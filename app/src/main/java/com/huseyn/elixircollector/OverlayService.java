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

public class OverlayService extends Service {
    private static final String CHANNEL_ID = "royalevision_manual_overlay";
    private static final int NOTIFICATION_ID = 9404;
    private static final String ACTION_STOP = "com.huseyn.elixircollector.STOP_OVERLAY";
    private static final String PREFS = "royalevision_manual_state";
    private static final String PREF_STATE = "state_json";
    private static final long STALE_MATCH_MS = 15L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TrackerState state = new TrackerState();

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private LinearLayout expandedPanel;
    private LinearLayout cardPickerPanel;
    private LinearLayout resourcePanel;
    private LinearLayout cardGrid;
    private TextView elixirValue;
    private TextView clockLabel;
    private TextView phaseLabel;
    private TextView lastLabel;
    private TextView nextLabel;
    private TextView deckWarning;
    private TextView startResetButton;
    private TextView modeButton;
    private final TextView[] deckSlots = new TextView[8];
    private final ArrayList<TextView> costFilterButtons = new ArrayList<>();
    private int activeCostFilter = 4;
    private boolean compact = true;
    private boolean resourceSpendMode = true;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshLive();
            handler.postDelayed(this, 100L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        restoreState();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
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

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (RuntimeException ignored) {}
        }
        overlay = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void showOverlay() {
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                dp(116), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int sw = getResources().getDisplayMetrics().widthPixels;
        params.x = Math.max(dp(6), sw - dp(122));
        params.y = dp(72);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(5), dp(5), dp(5), dp(5));
        overlay.setBackground(panel(Color.argb(244, 19, 14, 27), 18,
                Color.rgb(174, 89, 232), dp(2)));
        if (Build.VERSION.SDK_INT >= 21) overlay.setElevation(dp(12));

        LinearLayout compactBar = new LinearLayout(this);
        compactBar.setOrientation(LinearLayout.VERTICAL);
        compactBar.setGravity(Gravity.CENTER);
        overlay.addView(compactBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        elixirValue = label("💧 5.0", 20, Color.WHITE, true);
        elixirValue.setGravity(Gravity.CENTER);
        compactBar.addView(elixirValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(31)));

        phaseLabel = label("READY", 9, Color.rgb(211, 184, 229), true);
        phaseLabel.setGravity(Gravity.CENTER);
        compactBar.addView(phaseLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        installDragAndTap(compactBar);

        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams exp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        exp.topMargin = dp(3);
        overlay.addView(expandedPanel, exp);

        buildHeader();
        buildDeckSection();
        buildPrimaryActions();
        buildSyncRow();
        buildCardPicker();
        buildResourcePanel();

        lastLabel = label("LAST: —", 10, Color.rgb(195, 180, 207), false);
        lastLabel.setGravity(Gravity.CENTER);
        lastLabel.setPadding(dp(3), dp(5), dp(3), dp(1));
        expandedPanel.addView(lastLabel, matchWrap());

        nextLabel = label("NEXT: —", 10, Color.rgb(212, 191, 226), true);
        nextLabel.setGravity(Gravity.CENTER);
        nextLabel.setPadding(dp(3), dp(2), dp(3), dp(4));
        expandedPanel.addView(nextLabel, matchWrap());

        try {
            windowManager.addView(overlay, params);
            rebuildDeck();
            refreshLive();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not display overlay: " + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        expandedPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(41)));

        clockLabel = label("READY", 12, Color.WHITE, true);
        clockLabel.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(clockLabel, new LinearLayout.LayoutParams(0, dp(38), 1f));

        modeButton = smallButton("MODE AUTO");
        modeButton.setTextSize(9);
        modeButton.setOnClickListener(v -> {
            state.cycleMode();
            saveState();
            refreshLive();
        });
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(dp(78), dp(34));
        modeParams.rightMargin = dp(4);
        row.addView(modeButton, modeParams);

        TextView collapse = smallButton("−");
        collapse.setOnClickListener(v -> setCompact(true));
        row.addView(collapse, fixed(dp(38), dp(4)));

        TextView close = smallButton("×");
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, fixed(dp(38), 0));
    }

