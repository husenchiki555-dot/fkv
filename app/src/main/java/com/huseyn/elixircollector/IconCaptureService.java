package com.huseyn.elixircollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Live friendly-practice counter based on visible screen information only.
 *
 * The primary signal is the shared purple elixir-cost badge that appears during a deployment.
 * This avoids maintaining a separate troop model for every troop, spell, building, evolution,
 * skin, arena or level. A small local digit recognizer reads the cost displayed in that badge.
 */
public class IconCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "capture_result_code";
    public static final String EXTRA_RESULT_DATA = "capture_result_data";
    public static final String ACTION_STOP = "com.huseyn.elixircollector.ICON_STOP";

    private static final String CHANNEL_ID = "elixir_icon_practice";
    private static final int NOTIFICATION_ID = 7401;
    private static final String PREFS = "icon_vision_settings";
    private static final String KEY_START_PRESET = "start_preset";

    private static final long ANALYZE_INTERVAL_MS = 170L;
    private static final int BATTLE_LOCK_FRAMES = 3;
    private static final int BATTLE_LOST_FRAMES = 12;
    private static final long TRACK_MATCH_WINDOW_MS = 560L;
    private static final long TRACK_EXPIRE_MS = 1050L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DigitBank digitBank = new DigitBank();
    private final List<IconTrack> iconTracks = new ArrayList<>();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean projectionStopping;
    private long lastAnalyzedAt;

    private byte[] previousArenaGray;
    private int previousMaskWidth;
    private int previousMaskHeight;

    private volatile boolean inBattle;
    private int battleVisibleFrames;
    private int battleMissingFrames;
    private long battleStartedAtMs;
    private double startPreset = 7.5;

    private volatile double estimatedElixir = 7.5;
    private volatile boolean regenerationRunning;
    private volatile boolean automaticSpeed = true;
    private volatile double manualSpeed = 1.0;
    private long lastTickNanos;

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlayRoot;
    private LinearLayout controlsPanel;
    private TextView numberView;
    private TextView statusView;
    private TextView presetView;
    private TextView[] speedViews;
    private boolean expanded;
    private int bubbleState = 3; // 0 normal, 1 warning, 2 detected, 3 waiting

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            if (lastTickNanos == 0L) {
                lastTickNanos = now;
            }
            double elapsedSeconds = (now - lastTickNanos) / 1_000_000_000.0;
            lastTickNanos = now;

            if (inBattle && regenerationRunning && estimatedElixir < 10.0) {
                estimatedElixir = Math.min(10.0,
                        estimatedElixir + elapsedSeconds * (currentSpeedMultiplier() / 2.8));
                updateDisplay();
            }
            mainHandler.postDelayed(this, 100L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        startPreset = prefs.getFloat(KEY_START_PRESET, 7.5f);
        if (Math.abs(startPreset - 5.0) > 0.1 && Math.abs(startPreset - 7.5) > 0.1) {
            startPreset = 7.5;
        }
        estimatedElixir = startPreset;
        digitBank.build();
        lastTickNanos = System.nanoTime();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (overlayRoot == null) {
            showTinyOverlay();
            mainHandler.post(ticker);
        }
        if (mediaProjection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent == null ? 0 : intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (intent == null) {
            resultData = null;
        } else if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultData == null) {
            setStatus("Screen-capture permission missing", 1);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(resultCode, resultData);
        } catch (RuntimeException error) {
            setStatus("Capture failed: " + error.getClass().getSimpleName(), 1);
            Toast.makeText(this, "Could not start screen analysis", Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            throw new IllegalStateException("No MediaProjection token");
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!projectionStopping) {
                    mainHandler.post(() -> {
                        setStatus("Screen capture stopped", 1);
                        stopSelf();
                    });
                }
            }
        }, mainHandler);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        int density = Math.max(1, metrics.densityDpi);

        captureThread = new HandlerThread("ElixirIconCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ElixirCollectorIconVision",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        setStatus("WAITING FOR BATTLE", 3);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now - lastAnalyzedAt < ANALYZE_INTERVAL_MS) {
                return;
            }
            lastAnalyzedAt = now;

            Frame frame = new Frame(image);
            boolean battleUiVisible = detectBattleUi(frame);
            updateBattleState(battleUiVisible, now);
            if (inBattle) {
                detectCostIcons(frame, now);
            } else {
                previousArenaGray = null;
                previousMaskWidth = 0;
                previousMaskHeight = 0;
                iconTracks.clear();
            }
        } finally {
            image.close();
        }
    }

    /** Detects the long purple player elixir bar near the bottom of a battle screen. */
    private boolean detectBattleUi(Frame frame) {
        int width = frame.width;
        int height = frame.height;
        int step = clamp(width / 360, 2, 6);
        int left = (int) (width * 0.04);
        int right = (int) (width * 0.98);
        int top = (int) (height * 0.79);
        int bottom = (int) (height * 0.97);
        int sampledWidth = Math.max(1, (right - left) / step);
        int qualifyingRows = 0;

        for (int y = top; y < bottom; y += step * 2) {
            int purpleCount = 0;
            int first = Integer.MAX_VALUE;
            int last = -1;
            int sampleIndex = 0;
            for (int x = left; x < right; x += step) {
                int r = frame.r(x, y);
                int g = frame.g(x, y);
                int b = frame.b(x, y);
                if (isBarPurple(r, g, b)) {
                    purpleCount++;
                    first = Math.min(first, sampleIndex);
                    last = Math.max(last, sampleIndex);
                }
                sampleIndex++;
            }
            int span = last >= first ? last - first + 1 : 0;
            if (purpleCount >= sampledWidth * 0.11 && span >= sampledWidth * 0.28) {
                qualifyingRows++;
                if (qualifyingRows >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateBattleState(boolean visible, long now) {
        if (visible) {
            battleVisibleFrames++;
            battleMissingFrames = 0;
            if (!inBattle && battleVisibleFrames >= BATTLE_LOCK_FRAMES) {
                beginBattle(now);
            }
        } else {
            battleMissingFrames++;
            battleVisibleFrames = 0;
            if (inBattle && battleMissingFrames >= BATTLE_LOST_FRAMES) {
                finishBattle();
            }
        }
    }

    private void beginBattle(long now) {
        inBattle = true;
        regenerationRunning = true;
        automaticSpeed = true;
        estimatedElixir = startPreset;
        // The visible/actionable arena commonly appears after part of the intro has elapsed.
        // Seven seconds makes the default 7.5 preset and automatic x2/x3 transitions align better.
        battleStartedAtMs = now - (startPreset >= 7.0 ? 7_000L : 0L);
        lastTickNanos = System.nanoTime();
        iconTracks.clear();
        previousArenaGray = null;
        mainHandler.post(() -> {
            updateSpeedButtons();
            setStatus("BATTLE FOUND • ICON READER ACTIVE", 2);
        });
    }

    private void finishBattle() {
        inBattle = false;
        regenerationRunning = false;
        iconTracks.clear();
        previousArenaGray = null;
        mainHandler.post(() -> setStatus("WAITING FOR NEXT BATTLE", 3));
    }

    /**
     * Scans every playable arena position, including the lower arena for Miner/Drill/spells.
     * The hand and player elixir bar are excluded so their permanent cost badges are ignored.
     */
    private void detectCostIcons(Frame frame, long now) {
        int width = frame.width;
        int height = frame.height;
        int step = clamp(width / 270, 3, 7);
        int left = (int) (width * 0.02);
        int right = (int) (width * 0.98);
        int top = (int) (height * 0.07);
        int bottom = (int) (height * 0.76);
        int maskWidth = Math.max(1, (right - left + step - 1) / step);
        int maskHeight = Math.max(1, (bottom - top + step - 1) / step);
        int total = maskWidth * maskHeight;

        boolean[] purple = new boolean[total];
        byte[] gray = new byte[total];
        for (int my = 0; my < maskHeight; my++) {
            int y = Math.min(bottom - 1, top + my * step);
            for (int mx = 0; mx < maskWidth; mx++) {
                int x = Math.min(right - 1, left + mx * step);
                int r = frame.r(x, y);
                int g = frame.g(x, y);
                int b = frame.b(x, y);
                int index = my * maskWidth + mx;
                purple[index] = isBadgePurple(r, g, b);
                gray[index] = (byte) ((r * 30 + g * 59 + b * 11) / 100);
            }
        }

        byte[] previous = previousArenaGray;
        boolean comparable = previous != null
                && previousMaskWidth == maskWidth
                && previousMaskHeight == maskHeight;
        previousArenaGray = gray;
        previousMaskWidth = maskWidth;
        previousMaskHeight = maskHeight;
        if (!comparable) {
            return;
        }

        boolean[] visited = new boolean[total];
        int[] queue = new int[total];
        List<IconDetection> detections = new ArrayList<>();

        for (int start = 0; start < total; start++) {
            if (!purple[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            int area = 0;
            int minX = maskWidth;
            int maxX = 0;
            int minY = maskHeight;
            int maxY = 0;
            long motionTotal = 0L;

            while (head < tail) {
                int index = queue[head++];
                int x = index % maskWidth;
                int y = index / maskWidth;
                area++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                motionTotal += Math.abs((gray[index] & 0xff) - (previous[index] & 0xff));

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || nx >= maskWidth || ny < 0 || ny >= maskHeight) {
                            continue;
                        }
                        int next = ny * maskWidth + nx;
                        if (purple[next] && !visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }

            int boxW = maxX - minX + 1;
            int boxH = maxY - minY + 1;
            if (area < 6 || area > 420 || boxW < 3 || boxH < 3 || boxW > 34 || boxH > 38) {
                continue;
            }
            double aspect = boxW / (double) boxH;
            double fill = area / (double) (boxW * boxH);
            double motion = motionTotal / (double) area;
            if (aspect < 0.34 || aspect > 1.85 || fill < 0.12 || motion < 12.0) {
                continue;
            }

            int padX = Math.max(step * 2, boxW * step / 3);
            int padY = Math.max(step * 2, boxH * step / 3);
            Rect box = new Rect(
                    clamp(left + minX * step - padX, 0, width - 1),
                    clamp(top + minY * step - padY, 0, height - 1),
                    clamp(left + (maxX + 1) * step + padX, 1, width),
                    clamp(top + (maxY + 1) * step + padY, 1, height));

            DigitResult result = digitBank.recognize(frame, box);
            if (result != null && result.confidence >= 0.43) {
                detections.add(new IconDetection(
                        result.digit,
                        result.confidence,
                        (box.left + box.right) / 2,
                        (box.top + box.bottom) / 2));
            }
        }

        updateIconTracks(detections, now, width, height);
    }

    private void updateIconTracks(List<IconDetection> detections, long now,
                                  int screenWidth, int screenHeight) {
        double radius = Math.max(24.0, Math.min(screenWidth, screenHeight) * 0.065);
        for (IconDetection detection : detections) {
            IconTrack nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (IconTrack track : iconTracks) {
                if (track.cost != detection.cost || now - track.lastSeen > TRACK_MATCH_WINDOW_MS) {
                    continue;
                }
                double dx = track.x - detection.x;
                double dy = track.y - detection.y;
                double distance = Math.sqrt(dx * dx + dy * dy);
                if (distance < radius && distance < nearestDistance) {
                    nearest = track;
                    nearestDistance = distance;
                }
            }

            if (nearest == null) {
                iconTracks.add(new IconTrack(detection, now));
            } else {
                nearest.lastSeen = now;
                nearest.hits++;
                nearest.x = (nearest.x * 2 + detection.x) / 3;
                nearest.y = (nearest.y * 2 + detection.y) / 3;
                nearest.confidence = Math.max(nearest.confidence, detection.confidence);
                if (!nearest.processed && nearest.hits >= 2) {
                    nearest.processed = true;
                    final int cost = nearest.cost;
                    final double confidence = nearest.confidence;
                    mainHandler.post(() -> {
                        spendElixir(cost);
                        setStatus("ICON −" + cost + " • "
                                + String.format(Locale.US, "%.0f%%", confidence * 100.0), 2);
                    });
                }
            }
        }

        Iterator<IconTrack> iterator = iconTracks.iterator();
        while (iterator.hasNext()) {
            IconTrack track = iterator.next();
            if (now - track.lastSeen > TRACK_EXPIRE_MS) {
                iterator.remove();
            }
        }
    }

    private static boolean isBarPurple(int r, int g, int b) {
        return r >= 135 && b >= 120 && g <= 150
                && r + b >= g * 2 + 90
                && Math.abs(r - b) <= 145;
    }

    private static boolean isBadgePurple(int r, int g, int b) {
        return r >= 105 && b >= 120 && g <= 165
                && r + b >= g * 2 + 70
                && Math.max(r, b) - g >= 32;
    }

    private double currentSpeedMultiplier() {
        if (!automaticSpeed) {
            return manualSpeed;
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - battleStartedAtMs);
        if (elapsedMs < 120_000L) {
            return 1.0;
        }
        if (elapsedMs < 240_000L) {
            return 2.0;
        }
        return 3.0;
    }

    private void showTinyOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                dp(64),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(8);
        overlayParams.y = dp(92);

        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.VERTICAL);
        overlayRoot.setPadding(dp(4), dp(4), dp(4), dp(4));
        overlayRoot.setBackground(roundRect(Color.argb(232, 30, 18, 43), 15,
                Color.rgb(182, 102, 228)));
        overlayRoot.setElevation(dp(10));

        numberView = label("—", 20, Color.WHITE, true);
        numberView.setGravity(Gravity.CENTER);
        numberView.setContentDescription("Estimated opponent elixir. Tap to expand.");
        overlayRoot.addView(numberView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        installBubbleTouch(numberView);

        controlsPanel = new LinearLayout(this);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        controlsPanel.setPadding(dp(6), dp(7), dp(6), dp(4));
        controlsPanel.setVisibility(View.GONE);
        overlayRoot.addView(controlsPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = label("WAITING FOR BATTLE", 11, Color.rgb(231, 218, 240), true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(3), dp(3), dp(3), dp(5));
        controlsPanel.addView(statusView, matchWrap(dp(4)));

        presetView = label("Start preset: " + formatPreset(), 11,
                Color.rgb(191, 175, 205), false);
        presetView.setGravity(Gravity.CENTER);
        controlsPanel.addView(presetView, matchWrap(dp(5)));

        addSpeedControls();
        addCostButtons();
        addActionControls();

        windowManager.addView(overlayRoot, overlayParams);
        updateDisplay();
    }

    private void installBubbleTouch(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialRawX;
            private float initialRawY;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = overlayParams.x;
                        initialY = overlayParams.y;
                        initialRawX = event.getRawX();
                        initialRawY = event.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialRawX;
                        float dy = event.getRawY() - initialRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                            dragged = true;
                        }
                        overlayParams.x = initialX - Math.round(dx);
                        overlayParams.y = initialY + Math.round(dy);
                        windowManager.updateViewLayout(overlayRoot, overlayParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            toggleExpanded();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void toggleExpanded() {
        expanded = !expanded;
        controlsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        overlayParams.width = expanded ? dp(292) : dp(64);
        windowManager.updateViewLayout(overlayRoot, overlayParams);
    }

    private void addSpeedControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        controlsPanel.addView(row, matchHeight(dp(36), dp(5)));

        TextView pause = smallButton("Ⅱ");
        pause.setOnClickListener(v -> {
            regenerationRunning = !regenerationRunning;
            lastTickNanos = System.nanoTime();
            pause.setText(regenerationRunning ? "Ⅱ" : "▶");
        });
        row.addView(pause, fixedButton(dp(40), dp(4)));

        speedViews = new TextView[4];
        String[] names = {"A", "1×", "2×", "3×"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView speed = smallButton(names[i]);
            speed.setOnClickListener(v -> {
                if (index == 0) {
                    automaticSpeed = true;
                } else {
                    automaticSpeed = false;
                    manualSpeed = index;
                }
                lastTickNanos = System.nanoTime();
                updateSpeedButtons();
            });
            speedViews[i] = speed;
            row.addView(speed, fixedButton(dp(47), i == names.length - 1 ? 0 : dp(4)));
        }
        updateSpeedButtons();
    }

    private void addCostButtons() {
        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            controlsPanel.addView(row, matchHeight(dp(43), dp(4)));
            for (int column = 0; column < 3; column++) {
                final int cost = rowIndex * 3 + column + 1;
                TextView button = label(String.valueOf(cost), 17, Color.WHITE, true);
                button.setGravity(Gravity.CENTER);
                button.setBackground(roundRect(Color.rgb(89, 48, 115), 10,
                        Color.rgb(146, 88, 181)));
                button.setOnClickListener(v -> {
                    spendElixir(cost);
                    setStatus("MANUAL −" + cost, 0);
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(41), 1f);
                if (column < 2) {
                    params.rightMargin = dp(4);
                }
                row.addView(button, params);
            }
        }
    }

    private void addActionControls() {
        LinearLayout first = new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        controlsPanel.addView(first, matchHeight(dp(39), dp(4)));

        TextView plus = actionButton("+1", Color.rgb(45, 104, 78));
        plus.setOnClickListener(v -> {
            estimatedElixir = Math.min(10.0, estimatedElixir + 1.0);
            updateDisplay();
        });
        first.addView(plus, weighted(dp(4)));

        TextView minus = actionButton("−1", Color.rgb(119, 52, 65));
        minus.setOnClickListener(v -> {
            estimatedElixir = Math.max(0.0, estimatedElixir - 1.0);
            updateDisplay();
        });
        first.addView(minus, weighted(dp(4)));

        TextView reset = actionButton("RESET", Color.rgb(86, 58, 112));
        reset.setOnClickListener(v -> {
            estimatedElixir = startPreset;
            lastTickNanos = System.nanoTime();
            setStatus("Reset to " + formatPreset(), 0);
        });
        first.addView(reset, weighted(0));

        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        controlsPanel.addView(second, matchHeight(dp(39), 0));

        TextView preset = actionButton("5 ↔ 7.5", Color.rgb(91, 67, 45));
        preset.setOnClickListener(v -> {
            startPreset = startPreset >= 7.0 ? 5.0 : 7.5;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putFloat(KEY_START_PRESET, (float) startPreset)
                    .apply();
            if (presetView != null) {
                presetView.setText("Start preset: " + formatPreset());
            }
            setStatus("New matches start at " + formatPreset(), 0);
        });
        second.addView(preset, new LinearLayout.LayoutParams(0, dp(37), 2f));

        TextView close = actionButton("STOP", Color.rgb(124, 46, 62));
        close.setOnClickListener(v -> stopSelf());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(37), 1f);
        closeParams.leftMargin = dp(4);
        second.addView(close, closeParams);
    }

    private void spendElixir(int cost) {
        estimatedElixir = Math.max(0.0, estimatedElixir - cost);
        updateDisplay();
    }

    private void setStatus(String message, int state) {
        bubbleState = state;
        if (statusView != null) {
            statusView.setText(message);
        }
        updateDisplay();
        if (state == 2) {
            mainHandler.postDelayed(() -> {
                if (bubbleState == 2) {
                    bubbleState = inBattle ? 0 : 3;
                    updateDisplay();
                }
            }, 900L);
        }
    }

    private void updateDisplay() {
        if (numberView == null) {
            return;
        }
        numberView.setText(inBattle
                ? String.format(Locale.US, "%.1f", estimatedElixir)
                : "—");
        int fill;
        int stroke;
        if (bubbleState == 1) {
            fill = Color.rgb(151, 91, 33);
            stroke = Color.rgb(255, 194, 103);
        } else if (bubbleState == 2) {
            fill = Color.rgb(36, 119, 75);
            stroke = Color.rgb(139, 244, 181);
        } else if (bubbleState == 3) {
            fill = Color.rgb(53, 67, 102);
            stroke = Color.rgb(138, 172, 240);
        } else if (estimatedElixir < 3.0) {
            fill = Color.rgb(130, 42, 61);
            stroke = Color.rgb(255, 139, 160);
        } else {
            fill = Color.rgb(120, 53, 165);
            stroke = Color.rgb(225, 175, 255);
        }
        numberView.setBackground(roundRect(fill, 12, stroke));
    }

    private void updateSpeedButtons() {
        if (speedViews == null) {
            return;
        }
        for (int i = 0; i < speedViews.length; i++) {
            boolean selected = i == 0
                    ? automaticSpeed
                    : !automaticSpeed && Math.abs(manualSpeed - i) < 0.01;
            speedViews[i].setBackground(roundRect(
                    selected ? Color.rgb(153, 72, 198) : Color.rgb(59, 43, 72),
                    9,
                    selected ? Color.rgb(234, 188, 255) : Color.rgb(91, 72, 106)));
        }
    }

    private String formatPreset() {
        return startPreset >= 7.0 ? "7.5" : "5.0";
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, IconMainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 41, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, IconCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 42, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_elixir_collector)
                .setContentTitle("Elixir Collector — icon vision")
                .setContentText("Waiting for a battle or reading visible deployment costs")
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopPending).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Icon vision practice",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible while user-approved screen analysis is active");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        projectionStopping = true;

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (RuntimeException ignored) {
                // Android may already have stopped the projection.
            }
            mediaProjection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        if (windowManager != null && overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (RuntimeException ignored) {
                // Window may already be detached.
            }
        }
        overlayRoot = null;
        stopForeground(true);
        super.onDestroy();
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView smallButton(String text) {
        TextView view = label(text, 14, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(Color.rgb(59, 43, 72), 9,
                Color.rgb(91, 72, 106)));
        return view;
    }

    private TextView actionButton(String text, int color) {
        TextView view = label(text, 11, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(color, 9, Color.argb(90, 255, 255, 255)));
        return view;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottom;
        return params;
    }

    private LinearLayout.LayoutParams matchHeight(int height, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.bottomMargin = bottom;
        return params;
    }

    private LinearLayout.LayoutParams fixedButton(int width, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(34));
        params.rightMargin = right;
        return params;
    }

    private LinearLayout.LayoutParams weighted(int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(37), 1f);
        params.rightMargin = right;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Frame {
        final int width;
        final int height;
        private final ByteBuffer buffer;
        private final int pixelStride;
        private final int rowStride;
        private final int capacity;

        Frame(Image image) {
            Image.Plane plane = image.getPlanes()[0];
            width = image.getWidth();
            height = image.getHeight();
            buffer = plane.getBuffer();
            pixelStride = plane.getPixelStride();
            rowStride = plane.getRowStride();
            capacity = buffer.capacity();
        }

        int r(int x, int y) {
            return channel(x, y, 0);
        }

        int g(int x, int y) {
            return channel(x, y, 1);
        }

        int b(int x, int y) {
            return channel(x, y, 2);
        }

        private int channel(int x, int y, int channel) {
            int safeX = clamp(x, 0, width - 1);
            int safeY = clamp(y, 0, height - 1);
            int offset = safeY * rowStride + safeX * pixelStride + channel;
            if (offset < 0 || offset >= capacity) {
                return 0;
            }
            return buffer.get(offset) & 0xff;
        }
    }

    private static final class IconDetection {
        final int cost;
        final double confidence;
        final int x;
        final int y;

        IconDetection(int cost, double confidence, int x, int y) {
            this.cost = cost;
            this.confidence = confidence;
            this.x = x;
            this.y = y;
        }
    }

    private static final class IconTrack {
        final int cost;
        int x;
        int y;
        int hits = 1;
        double confidence;
        long lastSeen;
        boolean processed;

        IconTrack(IconDetection detection, long now) {
            cost = detection.cost;
            x = detection.x;
            y = detection.y;
            confidence = detection.confidence;
            lastSeen = now;
        }
    }

    private static final class DigitResult {
        final int digit;
        final double confidence;

        DigitResult(int digit, double confidence) {
            this.digit = digit;
            this.confidence = confidence;
        }
    }

    /** Tiny synthetic-template digit recognizer for the white number inside a purple cost badge. */
    private static final class DigitBank {
        private static final int NORM_W = 16;
        private static final int NORM_H = 24;
        private final List<DigitTemplate> templates = new ArrayList<>();

        void build() {
            templates.clear();
            Typeface[] faces = {
                    Typeface.DEFAULT_BOLD,
                    Typeface.MONOSPACE,
                    Typeface.create(Typeface.SERIF, Typeface.BOLD)
            };
            for (int digit = 1; digit <= 9; digit++) {
                for (Typeface face : faces) {
                    templates.add(new DigitTemplate(digit, render(digit, face)));
                }
            }
        }

        DigitResult recognize(Frame frame, Rect source) {
            Rect box = new Rect(
                    clamp(source.left, 0, frame.width - 1),
                    clamp(source.top, 0, frame.height - 1),
                    clamp(source.right, 1, frame.width),
                    clamp(source.bottom, 1, frame.height));
            if (box.width() < 4 || box.height() < 5) {
                return null;
            }

            int minX = box.right;
            int minY = box.bottom;
            int maxX = box.left - 1;
            int maxY = box.top - 1;
            int brightCount = 0;
            int scanStep = Math.max(1, Math.min(box.width(), box.height()) / 45);

            for (int y = box.top; y < box.bottom; y += scanStep) {
                for (int x = box.left; x < box.right; x += scanStep) {
                    int r = frame.r(x, y);
                    int g = frame.g(x, y);
                    int b = frame.b(x, y);
                    if (isDigitWhite(r, g, b)) {
                        brightCount++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (brightCount < 7 || maxX <= minX || maxY <= minY) {
                return null;
            }

            int glyphW = maxX - minX + 1;
            int glyphH = maxY - minY + 1;
            double glyphAspect = glyphW / (double) glyphH;
            // Two-digit level labels and horizontal UI text are deliberately rejected.
            if (glyphAspect > 0.95 || glyphAspect < 0.08) {
                return null;
            }

            boolean[] glyph = new boolean[NORM_W * NORM_H];
            int onCount = 0;
            for (int oy = 0; oy < NORM_H; oy++) {
                int y0 = minY + oy * glyphH / NORM_H;
                int y1 = minY + Math.max(1, (oy + 1) * glyphH / NORM_H);
                for (int ox = 0; ox < NORM_W; ox++) {
                    int x0 = minX + ox * glyphW / NORM_W;
                    int x1 = minX + Math.max(1, (ox + 1) * glyphW / NORM_W);
                    boolean found = false;
                    for (int y = y0; y <= Math.min(maxY, y1) && !found; y++) {
                        for (int x = x0; x <= Math.min(maxX, x1); x++) {
                            if (isDigitWhite(frame.r(x, y), frame.g(x, y), frame.b(x, y))) {
                                found = true;
                                break;
                            }
                        }
                    }
                    glyph[oy * NORM_W + ox] = found;
                    if (found) {
                        onCount++;
                    }
                }
            }
            if (onCount < 12 || onCount > glyph.length * 0.72) {
                return null;
            }

            int bestDigit = -1;
            double best = 0.0;
            for (DigitTemplate template : templates) {
                double score = shiftedF1(glyph, template.mask);
                if (score > best) {
                    best = score;
                    bestDigit = template.digit;
                }
            }
            return bestDigit < 0 ? null : new DigitResult(bestDigit, best);
        }

        private boolean[] render(int digit, Typeface face) {
            Bitmap bitmap = Bitmap.createBitmap(56, 76, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTextSize(62f);
            paint.setTypeface(face);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = 38f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(String.valueOf(digit), 28f, baseline, paint);

            int minX = bitmap.getWidth();
            int minY = bitmap.getHeight();
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 70) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }

            boolean[] normalized = new boolean[NORM_W * NORM_H];
            if (maxX <= minX || maxY <= minY) {
                return normalized;
            }
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            for (int oy = 0; oy < NORM_H; oy++) {
                int sy = minY + oy * height / NORM_H;
                for (int ox = 0; ox < NORM_W; ox++) {
                    int sx = minX + ox * width / NORM_W;
                    normalized[oy * NORM_W + ox] =
                            Color.alpha(bitmap.getPixel(sx, sy)) > 70;
                }
            }
            bitmap.recycle();
            return normalized;
        }

        private static double shiftedF1(boolean[] observed, boolean[] template) {
            double best = 0.0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int intersection = 0;
                    int observedCount = 0;
                    int templateCount = 0;
                    for (int y = 0; y < NORM_H; y++) {
                        for (int x = 0; x < NORM_W; x++) {
                            boolean a = observed[y * NORM_W + x];
                            int tx = x - dx;
                            int ty = y - dy;
                            boolean b = tx >= 0 && tx < NORM_W && ty >= 0 && ty < NORM_H
                                    && template[ty * NORM_W + tx];
                            if (a) {
                                observedCount++;
                            }
                            if (b) {
                                templateCount++;
                            }
                            if (a && b) {
                                intersection++;
                            }
                        }
                    }
                    double score = observedCount + templateCount == 0
                            ? 0.0
                            : (2.0 * intersection) / (observedCount + templateCount);
                    best = Math.max(best, score);
                }
            }
            return best;
        }

        private static boolean isDigitWhite(int r, int g, int b) {
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int luma = (r * 30 + g * 59 + b * 11) / 100;
            return luma >= 165 && max - min <= 92;
        }
    }

    private static final class DigitTemplate {
        final int digit;
        final boolean[] mask;

        DigitTemplate(int digit, boolean[] mask) {
            this.digit = digit;
            this.mask = mask;
        }
    }
}
