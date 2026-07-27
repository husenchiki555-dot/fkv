package com.huseyn.elixircollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final String CHANNEL_ID = "elixir_collector_overlay";
    private static final int NOTIFICATION_ID = 9021;
    private static final String ACTION_STOP = "com.huseyn.elixircollector.STOP_OVERLAY";
    private static final double BASE_SECONDS_PER_ELIXIR = 2.8;
    private static final int COMPACT_WIDTH_DP = 96;
    private static final int EXPANDED_WIDTH_DP = 314;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Integer> history = new ArrayDeque<>();

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private LinearLayout expandedPanel;
    private TextView elixirValue;
    private TextView modeLabel;
    private TextView historyLabel;
    private TextView pauseButton;
    private TextView[] speedButtons;

    private double elixir = 5.0;
    private double multiplier = 1.0;
    private boolean running = true;
    private boolean compact = true;
    private long lastTick;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            if (lastTick == 0L) {
                lastTick = now;
            }
            double elapsed = (now - lastTick) / 1_000_000_000.0;
            lastTick = now;
            if (running && elixir < 10.0) {
                elixir = Math.min(10.0,
                        elixir + elapsed * multiplier / BASE_SECONDS_PER_ELIXIR);
                refresh();
            }
            handler.postDelayed(this, 100L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        lastTick = System.nanoTime();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating-window permission is missing", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (overlay == null) {
            showOverlay();
            handler.post(ticker);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (RuntimeException ignored) {
                // Already removed by Android.
            }
        }
        overlay = null;
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

        params = new WindowManager.LayoutParams(
                dp(COMPACT_WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        params.x = Math.max(dp(8), screenWidth - dp(COMPACT_WIDTH_DP) - dp(8));
        params.y = dp(72);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(5), dp(5), dp(5), dp(5));
        overlay.setBackground(panel(Color.argb(240, 24, 14, 34), 18,
                Color.rgb(199, 103, 235), dp(2)));
        overlay.setElevation(dp(12));

        LinearLayout compactBar = new LinearLayout(this);
        compactBar.setOrientation(LinearLayout.HORIZONTAL);
        compactBar.setGravity(Gravity.CENTER);
        overlay.addView(compactBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        elixirValue = label("5.0", 21, Color.WHITE, true);
        elixirValue.setGravity(Gravity.CENTER);
        elixirValue.setContentDescription("Estimated opponent elixir. Tap to open controls.");
        compactBar.addView(elixirValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        installDragAndTap(compactBar);

        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams expandedParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        expandedParams.topMargin = dp(4);
        overlay.addView(expandedPanel, expandedParams);

        addExpandedHeader();
        addSpeedRow();
        addCostGrid();
        addActionRows();

        historyLabel = label("LAST: —", 10, Color.rgb(181, 164, 193), false);
        historyLabel.setGravity(Gravity.CENTER);
        historyLabel.setPadding(0, dp(5), 0, dp(2));
        expandedPanel.addView(historyLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(25)));

        try {
            windowManager.addView(overlay, params);
            refresh();
        } catch (RuntimeException error) {
            Toast.makeText(this, "Could not display overlay: "
                    + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void addExpandedHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        expandedPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        modeLabel = label("OPPONENT • 1×", 11, Color.rgb(220, 184, 235), true);
        modeLabel.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(modeLabel, new LinearLayout.LayoutParams(0, dp(38), 1f));

        pauseButton = smallButton("Ⅱ");
        pauseButton.setContentDescription("Pause regeneration");
        pauseButton.setOnClickListener(v -> togglePause());
        row.addView(pauseButton, fixedButton(dp(42), dp(5)));

        TextView collapse = smallButton("−");
        collapse.setContentDescription("Collapse to corner number");
        collapse.setOnClickListener(v -> setCompact(true));
        row.addView(collapse, fixedButton(dp(42), dp(5)));

        TextView close = smallButton("×");
        close.setContentDescription("Close overlay");
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, fixedButton(dp(42), 0));
    }

    private void addSpeedRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(5));
        expandedPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView title = label("REGEN", 11, Color.rgb(196, 176, 209), true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));

        speedButtons = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TextView button = smallButton((i + 1) + "×");
            button.setOnClickListener(v -> {
                multiplier = index + 1.0;
                lastTick = System.nanoTime();
                refreshSpeedButtons();
                refresh();
            });
            speedButtons[i] = button;
            row.addView(button, fixedButton(dp(48), i == 2 ? 0 : dp(5)));
        }
        refreshSpeedButtons();
    }

    private void addCostGrid() {
        int cost = 1;
        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            rowParams.bottomMargin = dp(4);
            expandedPanel.addView(row, rowParams);

            for (int column = 0; column < 3; column++) {
                final int cardCost = cost++;
                TextView button = costButton(String.valueOf(cardCost));
                button.setContentDescription("Opponent spent " + cardCost + " elixir");
                button.setOnClickListener(v -> spend(cardCost));
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (column < 2) {
                    buttonParams.rightMargin = dp(4);
                }
                row.addView(button, buttonParams);
            }
        }
    }

    private void addActionRows() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        expandedPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView undo = actionButton("UNDO", Color.rgb(58, 67, 88));
        undo.setOnClickListener(v -> undo());
        row.addView(undo, weighted(dp(4)));

        TextView plus = actionButton("+1", Color.rgb(43, 103, 77));
        plus.setOnClickListener(v -> {
            elixir = Math.min(10.0, elixir + 1.0);
            refresh();
        });
        row.addView(plus, weighted(dp(4)));

        TextView reset = actionButton("RESET 5", Color.rgb(106, 61, 137));
        reset.setOnClickListener(v -> {
            elixir = 5.0;
            history.clear();
            lastTick = System.nanoTime();
            refresh();
        });
        row.addView(reset, weighted(0));
    }

    private void spend(int cost) {
        elixir = Math.max(0.0, elixir - cost);
        history.addLast(cost);
        while (history.size() > 5) {
            history.removeFirst();
        }
        lastTick = System.nanoTime();
        refresh();
    }

    private void undo() {
        Integer last = history.pollLast();
        if (last == null) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        elixir = Math.min(10.0, elixir + last);
        lastTick = System.nanoTime();
        refresh();
    }

    private void togglePause() {
        running = !running;
        lastTick = System.nanoTime();
        pauseButton.setText(running ? "Ⅱ" : "▶");
        pauseButton.setContentDescription(running ? "Pause regeneration" : "Resume regeneration");
        refresh();
    }

    private void setCompact(boolean shouldCompact) {
        compact = shouldCompact;
        expandedPanel.setVisibility(compact ? View.GONE : View.VISIBLE);
        params.width = dp(compact ? COMPACT_WIDTH_DP : EXPANDED_WIDTH_DP);
        clampToScreen();
        windowManager.updateViewLayout(overlay, params);
    }

    private void toggleCompact() {
        setCompact(!compact);
    }

    private void clampToScreen() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        params.x = Math.max(0, Math.min(params.x, screenWidth - params.width));
        params.y = Math.max(0, Math.min(params.y, screenHeight - dp(48)));
    }

    private void refresh() {
        if (elixirValue != null) {
            elixirValue.setText(String.format(Locale.US, "%.1f", elixir));
            if (elixir < 2.0) {
                elixirValue.setTextColor(Color.rgb(255, 127, 144));
            } else if (elixir >= 9.5) {
                elixirValue.setTextColor(Color.rgb(235, 174, 255));
            } else {
                elixirValue.setTextColor(Color.WHITE);
            }
        }
        if (modeLabel != null) {
            modeLabel.setText(String.format(Locale.US, "OPPONENT • %.0f×%s",
                    multiplier, running ? "" : " • PAUSED"));
        }
        if (historyLabel != null) {
            if (history.isEmpty()) {
                historyLabel.setText("LAST: —");
            } else {
                StringBuilder text = new StringBuilder("LAST: ");
                boolean first = true;
                for (Integer cost : history) {
                    if (!first) {
                        text.append(" • ");
                    }
                    text.append(cost);
                    first = false;
                }
                historyLabel.setText(text.toString());
            }
        }
    }

    private void refreshSpeedButtons() {
        if (speedButtons == null) {
            return;
        }
        for (int i = 0; i < speedButtons.length; i++) {
            boolean selected = Math.abs(multiplier - (i + 1.0)) < 0.01;
            speedButtons[i].setBackground(panel(
                    selected ? Color.rgb(157, 74, 195) : Color.rgb(52, 42, 64),
                    11,
                    selected ? Color.rgb(225, 170, 255) : Color.rgb(78, 65, 91),
                    dp(1)));
            speedButtons[i].setTextColor(selected ? Color.WHITE : Color.rgb(205, 193, 216));
        }
    }

    private void installDragAndTap(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                            dragged = true;
                        }
                        if (dragged) {
                            params.x = startX + Math.round(dx);
                            params.y = startY + Math.round(dy);
                            clampToScreen();
                            windowManager.updateViewLayout(overlay, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            toggleCompact();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
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
        TextView button = label(text, 14, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(Color.rgb(52, 42, 64), 11,
                Color.rgb(85, 69, 98), dp(1)));
        return button;
    }

    private TextView costButton(String text) {
        TextView button = label(text, 19, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(Color.rgb(113, 50, 143), 13,
                Color.rgb(202, 118, 238), dp(1)));
        return button;
    }

    private TextView actionButton(String text, int color) {
        TextView button = label(text, 11, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(color, 11, Color.argb(70, 255, 255, 255), dp(1)));
        return button;
    }

    private LinearLayout.LayoutParams fixedButton(int width, int rightMargin) {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(width, dp(36));
        layoutParams.rightMargin = rightMargin;
        return layoutParams;
    }

    private LinearLayout.LayoutParams weighted(int rightMargin) {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        layoutParams.rightMargin = rightMargin;
        return layoutParams;
    }

    private GradientDrawable panel(int fill, int radiusDp, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT && strokeWidth > 0) {
            drawable.setStroke(strokeWidth, stroke);
        }
        return drawable;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Elixir Collector overlay",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the user-started floating elixir counter active");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending).build();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_notification_elixir)
                .setContentTitle("Elixir corner counter active")
                .setContentText("Tap the corner number to open controls")
                .setContentIntent(openPending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(stopAction)
                .build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