    private void buildDeckSection() {
        TextView title = label("OPPONENT DECK / CYCLE", 10, Color.rgb(226, 192, 246), true);
        title.setGravity(Gravity.CENTER);
        expandedPanel.addView(title, h(dp(23)));

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rp = h(dp(48));
            rp.bottomMargin = dp(3);
            expandedPanel.addView(row, rp);
            for (int c = 0; c < 4; c++) {
                int idx = r * 4 + c;
                TextView slot = label("?", 9, Color.rgb(221, 214, 228), true);
                slot.setGravity(Gravity.CENTER);
                slot.setMaxLines(2);
                slot.setBackground(panel(Color.rgb(40, 32, 51), 10,
                        Color.rgb(75, 62, 90), dp(1)));
                deckSlots[idx] = slot;
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(45), 1f);
                if (c < 3) sp.rightMargin = dp(3);
                row.addView(slot, sp);
            }
        }

        deckWarning = label("", 9, Color.rgb(255, 158, 169), true);
        deckWarning.setGravity(Gravity.CENTER);
        deckWarning.setVisibility(View.GONE);
        expandedPanel.addView(deckWarning, matchWrap());
    }

    private void buildPrimaryActions() {
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = h(dp(43));
        rp.topMargin = dp(4);
        expandedPanel.addView(row1, rp);

        TextView cards = actionButton("CARDS", Color.rgb(112, 57, 153));
        cards.setOnClickListener(v -> toggleSubPanel(cardPickerPanel));
        row1.addView(cards, weight(dp(4)));

        TextView spend = actionButton("SPEND", Color.rgb(111, 53, 72));
        spend.setOnClickListener(v -> {
            showResourcePanel(true);
            toggleSubPanel(resourcePanel);
        });
        row1.addView(spend, weight(dp(4)));

        TextView gain = actionButton("GAIN", Color.rgb(41, 102, 75));
        gain.setOnClickListener(v -> {
            showResourcePanel(false);
            toggleSubPanel(resourcePanel);
        });
        row1.addView(gain, weight(0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        expandedPanel.addView(row2, h(dp(43)));

        TextView undo = actionButton("UNDO", Color.rgb(55, 66, 85));
        undo.setOnClickListener(v -> {
            TrackerState.Event removed = state.undoLast();
            if (removed == null) Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            saveState();
            rebuildDeck();
            refreshLive();
        });
        row2.addView(undo, weight(dp(4)));

        startResetButton = actionButton("START MATCH", Color.rgb(55, 98, 157));
        startResetButton.setOnClickListener(v -> {
            if (!state.isStarted()) {
                state.startMatch(System.currentTimeMillis());
                Toast.makeText(this, "Match clock started", Toast.LENGTH_SHORT).show();
            } else {
                state.resetToReady();
                Toast.makeText(this, "Tracker reset to 5 Elixir", Toast.LENGTH_SHORT).show();
            }
            saveState();
            hideSubPanels();
            rebuildDeck();
            refreshLive();
        });
        row2.addView(startResetButton, weight(0));
    }

    private void buildSyncRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = h(dp(39));
        rp.topMargin = dp(2);
        expandedPanel.addView(row, rp);

        TextView title = label("SYNC TIMER", 9, Color.rgb(176, 160, 188), true);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1f));

        int[] amounts = {-5, -1, 1, 5};
        for (int amount : amounts) {
            String text = amount > 0 ? "+" + amount : String.valueOf(amount);
            TextView b = smallButton(text);
            b.setTextSize(10);
            b.setOnClickListener(v -> {
                state.nudgeElapsedSeconds(amount);
                saveState();
                refreshLive();
            });
            row.addView(b, fixed(dp(42), dp(3)));
        }
    }

    private void buildCardPicker() {
        cardPickerPanel = new LinearLayout(this);
        cardPickerPanel.setOrientation(LinearLayout.VERTICAL);
        cardPickerPanel.setVisibility(View.GONE);
        cardPickerPanel.setPadding(dp(3), dp(5), dp(3), dp(3));
        cardPickerPanel.setBackground(panel(Color.rgb(28, 22, 37), 13,
                Color.rgb(76, 62, 90), dp(1)));
        LinearLayout.LayoutParams pp = matchWrap();
        pp.topMargin = dp(4);
        expandedPanel.addView(cardPickerPanel, pp);

        TextView hint = label("Tap the card the opponent actually committed", 9,
                Color.rgb(201, 186, 211), false);
        hint.setGravity(Gravity.CENTER);
        cardPickerPanel.addView(hint, h(dp(25)));

        LinearLayout filter1 = new LinearLayout(this);
        filter1.setOrientation(LinearLayout.HORIZONTAL);
        cardPickerPanel.addView(filter1, h(dp(37)));
        for (int cost = 1; cost <= 5; cost++) addCostFilter(filter1, cost, false);

        LinearLayout filter2 = new LinearLayout(this);
        filter2.setOrientation(LinearLayout.HORIZONTAL);
        cardPickerPanel.addView(filter2, h(dp(37)));
        for (int cost = 6; cost <= 9; cost++) addCostFilter(filter2, cost, false);
        addCostFilter(filter2, 0, true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        cardPickerPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(294)));

        cardGrid = new LinearLayout(this);
        cardGrid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cardGrid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        rebuildCardGrid();
    }

    private void addCostFilter(LinearLayout row, int cost, boolean mirror) {
        String text = mirror ? "M" : String.valueOf(cost);
        TextView b = smallButton(text);
        b.setTextSize(11);
        b.setTag(mirror ? 0 : cost);
        b.setOnClickListener(v -> {
            activeCostFilter = (Integer)v.getTag();
            refreshCostFilters();
            rebuildCardGrid();
        });
        costFilterButtons.add(b);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(33), 1f);
        bp.rightMargin = dp(3);
        row.addView(b, bp);
        refreshCostFilters();
    }

    private void refreshCostFilters() {
        for (TextView b : costFilterButtons) {
            Object tag = b.getTag();
            if (!(tag instanceof Integer)) continue;
            boolean selected = ((Integer)tag) == activeCostFilter;
            b.setBackground(panel(selected ? Color.rgb(137, 64, 187) : Color.rgb(47, 38, 57),
                    9, selected ? Color.rgb(218, 157, 255) : Color.rgb(75, 62, 87), dp(1)));
            b.setTextColor(selected ? Color.WHITE : Color.rgb(201, 188, 210));
        }
    }

    private void rebuildCardGrid() {
        if (cardGrid == null) return;
        cardGrid.removeAllViews();
        List<CardCatalog.Card> list;
        if (activeCostFilter == 0) {
            list = new ArrayList<>();
            CardCatalog.Card mirror = CardCatalog.mirror();
            if (mirror != null) list.add(mirror);
        } else {
            list = CardCatalog.choicesForCost(activeCostFilter);
        }

        for (int i = 0; i < list.size(); i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rp = h(dp(53));
            rp.bottomMargin = dp(3);
            cardGrid.addView(row, rp);
            for (int c = 0; c < 3; c++) {
                int idx = i + c;
                if (idx >= list.size()) {
                    TextView spacer = new TextView(this);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, dp(50), 1f));
                    continue;
                }
                CardCatalog.Card card = list.get(idx);
                String costText = card.mirror ? "M" : String.valueOf(card.cost);
                TextView b = label(CardCatalog.shortName(card.displayName) + "\n" + costText,
                        9, Color.WHITE, true);
                b.setGravity(Gravity.CENTER);
                b.setMaxLines(2);
                b.setBackground(panel(Color.rgb(56, 42, 68), 10,
                        Color.rgb(89, 69, 105), dp(1)));
                b.setOnClickListener(v -> registerCard(card));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(50), 1f);
                if (c < 2) bp.rightMargin = dp(3);
                row.addView(b, bp);
            }
        }
    }

    private void registerCard(CardCatalog.Card card) {
        if (card.mirror && state.getCycleCommitCount() == 0) {
            Toast.makeText(this, "Mirror needs a previous opponent card", Toast.LENGTH_SHORT).show();
            return;
        }
        TrackerState.Event event = state.addCard(card, System.currentTimeMillis());
        saveState();
        hideSubPanels();
        rebuildDeck();
        refreshLive();
        double cost = -event.deltaElixir;
        Toast.makeText(this, event.displayName + " • " + format(cost) + " Elixir",
                Toast.LENGTH_SHORT).show();
    }

    private void buildResourcePanel() {
        resourcePanel = new LinearLayout(this);
        resourcePanel.setOrientation(LinearLayout.VERTICAL);
        resourcePanel.setVisibility(View.GONE);
        resourcePanel.setPadding(dp(4), dp(5), dp(4), dp(5));
        resourcePanel.setBackground(panel(Color.rgb(28, 22, 37), 13,
                Color.rgb(76, 62, 90), dp(1)));
        LinearLayout.LayoutParams rp = matchWrap();
        rp.topMargin = dp(4);
        expandedPanel.addView(resourcePanel, rp);
        showResourcePanel(true);
    }

    private void showResourcePanel(boolean spend) {
        resourceSpendMode = spend;
        if (resourcePanel == null) return;
        resourcePanel.removeAllViews();
        TextView title = label(spend
                        ? "NON-CARD ELIXIR SPEND • abilities / corrections"
                        : "ELIXIR GAIN • Collector / Elixir Golem / correction",
                9, Color.rgb(211, 193, 223), true);
        title.setGravity(Gravity.CENTER);
        resourcePanel.addView(title, h(dp(28)));

        double[] amounts = spend
                ? new double[]{0.5, 1, 2, 3, 4, 5, 6}
                : new double[]{0.5, 1, 2, 3, 4};
        int pos = 0;
        while (pos < amounts.length) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowp = h(dp(44));
            rowp.bottomMargin = dp(3);
            resourcePanel.addView(row, rowp);
            for (int c = 0; c < 4; c++) {
                if (pos >= amounts.length) {
                    row.addView(new TextView(this), new LinearLayout.LayoutParams(0, dp(41), 1f));
                    continue;
                }
                double amount = amounts[pos++];
                TextView b = actionButton((spend ? "−" : "+") + format(amount),
                        spend ? Color.rgb(100, 48, 64) : Color.rgb(39, 96, 70));
                b.setOnClickListener(v -> {
                    if (resourceSpendMode) state.addSpend(amount, System.currentTimeMillis());
                    else state.addGain(amount, System.currentTimeMillis());
                    saveState();
                    hideSubPanels();
                    refreshLive();
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(41), 1f);
                if (c < 3) bp.rightMargin = dp(3);
                row.addView(b, bp);
            }
        }
    }

    private void toggleSubPanel(View panelToShow) {
        boolean showing = panelToShow.getVisibility() == View.VISIBLE;
        hideSubPanels();
        if (!showing) panelToShow.setVisibility(View.VISIBLE);
        resizeForContent();
    }

    private void hideSubPanels() {
        if (cardPickerPanel != null) cardPickerPanel.setVisibility(View.GONE);
        if (resourcePanel != null) resourcePanel.setVisibility(View.GONE);
        resizeForContent();
    }

    private void rebuildDeck() {
        List<TrackerState.DeckCardStatus> deck = state.getDeckStatuses();
        for (int i = 0; i < deckSlots.length; i++) {
            TextView slot = deckSlots[i];
            if (slot == null) continue;
            if (i >= deck.size()) {
                slot.setText("?");
                slot.setTextColor(Color.rgb(159, 145, 170));
                continue;
            }
            TrackerState.DeckCardStatus s = deck.get(i);
            String status = s.cardsUntilReturn == 0 ? "✓" : "↻" + s.cardsUntilReturn;
            slot.setText(CardCatalog.shortName(s.name) + "\n" + status);
            slot.setTextColor(s.cardsUntilReturn == 0
                    ? Color.rgb(181, 255, 200)
                    : Color.rgb(231, 218, 239));
        }
        if (deckWarning != null) {
            boolean conflict = state.hasDeckConflict();
            deckWarning.setVisibility(conflict ? View.VISIBLE : View.GONE);
            if (conflict) deckWarning.setText("⚠ " + deck.size() + " unique cards seen — undo mistake or use special-mode logic");
        }
    }

    private void refreshLive() {
        long now = System.currentTimeMillis();
        double elixir = state.getElixir(now);
        if (elixirValue != null) {
            elixirValue.setText(String.format(Locale.US, "💧 %.1f", elixir));
            if (elixir < 2.0) elixirValue.setTextColor(Color.rgb(255, 132, 148));
            else if (elixir >= 9.5) elixirValue.setTextColor(Color.rgb(238, 181, 255));
            else elixirValue.setTextColor(Color.WHITE);
        }
        if (phaseLabel != null) {
            if (!state.isStarted()) phaseLabel.setText("READY • tap to expand");
            else phaseLabel.setText(String.format(Locale.US, "%.0f× • %s",
                    state.getCurrentMultiplier(now), state.getClockLabel(now)));
        }
        if (clockLabel != null) clockLabel.setText(state.getClockLabel(now));
        if (modeButton != null) modeButton.setText("MODE " + state.getMode().label);
        if (startResetButton != null) startResetButton.setText(state.isStarted() ? "RESET" : "START MATCH");
        if (lastLabel != null) lastLabel.setText(state.getLastEventSummary());
        if (nextLabel != null) nextLabel.setText(state.getNextReturnSummary());
    }

    private void setCompact(boolean makeCompact) {
        compact = makeCompact;
        hideSubPanels();
        expandedPanel.setVisibility(compact ? View.GONE : View.VISIBLE);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        params.width = compact ? dp(116) : Math.min(dp(372), screenWidth - dp(12));
        clampToScreen();
        windowManager.updateViewLayout(overlay, params);
        if (!compact) {
            rebuildDeck();
            refreshLive();
        }
    }

    private void toggleCompact() { setCompact(!compact); }

    private void resizeForContent() {
        if (windowManager == null || overlay == null || params == null) return;
        clampToScreen();
        try { windowManager.updateViewLayout(overlay, params); } catch (RuntimeException ignored) {}
    }

    private void clampToScreen() {
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
                        startX = params.x; startY = params.y;
                        downX = event.getRawX(); downY = event.getRawY();
                        dragged = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) dragged = true;
                        if (dragged) {
                            params.x = startX + Math.round(dx);
                            params.y = startY + Math.round(dy);
                            clampToScreen();
                            windowManager.updateViewLayout(overlay, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) toggleCompact();
                        return true;
                    case MotionEvent.ACTION_CANCEL: return true;
                    default: return false;
                }
            }
        });
    }

    private void saveState() {
        try {
            JSONObject root = new JSONObject();
            root.put("start", state.getMatchStartWallMs());
            root.put("mode", state.getMode().name());
            JSONArray arr = new JSONArray();
            for (TrackerState.Event e : state.getEvents()) {
                JSONObject j = new JSONObject();
                j.put("t", e.wallMs);
                j.put("kind", e.kind);
                j.put("deck", e.deckId == null ? JSONObject.NULL : e.deckId);
                j.put("name", e.displayName == null ? "" : e.displayName);
                j.put("delta", e.deltaElixir);
                j.put("cycle", e.cycleAdvance);
                arr.put(j);
            }
            root.put("events", arr);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_STATE, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(PREF_STATE, null);
        if (raw == null || raw.length() == 0) return;
        try {
            JSONObject root = new JSONObject(raw);
            long start = root.optLong("start", 0L);
            long now = System.currentTimeMillis();
            if (start > 0 && (now - start < 0 || now - start > STALE_MATCH_MS)) {
                prefs.edit().remove(PREF_STATE).apply();
                return;
            }
            TrackerState.Mode mode;
            try { mode = TrackerState.Mode.valueOf(root.optString("mode", "STANDARD_AUTO")); }
            catch (Exception e) { mode = TrackerState.Mode.STANDARD_AUTO; }
            ArrayList<TrackerState.Event> restored = new ArrayList<>();
            JSONArray arr = root.optJSONArray("events");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.optJSONObject(i);
                    if (j == null) continue;
                    String deck = j.isNull("deck") ? null : j.optString("deck", null);
                    restored.add(new TrackerState.Event(
                            j.optLong("t", start),
                            j.optString("kind", "UNKNOWN"),
                            deck,
                            j.optString("name", ""),
                            j.optDouble("delta", 0.0),
                            j.optBoolean("cycle", false)));
                }
            }
            state.restore(start, mode, restored);
        } catch (Exception ignored) {
            prefs.edit().remove(PREF_STATE).apply();
        }
    }

    private Notification createNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, OverlayService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("RoyaleVision Manual")
                .setContentText("Manual opponent elixir + deck/cycle overlay is active")
                .setSmallIcon(R.drawable.ic_notification_elixir)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build());
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "RoyaleVision overlay", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the manual floating tracker running");
        channel.setShowBadge(false);
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(sizeSp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView smallButton(String text) {
        TextView v = label(text, 12, Color.rgb(220, 207, 229), true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(panel(Color.rgb(49, 40, 59), 10,
                Color.rgb(78, 65, 91), dp(1)));
        return v;
    }

    private TextView actionButton(String text, int fill) {
        TextView v = label(text, 10, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(panel(fill, 10, Color.argb(85, 255, 255, 255), dp(1)));
        return v;
    }

    private GradientDrawable panel(int fill, int radiusDp, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT && strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    private LinearLayout.LayoutParams h(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fixed(int width, int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(34));
        p.rightMargin = rightMargin; return p;
    }

    private LinearLayout.LayoutParams weight(int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1f);
        p.rightMargin = rightMargin; return p;
    }

    private String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.valueOf((int)Math.rint(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
