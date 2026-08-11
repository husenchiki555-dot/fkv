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
import android.util.DisplayMetrics;
import android.view.WindowManager;

/** MediaProjection owner. Playback audio is an optional sidecar, never a capture dependency. */
public final class AutoCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String ACTION_STOP = "com.huseyn.elixircollector.V6_CAPTURE_STOP";

    private static final String CHANNEL = "royalevision_v6_capture";
    private static final int NOTIFICATION_ID = 9601;
    private static final long ANALYZE_EVERY_NS = 100_000_000L;

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
    private boolean destroying;

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
        startCaptureForeground();
        if (projection != null) return START_STICKY;
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
                        store.stopped("Android stopped screen capture");
                        stopSelf();
                    }
                }
            }, analysisHandler);
            createVirtualDisplay();
            // AudioPlaybackCapture internally catches setup failures. This outer
            // guard ensures even vendor-specific RuntimeExceptions are isolated.
            try { audio.start(projection); }
            catch (RuntimeException ignored) { audioAvailable = false; }
            return START_STICKY;
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
        windows.getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.max(360, metrics.widthPixels);
        int height = Math.max(640, metrics.heightPixels);
        int dpi = Math.max(160, metrics.densityDpi);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, analysisHandler);
        display = projection.createVirtualDisplay("RoyaleVisionV6", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, analysisHandler);
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
            if (!frame.valid()) return;
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
        return builder.setContentTitle("RoyaleVision v6")
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
        store.stopped(reason);
        stopSelf();
    }

    @Override public void onDestroy() {
        destroying = true;
        store.stopped("RoyaleVision stopped");
        if (audio != null) audio.stop();
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
