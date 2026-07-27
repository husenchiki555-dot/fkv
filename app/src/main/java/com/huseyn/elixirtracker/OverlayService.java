package com.huseyn.elixirtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Locale;

public class OverlayService extends Service {
    private static final String CHANNEL_ID = "elixir_overlay_channel";
    private static final int NOTIFICATION_ID = 7102;
    private static final String ACTION_STOP = "com.huseyn.elixirtracker.STOP";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Integer> recentCosts = new ArrayDeque<>();

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private View overlayView;
    private LinearLayout controlsPanel;
    private TextView elixirText;
    private TextView historyText;
    private TextView pauseButton;
    private TextView collapseButton;
    private TextView[] speedButtons;

    private double elixir = 5.0;
    private double speedMultiplier = 1.0;
    private boolean regenerationRunning = true;
    private boolean collapsed = false;
    private long lastTickNanos;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            if (lastTickNanos == 0L) {
                lastTickNanos = now;
            }
            double elapsedSeconds = (now - lastTickNanos) / 1_000_000_000.0;
            lastTickNanos = now;

            if (regenerationRunning && elixir < 10.0) {
                elixir = Math.min(10.0, elixir + elapsedSeconds * (speedMultiplier / 2.8));
                updateDisplay();
            }
            handler.postDelayed(this, 100L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        lastTickNanos = System.nanoTime();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show();
            Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permissionIntent);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (overlayView == null) {
            showOverlay();
            handler.post(ticker);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (RuntimeException ignored) {
                // The system may already have detached the view.
            }
        }
        overlayView = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        windowParams = new WindowManager.LayoutParams(
                dp(310),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = dp(12);
        windowParams.y = dp(150);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.setBackground(roundRect(Color.argb(238, 27, 20, 40), 18, Color.argb(210, 168, 108, 255)));
        root.setElevation(dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)));

        TextView dragHandle = label("⋮⋮", 22, Color.rgb(206, 183, 242), true);
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setBackground(roundRect(Color.argb(80, 255, 255, 255), 10, Color.TRANSPARENT));
        header.addView(dragHandle, new LinearLayout.LayoutParams(dp(42), dp(34)));
        installDragHandler(dragHandle);

        elixirText = label("5.0", 25, Color.WHITE, true);
        elixirText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elixirParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        elixirParams.leftMargin = dp(6);
        elixirParams.rightMargin = dp(6);
        header.addView(elixirText, elixirParams);

        pauseButton = compactButton("Ⅱ");
        pauseButton.setContentDescription("Pause regeneration");
        pauseButton.setOnClickListener(v -> {
            regenerationRunning = !regenerationRunning;
            lastTickNanos = System.nanoTime();
            pauseButton.setText(regenerationRunning ? "Ⅱ" : "▶");
            pauseButton.setContentDescription(regenerationRunning
                    ? "Pause regeneration" : "Resume regeneration");
        });
        header.addView(pauseButton, buttonParams(dp(38), dp(34), dp(4)));

        collapseButton = compactButton("−");
        collapseButton.setContentDescription("Collapse overlay");
        collapseButton.setOnClickListener(v -> toggleCollapsed());
        header.addView(collapseButton, buttonParams(dp(38), dp(34), 0));

        controlsPanel = new LinearLayout(this);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(controlsPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        historyText = label("Played: —", 13, Color.rgb(205, 193, 224), false);
        historyText.setGravity(Gravity.CENTER);
        historyText.setPadding(0, dp(5), 0, dp(7));
        controlsPanel.addView(historyText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addSpeedControls();
        addCostButtons();
        addActionControls();

        overlayView = root;
        windowManager.addView(overlayView, windowParams);
        updateDisplay();
    }

    private void addSpeedControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(7));
        controlsPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = label("REGEN", 12, Color.rgb(194, 174, 220), true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1f));

        speedButtons = new TextView[3];
        String[] labels = {"1×", "2×", "3×"};
        double[] values = {1.0, 2.0, 3.0};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            speedButtons[i] = compactButton(labels[i]);
            speedButtons[i].setOnClickListener(v -> {
                speedMultiplier = values[index];
                lastTickNanos = System.nanoTime();
                updateSpeedButtons();
            });
            row.addView(speedButtons[i], buttonParams(dp(48), dp(34), i == labels.length - 1 ? 0 : dp(5)));
        }
        updateSpeedButtons();
    }

    private void addCostButtons() {
        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48));
            rowParams.bottomMargin = dp(5);
            controlsPanel.addView(row, rowParams);

            for (int column = 0; column < 3; column++) {
                int cost = rowIndex * 3 + column + 1;
                TextView button = costButton(String.valueOf(cost));
                button.setContentDescription("Spend " + cost + " elixir");
                button.setOnClickListener(v -> spendElixir(cost));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (column < 2) {
                    params.rightMargin = dp(5);
                }
                row.addView(button, params);
            }
        }
    }

    private void addActionControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        controlsPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(43)));

        TextView undo = actionButton("UNDO", Color.rgb(63, 76, 108));
        undo.setOnClickListener(v -> undoLastCost());
        row.addView(undo, weightedButtonParams(dp(5)));

        TextView plus = actionButton("+1", Color.rgb(47, 105, 82));
        plus.setOnClickListener(v -> {
            elixir = Math.min(10.0, elixir + 1.0);
            updateDisplay();
        });
        row.addView(plus, weightedButtonParams(dp(5)));

        TextView reset = actionButton("RESET 5", Color.rgb(102, 67, 137));
        reset.setOnClickListener(v -> {
            elixir = 5.0;
            recentCosts.clear();
            lastTickNanos = System.nanoTime();
            updateDisplay();
        });
        row.addView(reset, weightedButtonParams(dp(5)));

        TextView close = actionButton("×", Color.rgb(129, 49, 65));
        close.setContentDescription("Close overlay");
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, weightedButtonParams(0));
    }

    private void spendElixir(int cost) {
        elixir = Math.max(0.0, elixir - cost);
        recentCosts.addLast(cost);
        while (recentCosts.size() > 4) {
            recentCosts.removeFirst();
        }
        updateDisplay();
    }

    private void undoLastCost() {
        Integer cost = recentCosts.pollLast();
        if (cost == null) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        elixir = Math.min(10.0, elixir + cost);
        updateDisplay();
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        controlsPanel.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapseButton.setText(collapsed ? "+" : "−");
        collapseButton.setContentDescription(collapsed ? "Expand overlay" : "Collapse overlay");
        windowParams.width = collapsed ? dp(190) : dp(310);
        windowManager.updateViewLayout(overlayView, windowParams);
    }

    private void updateDisplay() {
        if (elixirText != null) {
            elixirText.setText(String.format(Locale.US, "%.1f / 10", elixir));
            if (elixir < 3.0) {
                elixirText.setTextColor(Color.rgb(255, 140, 150));
            } else if (elixir >= 9.5) {
                elixirText.setTextColor(Color.rgb(207, 166, 255));
            } else {
                elixirText.setTextColor(Color.WHITE);
            }
        }

        if (historyText != null) {
            if (recentCosts.isEmpty()) {
                historyText.setText("Played: —");
            } else {
                StringBuilder builder = new StringBuilder("Played: ");
                boolean first = true;
                for (Integer value : recentCosts) {
                    if (!first) {
                        builder.append(" • ");
                    }
                    builder.append(value);
                    first = false;
                }
                historyText.setText(builder.toString());
            }
        }
    }

    private void updateSpeedButtons() {
        if (speedButtons == null) {
            return;
        }
        for (int i = 0; i < speedButtons.length; i++) {
            boolean selected = Math.abs(speedMultiplier - (i + 1.0)) < 0.01;
            speedButtons[i].setBackground(roundRect(
                    selected ? Color.rgb(139, 84, 207) : Color.rgb(60, 49, 78),
                    10,
                    selected ? Color.rgb(211, 178, 255) : Color.TRANSPARENT));
            speedButtons[i].setTextColor(selected ? Color.WHITE : Color.rgb(210, 198, 228));
        }
    }

    private void installDragHandler(View dragHandle) {
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = windowParams.x;
                        initialY = windowParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        windowParams.x = initialX + Math.round(event.getRawX() - initialTouchX);
                        windowParams.y = initialY + Math.round(event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, windowParams);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                pendingIntentFlags());

        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                pendingIntentFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Elixir Overlay is running")
                .setContentText("Tap card costs in the floating counter")
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Elixir overlay",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the user-controlled elixir counter visible over the game");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView compactButton(String text) {
        TextView button = label(text, 15, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(roundRect(Color.rgb(60, 49, 78), 10, Color.TRANSPARENT));
        return button;
    }

    private TextView costButton(String text) {
        TextView button = label(text, 19, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(Color.rgb(89, 55, 132), 12, Color.rgb(165, 111, 235)));
        return button;
    }

    private TextView actionButton(String text, int color) {
        TextView button = label(text, 12, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(color, 10, Color.TRANSPARENT));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(int width, int height, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.rightMargin = rightMargin;
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonParams(int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.rightMargin = rightMargin;
        return params;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
