package com.huseyn.elixircollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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

/** Foreground MediaProjection service for the fully local automatic-CV build. */
public final class AutoCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String ACTION_STOP = "com.huseyn.elixircollector.AUTO_CAPTURE_STOP";

    public static final String PREFS = "royalevision_auto_cv";
    public static final String K_CAPTURE = "capture_active";
    public static final String K_MATCH = "match_active";
    public static final String K_SESSION = "session_id";
    public static final String K_LOCAL_ELIXIR = "local_elixir";
    public static final String K_ELIXIR_CONF = "elixir_conf";
    public static final String K_HAND_CHANGE_MS = "hand_change_ms";
    public static final String K_LOCAL_PLAY_MS = "local_play_ms";
    public static final String K_LOCAL_ABILITY_MS = "local_ability_ms";
    public static final String K_ENEMY_CANDIDATE_MS = "enemy_candidate_ms";
    public static final String K_ARENA_CHANGE = "arena_change";
    public static final String K_HINT = "effect_hint";
    public static final String K_STATUS = "status";
    public static final String K_MATCH_CLOCK_START_MS = "match_clock_start_ms";
    public static final String K_MATCH_ANCHOR_MS = "match_anchor_ms";
    public static final String K_MATCH_ANCHOR_ELIXIR = "match_anchor_elixir";

    private static final String CHANNEL = "royalevision_auto_capture";
    private static final int NOTIFICATION_ID = 9505;
    private static final long ANALYZE_EVERY_NS = 90_000_000L; // ~11 fps
    private static final int START_STABLE_FRAMES = 10;
    private static final int END_MISSING_FRAMES = 28;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private final FrameAnalyzer analyzer = new FrameAnalyzer();
    private SharedPreferences prefs;
    private long lastAnalysisNs;
    private int hudStableFrames;
    private int hudMissingFrames;
    private boolean matchActive;
    private double smoothedElixir = Double.NaN;
    private double smoothedConfidence;
    private double stableMaxElixir = Double.NaN;
    private double stableMinElixir = Double.NaN;
    private long firstHudMs;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannel();
        analysisThread = new HandlerThread("RoyaleVisionCV");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startCaptureForeground();
        if (projection != null) return START_STICKY;
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode == 0 || resultData == null) {
            writeStopped("Capture permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            clearLiveDetectionState();
            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    writeStopped("Screen capture stopped");
                    stopSelf();
                }
            }, analysisHandler);
            startVirtualDisplay();
            prefs.edit()
                    .putBoolean(K_CAPTURE, true)
                    .putBoolean(K_MATCH, false)
                    .remove(K_LOCAL_ELIXIR)
                    .putFloat(K_ELIXIR_CONF, 0f)
                    .putString(K_STATUS, "SEARCHING FOR BATTLE HUD")
                    .apply();
        } catch (RuntimeException e) {
            writeStopped("Capture error: " + e.getClass().getSimpleName());
            stopSelf();
        }
        return START_STICKY;
    }

    private void startCaptureForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startVirtualDisplay() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(dm);
        int width = Math.max(360, dm.widthPixels);
        int height = Math.max(640, dm.heightPixels);
        int dpi = Math.max(160, dm.densityDpi);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, analysisHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "RoyaleVisionAutoCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                analysisHandler);
    }

    private void onImage(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            long nowNs = System.nanoTime();
            if (nowNs - lastAnalysisNs < ANALYZE_EVERY_NS) return;
            lastAnalysisNs = nowNs;
            long nowMs = System.currentTimeMillis();
            FrameAnalyzer.Result r = analyzer.analyze(image, nowMs);
            updateFromResult(r, nowMs);
        } catch (RuntimeException ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private void updateFromResult(FrameAnalyzer.Result r, long nowMs) {
        boolean reliableHudFrame = r.battleHud
                && !Double.isNaN(r.localElixir)
                && r.localElixir >= 0.45
                && r.elixirConfidence >= 0.42;

        if (reliableHudFrame) {
            if (Double.isNaN(smoothedElixir)) smoothedElixir = r.localElixir;
            else {
                // Drops must be followed quickly; normal regeneration/noise is smoothed.
                double alpha = r.localElixir < smoothedElixir - 0.45 ? 0.78 : 0.30;
                smoothedElixir = smoothedElixir * (1.0 - alpha) + r.localElixir * alpha;
            }
            smoothedConfidence = smoothedConfidence * 0.68 + r.elixirConfidence * 0.32;

            if (hudStableFrames == 0) {
                firstHudMs = nowMs;
                stableMaxElixir = smoothedElixir;
                stableMinElixir = smoothedElixir;
            }
            hudStableFrames++;
            hudMissingFrames = 0;
            stableMaxElixir = Double.isNaN(stableMaxElixir)
                    ? smoothedElixir : Math.max(stableMaxElixir, smoothedElixir);
            stableMinElixir = Double.isNaN(stableMinElixir)
                    ? smoothedElixir : Math.min(stableMinElixir, smoothedElixir);
        } else {
            hudMissingFrames++;
            if (!matchActive) {
                hudStableFrames = 0;
                firstHudMs = 0L;
                stableMaxElixir = Double.NaN;
                stableMinElixir = Double.NaN;
                // Do not carry a false/menu Elixir reading into the next battle.
                if (hudMissingFrames >= 5) {
                    smoothedElixir = Double.NaN;
                    smoothedConfidence = 0.0;
                }
            } else {
                hudStableFrames = Math.max(0, hudStableFrames - 1);
            }
        }

        if (!matchActive
                && hudStableFrames >= START_STABLE_FRAMES
                && smoothedConfidence >= 0.44
                && !Double.isNaN(stableMaxElixir)
                && stableMaxElixir >= 0.55) {
            matchActive = true;
            int session = prefs.getInt(K_SESSION, 0) + 1;
            long clockStart = firstHudMs > 0L ? firstHudMs : nowMs;
            // Use the highest reliable Elixir seen in the stable opening window.
            // If the player spent a card during detection, this avoids anchoring
            // the opponent to the post-spend low value.
            double anchorElixir = Math.max(0.55, Math.min(10.0, stableMaxElixir));
            prefs.edit()
                    .putInt(K_SESSION, session)
                    .putLong(K_MATCH_CLOCK_START_MS, clockStart)
                    .putLong(K_MATCH_ANCHOR_MS, nowMs)
                    .putFloat(K_MATCH_ANCHOR_ELIXIR, (float)anchorElixir)
                    .apply();
        } else if (matchActive && hudMissingFrames >= END_MISSING_FRAMES) {
            matchActive = false;
            analyzer.resetTemporalState();
            hudStableFrames = 0;
            hudMissingFrames = 0;
            firstHudMs = 0L;
            stableMaxElixir = Double.NaN;
            stableMinElixir = Double.NaN;
            smoothedElixir = Double.NaN;
            smoothedConfidence = 0.0;
        }

        SharedPreferences.Editor e = prefs.edit()
                .putBoolean(K_CAPTURE, true)
                .putBoolean(K_MATCH, matchActive)
                .putFloat(K_ELIXIR_CONF, (float)smoothedConfidence)
                .putFloat(K_ARENA_CHANGE, (float)r.arenaChange)
                .putString(K_HINT, r.effectHint == null ? "" : r.effectHint);
        if (!Double.isNaN(smoothedElixir) && matchActive) {
            e.putFloat(K_LOCAL_ELIXIR, (float)smoothedElixir);
        } else if (!matchActive) {
            e.remove(K_LOCAL_ELIXIR);
        }
        if (r.handChanged && matchActive) e.putLong(K_HAND_CHANGE_MS, nowMs);
        if (r.localPlay && matchActive) e.putLong(K_LOCAL_PLAY_MS, nowMs);
        if (r.localAbilitySpend && matchActive) e.putLong(K_LOCAL_ABILITY_MS, nowMs);
        if (r.enemyCandidate && matchActive) e.putLong(K_ENEMY_CANDIDATE_MS, nowMs);

        String status;
        if (!matchActive) {
            if (hudStableFrames > 0) {
                status = "VERIFYING BATTLE HUD " + hudStableFrames + "/" + START_STABLE_FRAMES;
            } else status = "SEARCHING FOR BATTLE HUD";
        } else if (r.localPlay) {
            status = "YOUR PLAY • HAND CHANGED + ELIXIR DROP";
        } else if (r.localAbilitySpend) {
            status = "YOUR ELIXIR DROP • NO HAND CHANGE";
        } else if (r.enemyCandidate) {
            status = "OPPONENT ACTION CANDIDATE";
        } else {
            status = "AUTO WATCHING";
        }
        e.putString(K_STATUS, status).apply();
    }

    private void clearLiveDetectionState() {
        matchActive = false;
        hudStableFrames = 0;
        hudMissingFrames = 0;
        firstHudMs = 0L;
        stableMaxElixir = Double.NaN;
        stableMinElixir = Double.NaN;
        smoothedElixir = Double.NaN;
        smoothedConfidence = 0.0;
        analyzer.resetTemporalState();
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, AutoMainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AutoCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("RoyaleVision Auto")
                .setContentText("Local screen CV: Elixir + hand-change ownership guard")
                .setSmallIcon(R.drawable.ic_notification_elixir)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel c = new NotificationChannel(CHANNEL,
                "RoyaleVision automatic capture", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("On-device screen analysis while automatic tracking is enabled");
        c.setShowBadge(false);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    private void writeStopped(String status) {
        if (prefs != null) {
            prefs.edit()
                    .putBoolean(K_CAPTURE, false)
                    .putBoolean(K_MATCH, false)
                    .remove(K_LOCAL_ELIXIR)
                    .putFloat(K_ELIXIR_CONF, 0f)
                    .putString(K_STATUS, status)
                    .apply();
        }
    }

    @Override public void onDestroy() {
        writeStopped("AUTO STOPPED");
        if (reader != null) {
            try { reader.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) {}
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (RuntimeException ignored) {}
            virtualDisplay = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) {}
            projection = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (RuntimeException ignored) {}
            reader = null;
        }
        if (analysisThread != null) analysisThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
