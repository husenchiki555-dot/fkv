package com.huseyn.elixircollector;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.WindowMetrics;

/** MediaProjection owner. Playback audio is an optional sidecar, never a capture dependency. */
public final class AutoCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String ACTION_STOP = "com.huseyn.elixircollector.V6_CAPTURE_STOP";

    private static final String CHANNEL = "royalevision_v6_capture";
    private static final int NOTIFICATION_ID = 9601;
    private static final long ANALYZE_EVERY_NS = 100_000_000L;
    private static final String HIDDEN_APP_MESSAGE =
            "Shared app is not visible; tracking is paused until it returns";
    private static final String NO_FRAMES_MESSAGE =
            "Screen sharing started but no usable app frames arrived";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private SnapshotStore store;
    private GameSessionEngine engine;
    private AudioEvidenceEngine audio;
    private volatile boolean audioAvailable;
    private volatile AudioEvidenceEngine.Fingerprint latestAudio;
    private long lastAnalysisNs;
    private int frameErrors;
    private int invalidFrames;
    private boolean destroying;
    private String stopReason;
    private boolean receivedFrame;
    private long captureStartedElapsed;
    private int captureWidth, captureHeight, captureDpi;
    private int pendingResizeWidth, pendingResizeHeight;

    private final Runnable captureHeartbeat = new Runnable() {
        @Override public void run() {
            if (destroying || projection == null || analysisHandler == null) return;
            store.touchCapture();
            if (!receivedFrame && captureStartedElapsed > 0L
                    && SystemClock.elapsedRealtime() - captureStartedElapsed >= 6_000L
                    && store.lastError().isEmpty()) {
                store.reportNonFatalError(NO_FRAMES_MESSAGE);
            }
            analysisHandler.postDelayed(this, 1_000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        store = new SnapshotStore(this);
        engine = new GameSessionEngine(this);
        analysisThread = new HandlerThread("RoyaleVisionV6CV");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
        audio = new AudioEvidenceEngine(this, new AudioEvidenceEngine.Listener() {
            @Override public void onAudioAvailability(boolean available) { audioAvailable = available; }
            @Override public void onTransient(AudioEvidenceEngine.Fingerprint fingerprint) { latestAudio = fingerprint; }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startCaptureForeground();
        } catch (RuntimeException error) {
            stopWithReason("Capture foreground service blocked: "
                    + error.getClass().getSimpleName());
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY;
        if (intent == null) {
            stopWithReason("Capture permission missing");
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else { //noinspection deprecation
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopWithReason("Capture permission missing");
            return START_NOT_STICKY;
        }
        try {
            store.clearForStart();
            store.setCaptureActive(true);
            engine.resetCapture();
            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (!destroying) {
                        stopReason = "Android stopped screen capture";
                        store.stopped(stopReason);
                        stopSelf();
                    }
                }

                @Override public void onCapturedContentResize(int width, int height) {
                    if (width <= 0 || height <= 0) return;
                    pendingResizeWidth = width;
                    pendingResizeHeight = height;
                    if (display != null) resizeVirtualDisplay(width, height);
                }

                @Override public void onCapturedContentVisibilityChanged(boolean isVisible) {
                    if (!isVisible) store.reportNonFatalError(HIDDEN_APP_MESSAGE);
                    else store.clearErrorIf(HIDDEN_APP_MESSAGE);
                }
            }, analysisHandler);
            createVirtualDisplay();
            captureStartedElapsed = SystemClock.elapsedRealtime();
            analysisHandler.post(captureHeartbeat);
            // AudioPlaybackCapture internally catches setup failures. This outer
            // guard ensures even vendor-specific RuntimeExceptions are isolated.
            try { audio.start(projection); }
            catch (RuntimeException ignored) { audioAvailable = false; }
            return START_NOT_STICKY;
        } catch (RuntimeException error) {
            stopWithReason("Capture setup error: " + error.getClass().getSimpleName());
            return START_NOT_STICKY;
        }
    }

    private void startCaptureForeground() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressWarnings("deprecation")
    private void createVirtualDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        int width, height;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics windowMetrics = windows.getMaximumWindowMetrics();
            Rect bounds = windowMetrics.getBounds();
            width = bounds.width();
            height = bounds.height();
            metrics = getResources().getDisplayMetrics();
        } else {
            windows.getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        if (pendingResizeWidth > 0 && pendingResizeHeight > 0) {
            width = pendingResizeWidth;
            height = pendingResizeHeight;
        }
        width = Math.max(200, width);
        height = Math.max(300, height);
        int dpi = Math.max(160, metrics.densityDpi);
        captureWidth = width;
        captureHeight = height;
        captureDpi = dpi;
        reader = newReader(width, height);
        reader.setOnImageAvailableListener(this::onImage, analysisHandler);
        display = projection.createVirtualDisplay("RoyaleVisionV6", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, analysisHandler);
        if (display == null) throw new IllegalStateException("Virtual display was not created");
    }

    private ImageReader newReader(int width, int height) {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
    }

    /** Keep the analysis surface matched to Android 14+ single-app sharing. */
    private void resizeVirtualDisplay(int requestedWidth, int requestedHeight) {
        int width = Math.max(200, requestedWidth);
        int height = Math.max(300, requestedHeight);
        if (display == null || reader == null
                || (width == captureWidth && height == captureHeight)) return;
        ImageReader replacement = null;
        try {
            replacement = newReader(width, height);
            replacement.setOnImageAvailableListener(this::onImage, analysisHandler);
            display.resize(width, height, captureDpi);
            display.setSurface(replacement.getSurface());
            ImageReader old = reader;
            reader = replacement;
            replacement = null;
            captureWidth = width;
            captureHeight = height;
            old.setOnImageAvailableListener(null, null);
            old.close();
            engine.resetCapture();
        } catch (RuntimeException error) {
            if (replacement != null) replacement.close();
            store.reportNonFatalError("Capture resize failed: " + error.getClass().getSimpleName());
        }
    }

    private void onImage(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            long nowNs = System.nanoTime();
            if (nowNs - lastAnalysisNs < ANALYZE_EVERY_NS) return;
            lastAnalysisNs = nowNs;
            ImagePixelFrame frame = new ImagePixelFrame(image);
            if (!frame.valid()) {
                invalidFrames++;
                if (invalidFrames >= 8) {
                    store.reportNonFatalError("Unsupported screen-capture frame format");
                    invalidFrames = 0;
                }
                return;
            }
            invalidFrames = 0;
            receivedFrame = true;
            store.clearErrorIf(NO_FRAMES_MESSAGE);
            SessionSnapshot snapshot = engine.process(frame, System.currentTimeMillis(),
                    audioAvailable, latestAudio);
            store.publish(snapshot);
            frameErrors = 0;
        } catch (RuntimeException error) {
            frameErrors++;
            if (frameErrors >= 8) {
                store.reportNonFatalError("Repeated frame-analysis error: " + error.getClass().getSimpleName());
                frameErrors = 0;
            }
        } finally {
            if (image != null) image.close();
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, AutoMainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AutoCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("RoyaleVision v6.1")
                .setContentText("Adaptive on-device visual tracking")
                .setSmallIcon(R.drawable.ic_notification_elixir)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "RoyaleVision capture", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("On-device screen analysis with optional playback audio");
        channel.setShowBadge(false);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void stopWithReason(String reason) {
        stopReason = reason;
        store.stopped(reason);
        stopSelf();
    }

    @Override public void onDestroy() {
        destroying = true;
        if (analysisHandler != null) analysisHandler.removeCallbacks(captureHeartbeat);
        if (stopReason == null || stopReason.isEmpty()) store.stopped("RoyaleVision stopped");
        if (audio != null) audio.stop();
        latestAudio = null;
        audioAvailable = false;
        if (reader != null) {
            try { reader.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) {}
        }
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) {} display = null; }
        if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) {} projection = null; }
        if (reader != null) { try { reader.close(); } catch (RuntimeException ignored) {} reader = null; }
        if (analysisThread != null) analysisThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
