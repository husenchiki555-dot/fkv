package com.huseyn.elixircollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RoyaleVision Auto v5.1 floating overlay.
 *
 * Compact layout intentionally matches the original concept:
 *   [💧 7.7] [Hog][Knight][Log][?][?][?][?][?]
 * Unknown cards remain "?". Known cards show whether they are currently back
 * in hand/cycle (IN) or how many card commits remain before they return (↻N).
 */
public final class AutoOverlayService extends Service {
    public static final String ACTION_STOP = "com.huseyn.elixircollector.AUTO_OVERLAY_STOP";
    private static final String CHANNEL = "royalevision_auto_overlay";
    private static final int NOTIFICATION_ID = 9506;
    private static final String STATE_PREFS = "royalevision_auto_overlay_state_v51";
    private static final String K_STATE_JSON = "state_json";
    private static final long MAX_RESTORE_AGE_MS = 15L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AutoState state = new AutoState();
    private SharedPreferences cv;

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private LinearLayout root;
    private LinearLayout expanded;
    private LinearLayout picker;
    private LinearLayout cardGrid;
    private TextView opponentText;
    private TextView compactStatus;
    private TextView cvStatus;
    private TextView clockText;
    private TextView hintText;
    private TextView lastText;
    private TextView deckWarning;
    private final TextView[] compactDeckSlots = new TextView[8];
    private final TextView[] expandedDeckSlots = new TextView[8];
    private final ArrayList<TextView> filterButtons = new ArrayList<>();

