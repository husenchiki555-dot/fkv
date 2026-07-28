package com.huseyn.elixircollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User-started live practice analyzer.
 *
 * The detector deliberately uses a simple local visual-template learner rather than pretending
 * to be a finished universal troop model. It detects a large visual change on the opponent's side,
 * asks the user to label unknown deployments once, and reuses similar learned signatures later.
 */
public class LiveCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "capture_result_code";
    public static final String EXTRA_RESULT_DATA = "capture_result_data";
    public static final String ACTION_STOP = "com.huseyn.elixircollector.LIVE_STOP";
    public static final String PREFS_NAME = "live_templates";

    private static final String CHANNEL_ID = "elixir_live_practice";
    private static final int NOTIFICATION_ID = 7301;
    private static final int SAMPLE_W = 64;
    private static final int SAMPLE_H = 72;
    private static final long ANALYZE_INTERVAL_MS = 420L;
    private static final long DETECTION_COOLDOWN_MS = 2300L;
    private static final double MOTION_THRESHOLD = 20.0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TemplateStore templateStore = new TemplateStore();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean projectionStopping;

    private byte[] previousFrame;
    private byte[] pendingSignature;
    private long lastAnalyzedAt;
    private long lastDetectionAt;

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlayRoot;
    private LinearLayout controlsPanel;
    private TextView numberView;
    private TextView statusView;
    private TextView learnedView;
    private TextView[] speedViews;
    private boolean expanded;
    private int bubbleState; // 0 normal, 1 unknown, 2 auto match

    private double estimatedElixir = 5.0;
    private double speedMultiplier = 1.0;
    private boolean regenerationRunning = true;
    private long lastTickNanos;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            if (lastTickNanos == 0L) {
                lastTickNanos = now;
            }
            double elapsed = (now - lastTickNanos) / 1_000_000_000.0;
            lastTickNanos = now;
            if (regenerationRunning && estimatedElixir < 10.0) {
                estimatedElixir = Math.min(10.0,
                        estimatedElixir + elapsed * (speedMultiplier / 2.8));
                updateDisplay();
            }
            mainHandler.postDelayed(this, 100L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        templateStore.load(this);
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
            setStatus("Screen capture permission was not supplied", 1);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(resultCode, resultData);
        } catch (RuntimeException error) {
            setStatus("Capture failed: " + error.getClass().getSimpleName(), 1);
            Toast.makeText(this, "Could not start live capture", Toast.LENGTH_LONG).show();
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

        captureThread = new HandlerThread("ElixirLiveCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ElixirCollectorPractice",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        setStatus("LIVE • watching opponent side", 0);
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
            byte[] sampled = sampleOpponentArena(image);
            if (sampled == null) {
                return;
            }
            analyzeSample(sampled, now);
        } finally {
            image.close();
        }
    }

    private byte[] sampleOpponentArena(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();

        // Clash Royale's opponent deployment area is mainly in the upper-middle arena.
        int left = Math.max(0, (int) (width * 0.06));
        int right = Math.min(width - 1, (int) (width * 0.94));
        int top = Math.max(0, (int) (height * 0.15));
        int bottom = Math.min(height - 1, (int) (height * 0.53));
        int cropW = Math.max(1, right - left);
        int cropH = Math.max(1, bottom - top);

        byte[] output = new byte[SAMPLE_W * SAMPLE_H];
        int capacity = buffer.capacity();
        for (int sy = 0; sy < SAMPLE_H; sy++) {
            int py = top + (sy * cropH / SAMPLE_H);
            for (int sx = 0; sx < SAMPLE_W; sx++) {
                int px = left + (sx * cropW / SAMPLE_W);
                int offset = py * rowStride + px * pixelStride;
                if (offset < 0 || offset + 2 >= capacity) {
                    continue;
                }
                int r = buffer.get(offset) & 0xff;
                int g = buffer.get(offset + 1) & 0xff;
                int b = buffer.get(offset + 2) & 0xff;
                int gray = (r * 30 + g * 59 + b * 11) / 100;
                output[sy * SAMPLE_W + sx] = (byte) gray;
            }
        }
        return output;
    }

    private void analyzeSample(byte[] current, long now) {
        if (previousFrame == null) {
            previousFrame = current;
            return;
        }

        long totalDiff = 0L;
        int maximumDiff = 0;
        int maximumIndex = 0;
        for (int i = 0; i < current.length; i++) {
            int diff = Math.abs((current[i] & 0xff) - (previousFrame[i] & 0xff));
            totalDiff += diff;
            if (diff > maximumDiff) {
                maximumDiff = diff;
                maximumIndex = i;
            }
        }
        double meanDiff = totalDiff / (double) current.length;
        previousFrame = current;

        if (meanDiff < MOTION_THRESHOLD || now - lastDetectionAt < DETECTION_COOLDOWN_MS) {
            return;
        }
        lastDetectionAt = now;

        int centerX = maximumIndex % SAMPLE_W;
        int centerY = maximumIndex / SAMPLE_W;
        byte[] signature = makeSignature(current, centerX, centerY);
        TemplateMatch match = templateStore.findBest(signature);

        if (match != null && match.score <= 31.0) {
            mainHandler.post(() -> {
                pendingSignature = null;
                spendElixir(match.cost);
                setStatus("AUTO −" + match.cost + " • match "
                        + String.format(Locale.US, "%.0f%%", Math.max(0, 100 - match.score * 2)), 2);
            });
        } else {
            mainHandler.post(() -> {
                pendingSignature = signature;
                setStatus("NEW DEPLOYMENT • tap number, then choose cost", 1);
            });
        }
    }

    private byte[] makeSignature(byte[] frame, int centerX, int centerY) {
        final int size = 16;
        byte[] signature = new byte[size * size];
        int startX = centerX - size / 2;
        int startY = centerY - size / 2;
        int sum = 0;
        for (int y = 0; y < size; y++) {
            int sourceY = clamp(startY + y, 0, SAMPLE_H - 1);
            for (int x = 0; x < size; x++) {
                int sourceX = clamp(startX + x, 0, SAMPLE_W - 1);
                int value = frame[sourceY * SAMPLE_W + sourceX] & 0xff;
                signature[y * size + x] = (byte) value;
                sum += value;
            }
        }
        int mean = sum / signature.length;
        for (int i = 0; i < signature.length; i++) {
            int value = signature[i] & 0xff;
            int normalized = clamp(128 + (value - mean) * 2, 0, 255);
            signature[i] = (byte) normalized;
        }
        return signature;
    }

    private void showTinyOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                dp(66),
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
        overlayRoot.setBackground(roundRect(Color.argb(230, 30, 18, 43), 15,
                Color.rgb(182, 102, 228)));
        overlayRoot.setElevation(dp(10));

        numberView = label("5.0", 20, Color.WHITE, true);
        numberView.setGravity(Gravity.CENTER);
        numberView.setContentDescription("Estimated opponent elixir. Tap to expand.");
        numberView.setBackground(roundRect(Color.rgb(120, 53, 165), 12,
                Color.rgb(225, 175, 255)));
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

        statusView = label("Starting live capture…", 12, Color.rgb(231, 218, 240), true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(3), dp(3), dp(3), dp(5));
        controlsPanel.addView(statusView, matchWrap(dp(4)));

        learnedView = label("Learned visuals: " + templateStore.size(), 11,
                Color.rgb(191, 175, 205), false);
        learnedView.setGravity(Gravity.CENTER);
        controlsPanel.addView(learnedView, matchWrap(dp(5)));

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
                        // With END gravity, moving right reduces x.
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
        overlayParams.width = expanded ? dp(282) : dp(66);
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
        row.addView(pause, fixedButton(dp(42), dp(4)));

        speedViews = new TextView[3];
        String[] names = {"1×", "2×", "3×"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView speed = smallButton(names[i]);
            speed.setOnClickListener(v -> {
                speedMultiplier = index + 1.0;
                lastTickNanos = System.nanoTime();
                updateSpeedButtons();
            });
            speedViews[i] = speed;
            row.addView(speed, fixedButton(dp(50), i == names.length - 1 ? 0 : dp(4)));
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
                button.setOnClickListener(v -> labelOrSpend(cost));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(41), 1f);
                if (column < 2) {
                    params.rightMargin = dp(4);
                }
                row.addView(button, params);
            }
        }
    }

    private void addActionControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        controlsPanel.addView(row, matchHeight(dp(39), 0));

        TextView plus = actionButton("+1", Color.rgb(45, 104, 78));
        plus.setOnClickListener(v -> {
            estimatedElixir = Math.min(10.0, estimatedElixir + 1.0);
            updateDisplay();
        });
        row.addView(plus, weighted(dp(4)));

        TextView reset = actionButton("RESET", Color.rgb(86, 58, 112));
        reset.setOnClickListener(v -> {
            estimatedElixir = 5.0;
            pendingSignature = null;
            lastTickNanos = System.nanoTime();
            setStatus("Reset to 5", 0);
        });
        row.addView(reset, weighted(dp(4)));

        TextView forget = actionButton("FORGET", Color.rgb(91, 67, 45));
        forget.setOnClickListener(v -> {
            templateStore.clear(LiveCaptureService.this);
            pendingSignature = null;
            updateLearnedCount();
            setStatus("Learned visual templates cleared", 0);
        });
        row.addView(forget, weighted(dp(4)));

        TextView close = actionButton("×", Color.rgb(124, 46, 62));
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, weighted(0));
    }

    private void labelOrSpend(int cost) {
        if (pendingSignature != null) {
            templateStore.add(this, pendingSignature, cost);
            pendingSignature = null;
            updateLearnedCount();
            spendElixir(cost);
            setStatus("LEARNED −" + cost + " • future similar deployments auto-count", 2);
        } else {
            spendElixir(cost);
            setStatus("MANUAL −" + cost, 0);
        }
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
                    bubbleState = pendingSignature == null ? 0 : 1;
                    updateDisplay();
                }
            }, 900L);
        }
    }

    private void updateDisplay() {
        if (numberView == null) {
            return;
        }
        numberView.setText(String.format(Locale.US, "%.1f", estimatedElixir));
        int fill;
        int stroke;
        if (bubbleState == 1) {
            fill = Color.rgb(151, 91, 33);
            stroke = Color.rgb(255, 194, 103);
        } else if (bubbleState == 2) {
            fill = Color.rgb(36, 119, 75);
            stroke = Color.rgb(139, 244, 181);
        } else if (estimatedElixir < 3.0) {
            fill = Color.rgb(130, 42, 61);
            stroke = Color.rgb(255, 139, 160);
        } else {
            fill = Color.rgb(120, 53, 165);
            stroke = Color.rgb(225, 175, 255);
        }
        numberView.setBackground(roundRect(fill, 12, stroke));
    }

    private void updateLearnedCount() {
        if (learnedView != null) {
            learnedView.setText("Learned visuals: " + templateStore.size());
        }
    }

    private void updateSpeedButtons() {
        if (speedViews == null) {
            return;
        }
        for (int i = 0; i < speedViews.length; i++) {
            boolean selected = Math.abs(speedMultiplier - (i + 1.0)) < 0.01;
            speedViews[i].setBackground(roundRect(
                    selected ? Color.rgb(153, 72, 198) : Color.rgb(59, 43, 72),
                    9,
                    selected ? Color.rgb(234, 188, 255) : Color.rgb(91, 72, 106)));
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 31, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LiveCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 32, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_elixir_collector)
                .setContentTitle("Elixir Collector — live practice")
                .setContentText("Screen analysis is active. Tap to open controls.")
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
                    "Live practice analysis",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible while user-approved screen analysis is running");
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
                // Projection may already be stopped by Android.
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

    private static final class TemplateMatch {
        final int cost;
        final double score;

        TemplateMatch(int cost, double score) {
            this.cost = cost;
            this.score = score;
        }
    }

    private static final class VisualTemplate {
        final byte[] signature;
        final int cost;

        VisualTemplate(byte[] signature, int cost) {
            this.signature = signature;
            this.cost = cost;
        }
    }

    private static final class TemplateStore {
        private static final String KEY_JSON = "templates_json";
        private static final int MAX_TEMPLATES = 80;
        private final List<VisualTemplate> templates = new ArrayList<>();

        void load(Context context) {
            templates.clear();
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String raw = prefs.getString(KEY_JSON, "[]");
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.getJSONObject(i);
                    int cost = object.getInt("cost");
                    byte[] signature = Base64.decode(object.getString("sig"), Base64.NO_WRAP);
                    if (signature.length == 256 && cost >= 1 && cost <= 9) {
                        templates.add(new VisualTemplate(signature, cost));
                    }
                }
            } catch (JSONException | IllegalArgumentException ignored) {
                templates.clear();
            }
        }

        int size() {
            return templates.size();
        }

        void add(Context context, byte[] signature, int cost) {
            if (templates.size() >= MAX_TEMPLATES) {
                templates.remove(0);
            }
            templates.add(new VisualTemplate(signature.clone(), cost));
            save(context);
        }

        TemplateMatch findBest(byte[] signature) {
            VisualTemplate best = null;
            double bestScore = Double.MAX_VALUE;
            for (VisualTemplate template : templates) {
                if (template.signature.length != signature.length) {
                    continue;
                }
                long total = 0L;
                for (int i = 0; i < signature.length; i++) {
                    total += Math.abs((signature[i] & 0xff)
                            - (template.signature[i] & 0xff));
                }
                double score = total / (double) signature.length;
                if (score < bestScore) {
                    bestScore = score;
                    best = template;
                }
            }
            return best == null ? null : new TemplateMatch(best.cost, bestScore);
        }

        void clear(Context context) {
            templates.clear();
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_JSON)
                    .apply();
        }

        private void save(Context context) {
            JSONArray array = new JSONArray();
            for (VisualTemplate template : templates) {
                JSONObject object = new JSONObject();
                try {
                    object.put("cost", template.cost);
                    object.put("sig", Base64.encodeToString(template.signature, Base64.NO_WRAP));
                    array.put(object);
                } catch (JSONException ignored) {
                    // Primitive values should not fail JSON serialization.
                }
            }
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_JSON, array.toString())
                    .apply();
        }
    }
}
