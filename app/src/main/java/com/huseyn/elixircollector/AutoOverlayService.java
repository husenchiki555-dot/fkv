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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Compact transparent renderer. It never computes or mutates game state. */
public final class AutoOverlayService extends Service {
    public static final String ACTION_STOP = "com.huseyn.elixircollector.V6_OVERLAY_STOP";
    private static final String CHANNEL = "royalevision_v6_overlay";
    private static final int NOTIFICATION_ID = 9602;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SnapshotStore store;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private LinearLayout root, expanded;
    private TextView opponentText, statusText, detailText, sensorText, lastText;
    private final SlotView[] slots = new SlotView[8];
    private boolean expandedVisible;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 180);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new SnapshotStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root == null) {
            showOverlay();
            handler.post(ticker);
        }
        return START_STICKY;
    }

    private void showOverlay() {
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(8), dp(390));
        params = new WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(4);
        params.y = dp(38);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(2), dp(2), dp(2), dp(2));
        root.setAlpha(0.84f);
        root.setBackground(panel(Color.argb(48, 10, 7, 15), 12,
                Color.argb(62, 190, 103, 232), 1));
        buildCompact();
        buildExpanded();
        try { windows.addView(root, params); }
        catch (RuntimeException error) { stopSelf(); }
    }

    private void buildCompact() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(41)));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(panel(Color.argb(104, 69, 31, 92), 9,
                Color.argb(104, 214, 143, 248), 1));
        row.addView(pill, new LinearLayout.LayoutParams(dp(58), dp(38)));
        opponentText = label("💧 ?", 14, Color.WHITE, true);
        opponentText.setGravity(Gravity.CENTER);
        pill.addView(opponentText, new LinearLayout.LayoutParams(-1, dp(25)));
        TextView auto = label("AUTO", 7, Color.rgb(230, 208, 241), true);
        auto.setGravity(Gravity.CENTER);
        pill.addView(auto, new LinearLayout.LayoutParams(-1, dp(10)));
        installDragAndTap(pill);

        for (int i = 0; i < 8; i++) {
            slots[i] = makeSlot();
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(37), 1f);
            p.leftMargin = dp(2);
            row.addView(slots[i].root, p);
        }
        statusText = label("SEARCHING FOR MATCH", 8, Color.rgb(228, 213, 236), true);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, dp(14)));
    }

    private void buildExpanded() {
        expanded = new LinearLayout(this);
        expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setPadding(dp(6), dp(5), dp(6), dp(6));
        expanded.setVisibility(View.GONE);
        expanded.setBackground(panel(Color.argb(102, 13, 9, 19), 10,
                Color.argb(55, 210, 146, 244), 1));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = dp(2);
        root.addView(expanded, ep);

        detailText = label("OPP RANGE 0.0–10.0", 10, Color.WHITE, true);
        detailText.setGravity(Gravity.CENTER);
        expanded.addView(detailText, new LinearLayout.LayoutParams(-1, dp(24)));
        sensorText = label("HUD • RAIL • HAND", 8, Color.rgb(196, 220, 238), false);
        sensorText.setGravity(Gravity.CENTER);
        expanded.addView(sensorText, new LinearLayout.LayoutParams(-1, dp(24)));
        lastText = label("LAST: —", 8, Color.rgb(205, 190, 214), false);
        lastText.setGravity(Gravity.CENTER);
        expanded.addView(lastText, new LinearLayout.LayoutParams(-1, dp(20)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(35));
        ap.topMargin = dp(3);
        expanded.addView(actions, ap);
        TextView collapse = button("COLLAPSE");
        collapse.setOnClickListener(v -> setExpanded(false));
        actions.addView(collapse, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView stop = button("STOP ALL");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, AutoCaptureService.class));
            stopSelf();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        sp.leftMargin = dp(4);
        actions.addView(stop, sp);
    }

    private SlotView makeSlot() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(panel(Color.argb(62, 29, 24, 35), 7,
                Color.argb(50, 120, 97, 136), 1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setAlpha(0.86f);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView unknown = label("?", 15, Color.argb(228, 229, 218, 234), true);
        unknown.setGravity(Gravity.CENTER);
        frame.addView(unknown, new FrameLayout.LayoutParams(-1, -1));
        TextView cycle = label("", 6, Color.WHITE, true);
        cycle.setGravity(Gravity.CENTER);
        cycle.setBackground(panel(Color.argb(138, 0, 0, 0), 4, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, dp(10), Gravity.BOTTOM);
        frame.addView(cycle, cp);
        return new SlotView(frame, image, unknown, cycle);
    }

    private void refresh() {
        SessionSnapshot snapshot = store.read();
        if (snapshot == null) {
            opponentText.setText("💧 ?");
            statusText.setText(store.captureActive() ? "STARTING CAPTURE" : "AUTO OFF");
            clearDeck();
            return;
        }
        boolean stale = System.currentTimeMillis() - snapshot.timeMs > 2300;
        OpponentStateTracker.Estimate estimate = snapshot.opponent;
        if (!snapshot.matchFound() || estimate == null || Double.isNaN(estimate.best)) {
            opponentText.setText("💧 ?");
        } else if (estimate.confidence >= 0.55) {
            opponentText.setText(String.format(Locale.US, "💧 %.1f", estimate.best));
        } else {
            opponentText.setText(String.format(Locale.US, "💧 ~%.1f", estimate.best));
        }
        statusText.setText(stale ? "CAPTURE STALLED" : snapshot.status);
        if (estimate != null) {
            String best = Double.isNaN(estimate.best) ? "?" : String.format(Locale.US, "%.1f", estimate.best);
            detailText.setText(String.format(Locale.US, "OPP %s • RANGE %.1f–%.1f • %d%%",
                    best, estimate.min, estimate.max, Math.round(estimate.confidence * 100.0)));
        }
        sensorText.setText(snapshot.matchState + " • HUD " + snapshot.hudState
                + " • RAIL " + snapshot.elixirState + " • HAND " + snapshot.recognizedHandSlots + "/4"
                + (snapshot.audioAvailable ? " • SOUND" : ""));
        lastText.setText(snapshot.lastEvent);
        applyDeck(snapshot.deck);
    }

    private void applyDeck(List<OpponentStateTracker.DeckSlot> deck) {
        for (int i = 0; i < slots.length; i++) {
            OpponentStateTracker.DeckSlot slot = deck != null && i < deck.size() ? deck.get(i) : null;
            SlotView view = slots[i];
            if (slot == null || !slot.known()) {
                view.image.setImageDrawable(null);
                view.unknown.setVisibility(View.VISIBLE);
                view.cycle.setText("");
            } else {
                CardIconLoader.setCard(view.image, slot.cardId);
                view.unknown.setVisibility(view.image.getDrawable() == null ? View.VISIBLE : View.GONE);
                view.cycle.setText(slot.cardsUntilReturn == 0 ? "IN" : "↻" + slot.cardsUntilReturn);
                view.cycle.setTextColor(slot.cardsUntilReturn == 0
                        ? Color.rgb(178, 255, 196) : Color.WHITE);
            }
        }
    }

    private void clearDeck() { applyDeck(null); }

    private void setExpanded(boolean value) {
        expandedVisible = value;
        if (expanded != null) expanded.setVisibility(value ? View.VISIBLE : View.GONE);
        if (windows != null && root != null) {
            try { windows.updateViewLayout(root, params); } catch (RuntimeException ignored) {}
        }
    }

    private void installDragAndTap(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float downX, downY; boolean drag;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x; startY = params.y;
                        downX = event.getRawX(); downY = event.getRawY(); drag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) drag = true;
                        if (drag) {
                            params.x = startX + Math.round(dx);
                            params.y = startY + Math.round(dy);
                            clampPosition();
                            try { windows.updateViewLayout(root, params); } catch (RuntimeException ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!drag) setExpanded(!expandedVisible);
                        return true;
                    default: return true;
                }
            }
        });
    }

    private void clampPosition() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        params.x = Math.max(0, Math.min(params.x, Math.max(0, width - params.width)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, height - dp(58))));
    }

    private Notification notification() {
        Intent open = new Intent(this, AutoMainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("RoyaleVision v6 overlay")
                .setContentText("Transparent opponent state overlay")
                .setSmallIcon(R.drawable.ic_notification_elixir)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "RoyaleVision overlay", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView button(String text) {
        TextView view = label(text, 9, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(panel(Color.argb(92, 57, 43, 70), 8,
                Color.argb(76, 155, 122, 177), 1));
        return view;
    }

    private GradientDrawable panel(int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT && width > 0) drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (windows != null && root != null) {
            try { windows.removeView(root); } catch (RuntimeException ignored) {}
        }
        root = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class SlotView {
        final FrameLayout root; final ImageView image; final TextView unknown, cycle;
        SlotView(FrameLayout root, ImageView image, TextView unknown, TextView cycle) {
            this.root = root; this.image = image; this.unknown = unknown; this.cycle = cycle;
        }
    }
}