    private boolean compact = true;
    private int filterCost = -1;
    private int handledSession;
    private long handledEnemyCandidate;
    private long handledLocalPlay;
    private long handledHandChange;
    private boolean previousMatchActive;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateAutomaticState();
            refresh();
            handler.postDelayed(this, 100L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        cv = getSharedPreferences(AutoCaptureService.PREFS, MODE_PRIVATE);
        createChannel();
        restoreState();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

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

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveState();
        if (wm != null && root != null) {
            try { wm.removeView(root); } catch (RuntimeException ignored) {}
        }
        root = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void updateAutomaticState() {
        boolean match = cv.getBoolean(AutoCaptureService.K_MATCH, false);
        int session = cv.getInt(AutoCaptureService.K_SESSION, 0);

        if (match && session > 0 && session != handledSession) {
            long clockStart = cv.getLong(AutoCaptureService.K_MATCH_CLOCK_START_MS, 0L);
            long anchorMs = cv.getLong(AutoCaptureService.K_MATCH_ANCHOR_MS, 0L);
            double anchorElixir = cv.getFloat(AutoCaptureService.K_MATCH_ANCHOR_ELIXIR, Float.NaN);
            if (clockStart > 0L && anchorMs > 0L
                    && !Double.isNaN(anchorElixir) && anchorElixir >= 0.5) {
                handledSession = session;
                state.start(clockStart, anchorMs, anchorElixir);
                handledEnemyCandidate = 0L;
                handledLocalPlay = 0L;
                handledHandChange = 0L;
                rebuildDeck();
                saveState();
                Toast.makeText(this,
                        "Battle detected • opponent Elixir anchored from stable HUD",
                        Toast.LENGTH_SHORT).show();
            }
        }

        // Once a real battle disappears for the capture service's full debounce
        // window, clear the visible deck. The next match starts at eight '?' slots.
        if (previousMatchActive && !match) {
            state.reset();
            rebuildDeck();
            saveState();
            if (picker != null) picker.setVisibility(View.GONE);
        }
        previousMatchActive = match;

        long hand = cv.getLong(AutoCaptureService.K_HAND_CHANGE_MS, 0L);
        long own = cv.getLong(AutoCaptureService.K_LOCAL_PLAY_MS, 0L);
        long enemy = cv.getLong(AutoCaptureService.K_ENEMY_CANDIDATE_MS, 0L);

        if (hand > handledHandChange && match) {
            handledHandChange = hand;
            if (hintText != null) {
                hintText.setText("LOCAL HAND CHANGED • enemy attribution suppressed");
            }
        }
        if (own > handledLocalPlay && match) {
            handledLocalPlay = own;
            if (hintText != null) {
                hintText.setText("✓ YOUR PLAY • hand changed + your Elixir dropped");
            }
        }
        if (enemy > handledEnemyCandidate && match && state.isStarted()) {
            handledEnemyCandidate = enemy;
            String hint = cv.getString(AutoCaptureService.K_HINT,
                    "DEPLOYMENT / ARENA CHANGE");
            if (hintText != null) hintText.setText("⚠ OPPONENT ACTION? • " + hint);
            // No trained identity model exists in this AI-less build, therefore
            // exact card identity is still confirmed with one tap instead of guessed.
            setCompact(false);
            showPicker();
        }
    }

    private void showOverlay() {
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                compactWidth(), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(4);
        params.y = dp(42);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.setBackground(panel(Color.argb(246, 18, 13, 26), 16,
                Color.rgb(173, 82, 232), 2));
        if (Build.VERSION.SDK_INT >= 21) root.setElevation(dp(12));

        buildCompactStrip();

        expanded = new LinearLayout(this);
        expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setVisibility(View.GONE);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = dp(3);
        root.addView(expanded, ep);

        buildHeader();
        buildAutoStatus();
        buildExpandedDeck();
        buildActions();
        buildPicker();

        try {
            wm.addView(root, params);
            rebuildDeck();
            refresh();
        } catch (RuntimeException e) {
            stopSelf();
        }
    }

    private void buildCompactStrip() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout elixirBox = new LinearLayout(this);
        elixirBox.setOrientation(LinearLayout.VERTICAL);
        elixirBox.setGravity(Gravity.CENTER);
        elixirBox.setPadding(dp(2), 0, dp(2), 0);
        elixirBox.setBackground(panel(Color.rgb(45, 29, 59), 10,
                Color.rgb(136, 74, 176), 1));
        row.addView(elixirBox, new LinearLayout.LayoutParams(dp(60), dp(40)));

        opponentText = label("💧 ?", 16, Color.WHITE, true);
        opponentText.setGravity(Gravity.CENTER);
        elixirBox.addView(opponentText, new LinearLayout.LayoutParams(-1, dp(25)));
        TextView handle = label("AUTO", 7, Color.rgb(213, 184, 230), true);
        handle.setGravity(Gravity.CENTER);
        elixirBox.addView(handle, new LinearLayout.LayoutParams(-1, dp(12)));
        installDragAndTap(elixirBox);

        for (int i = 0; i < compactDeckSlots.length; i++) {
            TextView slot = label("?", 8, Color.rgb(170, 157, 180), true);
            slot.setGravity(Gravity.CENTER);
            slot.setMaxLines(2);
            slot.setPadding(dp(1), 0, dp(1), 0);
            slot.setBackground(panel(Color.rgb(38, 31, 47), 8,
                    Color.rgb(72, 59, 83), 1));
            compactDeckSlots[i] = slot;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1f);
            p.leftMargin = dp(2);
            row.addView(slot, p);
        }

