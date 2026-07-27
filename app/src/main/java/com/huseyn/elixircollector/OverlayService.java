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
import android.widget.ImageView;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Integer> history = new ArrayDeque<>();

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View overlay;
    private LinearLayout expandedPanel;
    private TextView elixirValue;
    private TextView modeLabel;
    private TextView historyLabel;
    private TextView pauseButton;
    private TextView expandButton;
    private TextView[] speedButtons;

    private double elixir = 5.0;
    private double multiplier = 1.0;
    private boolean running = true;
    private boolean compact = false;
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
                // Already removed by the system.
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
                dp(326),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(10);
        params.y = dp(110);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(9), dp(8), dp(9), dp(9));
        root.setBackground(panel(Color.argb(246, 20, 13, 29), 20,
                Color.rgb(184, 103, 224), dp(2)));
        root.setElevation(dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        installDrag(header);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_elixir_collector);
        icon.setContentDescription("Elixir collector");
        header.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout numbers = new LinearLayout(this);
        numbers.setOrientation(LinearLayout.VERTICAL);
        numbers.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams numbersParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        numbersParams.leftMargin = dp(7);
        header.addView(numbers, numbersParams);

        elixirValue = label("5.0", 24, Color.WHITE, true);
        numbers.addView(elixirValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        modeLabel = label("OPPONENT • 1×", 10, Color.rgb(207, 172, 229), true);
        numbers.addView(modeLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));

        pauseButton = tinyButton("Ⅱ");
        pauseButton.setContentDescription("Pause regeneration");
        pauseButton.setOnClickListener(v -> togglePause());
        header.addView(pauseButton, square(dp(38), dp(5)));

        expandButton = tinyButton("−");
        expandButton.setContentDescription("Collapse controls");
        expandButton.setOnClickListener(v -> toggleCompact());
        header.addView(expandButton, square(dp(38), dp(5)));

        TextView close = tinyButton("×");
        close.setContentDescription("Close overlay");
        close.setOnClickListener(v -> stopSelf());
        header.addView(close, square(dp(38), 0));

        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(expandedPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        historyLabel = label("LAST CARDS: —", 11, Color.rgb(182, 168, 198), false);
        historyLabel.setGravity(Gravity.CENTER);
        historyLabel.setPadding(0, dp(4), 0, dp(5));
        expandedPanel.addView(historyLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        addSpeedRow();
        addCostGrid();
        addActionRow();

        TextView help = label("Tap the elixir cost whenever the opponent plays a card", 10,
                Color.rgb(150, 137, 164), false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, dp(6), 0, 0);
        expandedPanel.addView(help, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlay = root;
        try {
            windowManager.addView(overlay, params);
            refresh();
        } catch (RuntimeException error) {
            Toast.makeText(this, "Could not display overlay: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void addSpeedRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(6));
        expandedPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView label = label("REGEN", 11, Color.rgb(196, 176, 209), true);
        label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1f));

        speedButtons = new TextView[3];
        String[] texts = {"1×", "2×", "3×"};
        for (int i = 0; i < texts.length; i++) {
            final int selected = i;
            TextView button = tinyButton(texts[i]);
            button.setOnClickListener(v -> {
                multiplier = selected + 1.0;
                lastTick = System.nanoTime();
                refreshSpeedButtons();
                refresh();
            });
            speedButtons[i] = button;
            row.addView(button, square(dp(52), i == texts.length - 1 ? 0 : dp(5)));
        }
        refreshSpeedButtons();
    }

    private void addCostGrid() {
        int cost = 1;
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
            rowParams.bottomMargin = dp(5);
            expandedPanel.addView(row, rowParams);

            int buttonsThisRow = rowIndex == 0 ? 5 : 4;
            for (int column = 0; column < buttonsThisRow; column++) {
                final int cardCost = cost++;
                TextView button = costButton(String.valueOf(cardCost));
                button.setContentDescription("Opponent spent " + cardCost + " elixir");
                button.setOnClickListener(v -> spend(cardCost));
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
                if (column < buttonsThisRow - 1) {
                    buttonParams.rightMargin = dp(5);
                }
                row.addView(button, buttonParams);
            }
        }
    }

    private void addActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        expandedPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView undo = actionButton("UNDO", Color.rgb(58, 67, 88));
        undo.setOnClickListener(v -> undo());
        row.addView(undo, weighted(dp(5)));

        TextView plus = actionButton("+1", Color.rgb(43, 103, 77));
        plus.setOnClickListener(v -> {
            elixir = Math.min(10.0, elixir + 1.0);
            refresh();
        });
        row.addView(plus, weighted(dp(5)));

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

    private void toggleCompact() {
        compact = !compact;
        expandedPanel.setVisibility(compact ? View.GONE : View.VISIBLE);
        expandButton.setText(compact ? "+" : "−");
        params.width = compact ? dp(222) : dp(326);
        windowManager.updateViewLayout(overlay, params);
    }

    private void refresh() {
        if (elixirValue != null) {
            elixirValue.setText(String.format(Locale.US, "%.1f / 10", elixir));
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
                historyLabel.setText("LAST CARDS: —");
            } else {
                StringBuilder text = new StringBuilder("LAST CARDS: ");
                boolean first = true;
                for (Integer cost : history) {
                    if (!first) {
                        text.append("  •  ");
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
                    12,
                    selected ? Color.rgb(225, 170, 255) : Color.rgb(78, 65, 91),
                    dp(1)));
            speedButtons[i].setTextColor(selected ? Color.WHITE : Color.rgb(205, 193, 216));
        }
    }

    private void installDrag(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + Math.round(event.getRawX() - touchX);
                        params.y = Math.max(0, startY + Math.round(event.getRawY() - touchY));
                        windowManager.updateViewLayout(overlay, params);
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

    private TextView tinyButton(String text) {
        TextView button = label(text, 15, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(Color.rgb(52, 42, 64), 12,
                Color.rgb(85, 69, 98), dp(1)));
        return button;
    }

    private TextView costButton(String text) {
        TextView button = label(text, 20, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(Color.rgb(113, 50, 143), 14,
                Color.rgb(202, 118, 238), dp(1)));
        return button;
    }

    private TextView actionButton(String text, int color) {
        TextView button = label(text, 11, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(color, 12, Color.argb(70, 255, 255, 255), dp(1)));
        return button;
    }

    private LinearLayout.LayoutParams square(int width, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(38));
        params.rightMargin = rightMargin;
        return params;
    }

    private LinearLayout.LayoutParams weighted(int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.rightMargin = rightMargin;
        return params;
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
                .setContentTitle("Elixir Collector is active")
                .setContentText("Tap card costs in the floating counter")
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