        compactStatus = label("SEARCHING FOR MATCH", 8,
                Color.rgb(211, 187, 227), true);
        compactStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(14));
        sp.topMargin = dp(1);
        root.addView(compactStatus, sp);
    }

    private void buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        expanded.addView(row, h(39));

        clockText = label("SEARCHING", 12, Color.WHITE, true);
        row.addView(clockText, new LinearLayout.LayoutParams(0, dp(35), 1f));

        TextView collapse = smallButton("−");
        collapse.setOnClickListener(v -> setCompact(true));
        row.addView(collapse, fixed(38, 4));

        TextView close = smallButton("×");
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, fixed(38, 0));
    }

    private void buildAutoStatus() {
        cvStatus = label("AUTO CV • waiting for capture", 9,
                Color.rgb(189, 228, 255), true);
        cvStatus.setGravity(Gravity.CENTER);
        expanded.addView(cvStatus, new LinearLayout.LayoutParams(-1, dp(26)));

        hintText = label("Hand change + local Elixir drop rejects your own play",
                9, Color.rgb(207, 192, 218), false);
        hintText.setGravity(Gravity.CENTER);
        hintText.setMaxLines(2);
        expanded.addView(hintText, new LinearLayout.LayoutParams(-1, dp(36)));
    }

    private void buildExpandedDeck() {
        TextView title = label("OPPONENT DECK / CYCLE", 10,
                Color.rgb(229, 195, 248), true);
        title.setGravity(Gravity.CENTER);
        expanded.addView(title, h(22));

        TextView legend = label("GREEN IN = back in hand • ↻N = N cards until return • ? = unseen",
                8, Color.rgb(181, 168, 192), false);
        legend.setGravity(Gravity.CENTER);
        expanded.addView(legend, h(24));

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rp = h(49);
            rp.bottomMargin = dp(3);
            expanded.addView(row, rp);
            for (int c = 0; c < 4; c++) {
                int index = r * 4 + c;
                TextView slot = label("?", 9, Color.rgb(190, 177, 200), true);
                slot.setGravity(Gravity.CENTER);
                slot.setMaxLines(2);
                slot.setBackground(panel(Color.rgb(40, 31, 50), 9,
                        Color.rgb(73, 59, 85), 1));
                expandedDeckSlots[index] = slot;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(45), 1f);
                if (c < 3) p.rightMargin = dp(3);
                row.addView(slot, p);
            }
        }

        deckWarning = label("", 9, Color.rgb(255, 155, 168), true);
        deckWarning.setGravity(Gravity.CENTER);
        deckWarning.setVisibility(View.GONE);
        expanded.addView(deckWarning, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = h(42);
        rp.topMargin = dp(4);
        expanded.addView(row, rp);

        TextView cards = actionButton("CARDS", Color.rgb(111, 55, 151));
        cards.setOnClickListener(v -> showPicker());
        row.addView(cards, weight(4));

        TextView undo = actionButton("UNDO", Color.rgb(54, 65, 84));
        undo.setOnClickListener(v -> {
            state.undo();
            saveState();
            rebuildDeck();
            refresh();
        });
        row.addView(undo, weight(4));

        TextView reset = actionButton("CLEAR", Color.rgb(83, 45, 99));
        reset.setOnClickListener(v -> {
            state.reset();
            handledSession = cv.getInt(AutoCaptureService.K_SESSION, 0);
            saveState();
            rebuildDeck();
            refresh();
        });
        row.addView(reset, weight(0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        expanded.addView(row2, h(40));
        double[] spends = {1, 2, 3};
        for (double amount : spends) {
            TextView b = actionButton("ABILITY −" + (int)amount,
                    Color.rgb(96, 47, 64));
            b.setOnClickListener(v -> {
                state.addSpend(amount, System.currentTimeMillis());
                saveState();
                refresh();
            });
            row2.addView(b, weight(3));
        }
        TextView gain = actionButton("+1", Color.rgb(40, 98, 72));
        gain.setOnClickListener(v -> {
            state.addGain(1, System.currentTimeMillis());
            saveState();
            refresh();
        });
        row2.addView(gain, weight(0));

        lastText = label("LAST: —", 9, Color.rgb(197, 183, 207), false);
        lastText.setGravity(Gravity.CENTER);
        expanded.addView(lastText, new LinearLayout.LayoutParams(-1, dp(24)));
    }

    private void buildPicker() {
        picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setVisibility(View.GONE);
        picker.setPadding(dp(3), dp(4), dp(3), dp(3));
        picker.setBackground(panel(Color.rgb(28, 22, 37), 12,
                Color.rgb(75, 61, 88), 1));
        expanded.addView(picker, new LinearLayout.LayoutParams(-1, -2));

        TextView title = label("OPPONENT ACTION • choose exact card", 9,
                Color.rgb(222, 204, 234), true);
        title.setGravity(Gravity.CENTER);
        picker.addView(title, h(26));

        LinearLayout filtersA = new LinearLayout(this);
        filtersA.setOrientation(LinearLayout.HORIZONTAL);
        picker.addView(filtersA, h(34));
        addFilter(filtersA, "ALL", -1);
        for (int c = 1; c <= 4; c++) addFilter(filtersA, String.valueOf(c), c);

        LinearLayout filtersB = new LinearLayout(this);
        filtersB.setOrientation(LinearLayout.HORIZONTAL);
        picker.addView(filtersB, h(34));
        for (int c = 5; c <= 9; c++) addFilter(filtersB, String.valueOf(c), c);
        addFilter(filtersB, "M", 0);

        ScrollView scroll = new ScrollView(this);
        picker.addView(scroll, new LinearLayout.LayoutParams(-1, dp(285)));
        cardGrid = new LinearLayout(this);
        cardGrid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cardGrid, new ScrollView.LayoutParams(-1, -2));
        rebuildCardGrid();
    }

    private void addFilter(LinearLayout row, String text, int value) {
        TextView b = smallButton(text);
        b.setTextSize(10);
        b.setTag(value);
        b.setOnClickListener(v -> {
            filterCost = (Integer)v.getTag();
            refreshFilters();
            rebuildCardGrid();
        });
        filterButtons.add(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(31), 1f);
        p.rightMargin = dp(2);
        row.addView(b, p);
    }

    private void refreshFilters() {
        for (TextView b : filterButtons) {
            boolean on = ((Integer)b.getTag()) == filterCost;
            b.setBackground(panel(on ? Color.rgb(139, 64, 188) : Color.rgb(47, 38, 57),
                    8, on ? Color.rgb(220, 160, 255) : Color.rgb(75, 62, 87), 1));
        }
    }

    private void rebuildCardGrid() {
        if (cardGrid == null) return;
        cardGrid.removeAllViews();
        List<CardCatalog.Card> list = new ArrayList<>();
        if (filterCost == -1) list.addAll(CardCatalog.ALL);
        else if (filterCost == 0) {
            CardCatalog.Card m = CardCatalog.mirror();
            if (m != null) list.add(m);
        } else list.addAll(CardCatalog.choicesForCost(filterCost));

        for (int i = 0; i < list.size(); i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rp = h(51);
            rp.bottomMargin = dp(3);
            cardGrid.addView(row, rp);
            for (int c = 0; c < 3; c++) {
                if (i + c >= list.size()) {
                    row.addView(new TextView(this), new LinearLayout.LayoutParams(0, dp(48), 1f));
                    continue;
                }
                CardCatalog.Card card = list.get(i + c);
                TextView b = label(CardCatalog.shortName(card.displayName) + "\n"
                                + (card.mirror ? "M" : card.cost),
                        8, Color.WHITE, true);
                b.setGravity(Gravity.CENTER);
                b.setMaxLines(2);
                b.setBackground(panel(Color.rgb(55, 42, 67), 9,
                        Color.rgb(87, 68, 102), 1));
                b.setOnClickListener(v -> registerCard(card));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(48), 1f);
                if (c < 2) bp.rightMargin = dp(3);
                row.addView(b, bp);
            }
        }
        refreshFilters();
    }

    private void registerCard(CardCatalog.Card card) {
        if (!state.isStarted()) {
            Toast.makeText(this, "Waiting for automatic match detection",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        AutoState.Event e = state.addCard(card, System.currentTimeMillis());
        if (e == null) {
            Toast.makeText(this, "Mirror needs a previous opponent card",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        picker.setVisibility(View.GONE);
        saveState();
        rebuildDeck();
        refresh();
        Toast.makeText(this, e.name + " registered", Toast.LENGTH_SHORT).show();
    }

    private void showPicker() {
        if (picker == null) return;
        picker.setVisibility(View.VISIBLE);
        filterCost = -1;
        rebuildCardGrid();
        setCompact(false);
    }

    private void rebuildDeck() {
        List<AutoState.DeckStatus> deck = state.getDeck();
        for (int i = 0; i < 8; i++) {
            AutoState.DeckStatus s = i < deck.size() ? deck.get(i) : null;
            applyDeckSlot(compactDeckSlots[i], s, true);
            applyDeckSlot(expandedDeckSlots[i], s, false);
        }
        if (deckWarning != null) {
            boolean conflict = state.hasDeckConflict();
            deckWarning.setVisibility(conflict ? View.VISIBLE : View.GONE);
            if (conflict) {
                deckWarning.setText("⚠ More than 8 unique opponent cards recorded — undo/correct the event stream");
            }
        }
    }

    private void applyDeckSlot(TextView slot, AutoState.DeckStatus s, boolean tiny) {
        if (slot == null) return;
        if (s == null) {
            slot.setText("?");
            slot.setTextColor(Color.rgb(164, 151, 175));
            slot.setBackground(panel(Color.rgb(38, 31, 47), tiny ? 8 : 9,
                    Color.rgb(72, 59, 83), 1));
            return;
        }
        String name = compactName(s.name, tiny);
        if (s.isInHandOrAvailable()) {
            slot.setText(name + "\nIN");
            slot.setTextColor(Color.rgb(187, 255, 205));
            slot.setBackground(panel(Color.rgb(29, 57, 42), tiny ? 8 : 9,
                    Color.rgb(84, 177, 108), 1));
        } else {
            slot.setText(name + "\n↻" + s.cardsUntilReturn);
            slot.setTextColor(Color.rgb(227, 212, 237));
            slot.setBackground(panel(Color.rgb(48, 37, 59), tiny ? 8 : 9,
                    Color.rgb(105, 76, 125), 1));
        }
    }

    private String compactName(String name, boolean tiny) {
        String shortName = CardCatalog.shortName(name);
        if (!tiny) return shortName;
        if (shortName.length() <= 5) return shortName;
        String[] p = name == null ? new String[0] : name.split(" ");
        if (p.length >= 2) {
            StringBuilder sb = new StringBuilder();
            for (String x : p) if (x.length() > 0) sb.append(Character.toUpperCase(x.charAt(0)));
            if (sb.length() >= 2 && sb.length() <= 4) return sb.toString();
        }
        return shortName.substring(0, Math.min(4, shortName.length()));
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        boolean capture = cv.getBoolean(AutoCaptureService.K_CAPTURE, false);
        boolean match = cv.getBoolean(AutoCaptureService.K_MATCH, false);
        double local = cv.getFloat(AutoCaptureService.K_LOCAL_ELIXIR, Float.NaN);
        double conf = cv.getFloat(AutoCaptureService.K_ELIXIR_CONF, 0f);
        String status = cv.getString(AutoCaptureService.K_STATUS, "AUTO STOPPED");

        if (opponentText != null) {
            double enemy = state.getOpponentElixir(now);
            if (!match || Double.isNaN(enemy)) opponentText.setText("💧 ?");
            else if (conf < 0.55) opponentText.setText(String.format(Locale.US, "💧 ≈%.1f", enemy));
            else opponentText.setText(String.format(Locale.US, "💧 %.1f", enemy));
        }

        if (compactStatus != null) {
            if (!capture) compactStatus.setText("AUTO OFF");
            else if (!match) compactStatus.setText(status);
            else {
                String you = Double.isNaN(local) ? "YOU ?" : String.format(Locale.US, "YOU %.1f", local);
                compactStatus.setText(String.format(Locale.US, "%s • %.0f× • %s",
                        you, state.getMultiplier(now), state.getClock(now)));
            }
        }

        if (clockText != null) clockText.setText(state.getClock(now));
        if (cvStatus != null) {
            cvStatus.setText(status + " • Elixir confidence " + Math.round(conf * 100) + "%");
            cvStatus.setTextColor(match ? Color.rgb(178, 244, 198) : Color.rgb(210, 193, 221));
        }
        if (lastText != null) lastText.setText(state.getLast());
    }

    private void setCompact(boolean value) {
        compact = value;
        if (expanded != null) expanded.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (params != null) {
            params.width = compactWidth();
            clamp();
            if (wm != null && root != null) {
                try { wm.updateViewLayout(root, params); } catch (RuntimeException ignored) {}
            }
        }
    }

    private int compactWidth() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(300), Math.min(dp(390), sw - dp(8)));
    }

    private void clamp() {
        if (params == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        params.x = Math.max(0, Math.min(params.x, Math.max(0, sw - params.width)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, sh - dp(60))));
    }

    private void installDragAndTap(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean dragged;

            @Override public boolean onTouch(View v, MotionEvent event) {
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
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) dragged = true;
                        if (dragged) {
                            params.x = startX + Math.round(dx);
                            params.y = startY + Math.round(dy);
                            clamp();
                            try { wm.updateViewLayout(root, params); } catch (RuntimeException ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) setCompact(!compact);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void saveState() {
        try {
            JSONObject root = new JSONObject();
            root.put("saved", System.currentTimeMillis());
            root.put("handledSession", handledSession);
            root.put("clockStart", state.getMatchStartMs());
            root.put("anchorMs", state.getElixirAnchorMs());
            root.put("initial", state.getInitialOpponentElixir());
            JSONArray arr = new JSONArray();
            for (AutoState.Event e : state.getEvents()) {
                JSONObject j = new JSONObject();
                j.put("t", e.timeMs);
                j.put("kind", e.kind);
                j.put("deck", e.deckId == null ? JSONObject.NULL : e.deckId);
                j.put("name", e.name == null ? "" : e.name);
                j.put("delta", e.delta);
                j.put("cycle", e.cycleAdvance);
                arr.put(j);
            }
            root.put("events", arr);
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                    .putString(K_STATE_JSON, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void restoreState() {
        SharedPreferences p = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        String raw = p.getString(K_STATE_JSON, null);
        if (raw == null || raw.length() == 0) return;
        try {
            JSONObject root = new JSONObject(raw);
            long saved = root.optLong("saved", 0L);
            long now = System.currentTimeMillis();
            if (saved <= 0L || now - saved > MAX_RESTORE_AGE_MS) {
                p.edit().remove(K_STATE_JSON).apply();
                return;
            }
            long clockStart = root.optLong("clockStart", 0L);
            long anchorMs = root.optLong("anchorMs", 0L);
            double initial = root.optDouble("initial", Double.NaN);
            handledSession = root.optInt("handledSession", 0);
            if (clockStart <= 0L || anchorMs <= 0L || Double.isNaN(initial)) return;

            ArrayList<AutoState.Event> restored = new ArrayList<>();
            JSONArray arr = root.optJSONArray("events");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.optJSONObject(i);
                    if (j == null) continue;
                    String deck = j.isNull("deck") ? null : j.optString("deck", null);
                    restored.add(new AutoState.Event(
                            j.optLong("t", anchorMs),
                            j.optString("kind", "UNKNOWN"),
                            deck,
                            j.optString("name", ""),
                            j.optDouble("delta", 0.0),
                            j.optBoolean("cycle", false)));
                }
            }
            state.restore(clockStart, anchorMs, initial, restored);
        } catch (Exception ignored) {
            p.edit().remove(K_STATE_JSON).apply();
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, AutoMainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AutoOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("RoyaleVision Auto v5.1")
                .setContentText("Opponent Elixir + 8-slot deck/cycle overlay")
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
                "RoyaleVision automatic overlay", NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView smallButton(String text) {
        TextView v = label(text, 11, Color.rgb(220, 207, 229), true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(panel(Color.rgb(49, 40, 59), 9,
                Color.rgb(78, 65, 91), 1));
        return v;
    }

    private TextView actionButton(String text, int fill) {
        TextView v = label(text, 9, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(panel(fill, 9, Color.argb(85, 255, 255, 255), 1));
        return v;
    }

    private GradientDrawable panel(int fill, int radiusDp, int stroke, int strokeWidthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT && strokeWidthDp > 0) {
            d.setStroke(dp(strokeWidthDp), stroke);
        }
        return d;
    }

    private LinearLayout.LayoutParams h(int heightDp) {
        return new LinearLayout.LayoutParams(-1, dp(heightDp));
    }

    private LinearLayout.LayoutParams fixed(int widthDp, int rightMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(widthDp), dp(34));
        p.rightMargin = dp(rightMarginDp);
        return p;
    }

    private LinearLayout.LayoutParams weight(int rightMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p.rightMargin = dp(rightMarginDp);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
