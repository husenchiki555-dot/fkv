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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Floating overlay for RoyaleVision Auto v5.
 * Exact opponent-card identity remains manual in this AI-less build; however the app
 * automatically detects the match HUD, tracks local Elixir, detects local hand changes,
 * suppresses own-play arena events, and opens the picker only on higher-quality opponent candidates.
 */
public final class AutoOverlayService extends Service {
    public static final String ACTION_STOP = "com.huseyn.elixircollector.AUTO_OVERLAY_STOP";
    private static final String CHANNEL = "royalevision_auto_overlay";
    private static final int NOTIFICATION_ID = 9506;

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
    private final TextView[] deckSlots = new TextView[8];
    private final ArrayList<TextView> filterButtons = new ArrayList<>();

    private boolean compact = true;
    private int filterCost = -1; // -1 all, 0 mirror, 1..9 cost
    private int handledSession;
    private long handledEnemyCandidate;
    private long handledLocalPlay;
    private long handledHandChange;

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
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else startForeground(NOTIFICATION_ID, n);

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

    private void updateAutomaticState() {
        boolean match = cv.getBoolean(AutoCaptureService.K_MATCH, false);
        int session = cv.getInt(AutoCaptureService.K_SESSION, 0);
        double local = cv.getFloat(AutoCaptureService.K_LOCAL_ELIXIR, Float.NaN);
        if (match && session > 0 && session != handledSession && !Double.isNaN(local)) {
            handledSession = session;
            state.start(System.currentTimeMillis(), local);
            handledEnemyCandidate = 0L;
            handledLocalPlay = 0L;
            handledHandChange = 0L;
            rebuildDeck();
            Toast.makeText(this, "Battle detected • anchored at observed Elixir "
                    + String.format(Locale.US, "%.1f", local), Toast.LENGTH_SHORT).show();
        }

        long hand = cv.getLong(AutoCaptureService.K_HAND_CHANGE_MS, 0L);
        long own = cv.getLong(AutoCaptureService.K_LOCAL_PLAY_MS, 0L);
        long enemy = cv.getLong(AutoCaptureService.K_ENEMY_CANDIDATE_MS, 0L);

        if (hand > handledHandChange) {
            handledHandChange = hand;
            if (hintText != null) hintText.setText("LOCAL HAND CHANGED • ownership guard armed");
        }
        if (own > handledLocalPlay) {
            handledLocalPlay = own;
            if (hintText != null) hintText.setText("✓ YOUR PLAY • hand changed + your Elixir dropped");
        }
        if (enemy > handledEnemyCandidate && state.isStarted()) {
            handledEnemyCandidate = enemy;
            String hint = cv.getString(AutoCaptureService.K_HINT, "DEPLOYMENT / ARENA CHANGE");
            if (hintText != null) hintText.setText("⚠ OPPONENT ACTION? • " + hint);
            // AI-less identity cannot be trusted, so automatically bring up the card picker
            // instead of inventing a card and poisoning the opponent state.
            setCompact(false);
            showPicker();
        }
    }

    private void showOverlay() {
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(dp(132), WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(dp(4), getResources().getDisplayMetrics().widthPixels - dp(138));
        params.y = dp(68);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.setBackground(panel(Color.argb(244, 18, 13, 26), 18, Color.rgb(173, 82, 232), 2));
        if (Build.VERSION.SDK_INT >= 21) root.setElevation(dp(12));

        LinearLayout compactBar = new LinearLayout(this);
        compactBar.setOrientation(LinearLayout.VERTICAL);
        compactBar.setGravity(Gravity.CENTER);
        root.addView(compactBar, new LinearLayout.LayoutParams(-1, dp(59)));

        opponentText = label("💧 ?", 20, Color.WHITE, true);
        opponentText.setGravity(Gravity.CENTER);
        compactBar.addView(opponentText, new LinearLayout.LayoutParams(-1, dp(32)));
        compactStatus = label("SEARCHING", 8, Color.rgb(218, 190, 235), true);
        compactStatus.setGravity(Gravity.CENTER);
        compactBar.addView(compactStatus, new LinearLayout.LayoutParams(-1, dp(20)));
        installDrag(compactBar);

        expanded = new LinearLayout(this);
        expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setVisibility(View.GONE);
        root.addView(expanded, new LinearLayout.LayoutParams(-1, -2));

        buildHeader();
        buildAutoStatus();
        buildDeck();
        buildActions();
        buildPicker();

        try {
            wm.addView(root, params);
            refresh();
        } catch (RuntimeException e) {
            stopSelf();
        }
    }

    private void buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        expanded.addView(row, h(40));
        clockText = label("SEARCHING", 12, Color.WHITE, true);
        row.addView(clockText, new LinearLayout.LayoutParams(0, dp(36), 1f));
        TextView collapse = smallButton("−");
        collapse.setOnClickListener(v -> setCompact(true));
        row.addView(collapse, fixed(40, 4));
        TextView close = smallButton("×");
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, fixed(40, 0));
    }

    private void buildAutoStatus() {
        cvStatus = label("AUTO CV • waiting for capture", 10, Color.rgb(189, 228, 255), true);
        cvStatus.setGravity(Gravity.CENTER);
        cvStatus.setPadding(dp(4), dp(4), dp(4), dp(3));
        expanded.addView(cvStatus, new LinearLayout.LayoutParams(-1, dp(29)));
        hintText = label("Hand changes are used to reject your own deployments", 9,
                Color.rgb(207, 192, 218), false);
        hintText.setGravity(Gravity.CENTER);
        hintText.setMaxLines(2);
        expanded.addView(hintText, new LinearLayout.LayoutParams(-1, dp(38)));
    }

    private void buildDeck() {
        TextView title = label("OPPONENT DECK / CYCLE", 10, Color.rgb(229, 195, 248), true);
        title.setGravity(Gravity.CENTER);
        expanded.addView(title, h(24));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            expanded.addView(row, h(48));
            for (int c = 0; c < 4; c++) {
                int index = r * 4 + c;
                TextView slot = label("?", 9, Color.rgb(190, 177, 200), true);
                slot.setGravity(Gravity.CENTER);
                slot.setMaxLines(2);
                slot.setBackground(panel(Color.rgb(40,31,50), 9, Color.rgb(73,59,85), 1));
                deckSlots[index] = slot;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
                if (c < 3) p.rightMargin = dp(3);
                row.addView(slot, p);
            }
        }
    }

    private void buildActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = h(43); rp.topMargin = dp(4);
        expanded.addView(row, rp);

        TextView cards = actionButton("CARDS", Color.rgb(111,55,151));
        cards.setOnClickListener(v -> showPicker());
        row.addView(cards, weight(4));
        TextView undo = actionButton("UNDO", Color.rgb(54,65,84));
        undo.setOnClickListener(v -> { state.undo(); rebuildDeck(); refresh(); });
        row.addView(undo, weight(4));
        TextView reset = actionButton("WAIT NEW", Color.rgb(83,45,99));
        reset.setOnClickListener(v -> { state.reset(); handledSession = cv.getInt(AutoCaptureService.K_SESSION,0); rebuildDeck(); refresh(); });
        row.addView(reset, weight(0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        expanded.addView(row2, h(41));
        double[] spends = {1,2,3};
        for (double amount : spends) {
            TextView b = actionButton("ABILITY −" + (int)amount, Color.rgb(96,47,64));
            b.setOnClickListener(v -> { state.addSpend(amount,System.currentTimeMillis()); refresh(); });
            row2.addView(b, weight(3));
        }
        TextView gain = actionButton("+1", Color.rgb(40,98,72));
        gain.setOnClickListener(v -> { state.addGain(1,System.currentTimeMillis()); refresh(); });
        row2.addView(gain, weight(0));

        lastText = label("LAST: —", 9, Color.rgb(197,183,207), false);
        lastText.setGravity(Gravity.CENTER);
        expanded.addView(lastText, new LinearLayout.LayoutParams(-1, dp(25)));
    }

    private void buildPicker() {
        picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setVisibility(View.GONE);
        picker.setPadding(dp(3),dp(4),dp(3),dp(3));
        picker.setBackground(panel(Color.rgb(28,22,37), 12, Color.rgb(75,61,88),1));
        expanded.addView(picker, new LinearLayout.LayoutParams(-1,-2));

        TextView title = label("OPPONENT ACTION • choose exact card", 9, Color.rgb(222,204,234), true);
        title.setGravity(Gravity.CENTER);
        picker.addView(title, h(26));

        LinearLayout filtersA = new LinearLayout(this);
        filtersA.setOrientation(LinearLayout.HORIZONTAL);
        picker.addView(filtersA, h(34));
        addFilter(filtersA, "ALL", -1);
        for (int c=1;c<=4;c++) addFilter(filtersA,String.valueOf(c),c);
        LinearLayout filtersB = new LinearLayout(this);
        filtersB.setOrientation(LinearLayout.HORIZONTAL);
        picker.addView(filtersB,h(34));
        for (int c=5;c<=9;c++) addFilter(filtersB,String.valueOf(c),c);
        addFilter(filtersB,"M",0);

        ScrollView scroll = new ScrollView(this);
        picker.addView(scroll, new LinearLayout.LayoutParams(-1, dp(285)));
        cardGrid = new LinearLayout(this);
        cardGrid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cardGrid, new ScrollView.LayoutParams(-1,-2));
        rebuildCardGrid();
    }

    private void addFilter(LinearLayout row, String text, int value) {
        TextView b = smallButton(text);
        b.setTextSize(10);
        b.setTag(value);
        b.setOnClickListener(v -> { filterCost = (Integer)v.getTag(); refreshFilters(); rebuildCardGrid(); });
        filterButtons.add(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(31),1f);
        p.rightMargin=dp(2); row.addView(b,p);
    }

    private void refreshFilters() {
        for (TextView b : filterButtons) {
            boolean on = ((Integer)b.getTag()) == filterCost;
            b.setBackground(panel(on ? Color.rgb(139,64,188) : Color.rgb(47,38,57),
                    8, on ? Color.rgb(220,160,255) : Color.rgb(75,62,87),1));
        }
    }

    private void rebuildCardGrid() {
        if (cardGrid == null) return;
        cardGrid.removeAllViews();
        List<CardCatalog.Card> list = new ArrayList<>();
        if (filterCost == -1) list.addAll(CardCatalog.ALL);
        else if (filterCost == 0) { CardCatalog.Card m=CardCatalog.mirror(); if(m!=null) list.add(m); }
        else list.addAll(CardCatalog.choicesForCost(filterCost));

        for (int i=0;i<list.size();i+=3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rp=h(51); rp.bottomMargin=dp(3); cardGrid.addView(row,rp);
            for (int c=0;c<3;c++) {
                if (i+c>=list.size()) { row.addView(new TextView(this),new LinearLayout.LayoutParams(0,dp(48),1f)); continue; }
                CardCatalog.Card card=list.get(i+c);
                TextView b=label(CardCatalog.shortName(card.displayName)+"\n"+(card.mirror?"M":card.cost),8,Color.WHITE,true);
                b.setGravity(Gravity.CENTER); b.setMaxLines(2);
                b.setBackground(panel(Color.rgb(55,42,67),9,Color.rgb(87,68,102),1));
                b.setOnClickListener(v -> registerCard(card));
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1f); if(c<2)bp.rightMargin=dp(3); row.addView(b,bp);
            }
        }
        refreshFilters();
    }

    private void registerCard(CardCatalog.Card card) {
        if (!state.isStarted()) {
            Toast.makeText(this,"Waiting for automatic match detection",Toast.LENGTH_SHORT).show(); return;
        }
        AutoState.Event e = state.addCard(card,System.currentTimeMillis());
        if (e == null) {
            Toast.makeText(this,"Mirror needs a previous opponent card",Toast.LENGTH_SHORT).show(); return;
        }
        picker.setVisibility(View.GONE);
        rebuildDeck(); refresh();
        Toast.makeText(this,e.name+" registered",Toast.LENGTH_SHORT).show();
    }

    private void showPicker() {
        if (picker == null) return;
        picker.setVisibility(View.VISIBLE);
        filterCost=-1; rebuildCardGrid();
        setCompact(false);
    }

    private void rebuildDeck() {
        List<AutoState.DeckStatus> deck=state.getDeck();
        for(int i=0;i<8;i++) {
            if(deckSlots[i]==null) continue;
            if(i>=deck.size()) { deckSlots[i].setText("?"); deckSlots[i].setTextColor(Color.rgb(158,145,169)); continue; }
            AutoState.DeckStatus s=deck.get(i);
            deckSlots[i].setText(CardCatalog.shortName(s.name)+"\n"+(s.cardsUntilReturn==0?"✓":"↻"+s.cardsUntilReturn));
            deckSlots[i].setTextColor(s.cardsUntilReturn==0?Color.rgb(181,255,200):Color.rgb(231,218,239));
        }
    }

    private void refresh() {
        long now=System.currentTimeMillis();
        boolean capture=cv.getBoolean(AutoCaptureService.K_CAPTURE,false);
        boolean match=cv.getBoolean(AutoCaptureService.K_MATCH,false);
        double local=cv.getFloat(AutoCaptureService.K_LOCAL_ELIXIR,Float.NaN);
        double conf=cv.getFloat(AutoCaptureService.K_ELIXIR_CONF,0f);
        String status=cv.getString(AutoCaptureService.K_STATUS,"AUTO STOPPED");

        if(opponentText!=null) {
            double enemy=state.getOpponentElixir(now);
            opponentText.setText(Double.isNaN(enemy)?"💧 ?":String.format(Locale.US,"💧 %.1f",enemy));
        }
        if(compactStatus!=null) {
            if(!capture) compactStatus.setText("AUTO OFF");
            else if(!match) compactStatus.setText("SEARCHING HUD");
            else compactStatus.setText(Double.isNaN(local)?"AUTO • YOU ?":String.format(Locale.US,"AUTO • YOU %.1f",local));
        }
        if(clockText!=null) clockText.setText(state.getClock(now));
        if(cvStatus!=null) {
            cvStatus.setText(status+" • Elixir conf "+Math.round(conf*100)+"%");
            cvStatus.setTextColor(match?Color.rgb(178,244,198):Color.rgb(210,193,221));
        }
        if(lastText!=null) lastText.setText(state.getLast());
    }

    private void setCompact(boolean value) {
        compact=value;
        if(expanded!=null) expanded.setVisibility(compact?View.GONE:View.VISIBLE);
        if(params!=null) {
            int sw=getResources().getDisplayMetrics().widthPixels;
            params.width=compact?dp(132):Math.min(dp(382),sw-dp(10));
            clamp();
            if(wm!=null&&root!=null) try{wm.updateViewLayout(root,params);}catch(RuntimeException ignored){}
        }
    }

    private void installDrag(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            int sx,sy; float dx,dy; boolean dragged;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch(e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx=params.x;sy=params.y;dx=e.getRawX();dy=e.getRawY();dragged=false;return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx=e.getRawX()-dx,my=e.getRawY()-dy;
                        if(Math.abs(mx)>dp(4)||Math.abs(my)>dp(4))dragged=true;
                        if(dragged){params.x=sx+Math.round(mx);params.y=sy+Math.round(my);clamp();wm.updateViewLayout(root,params);}return true;
                    case MotionEvent.ACTION_UP: if(!dragged)setCompact(!compact);return true;
                    default:return true;
                }
            }
        });
    }

    private void clamp(){int sw=getResources().getDisplayMetrics().widthPixels,sh=getResources().getDisplayMetrics().heightPixels;params.x=Math.max(0,Math.min(params.x,Math.max(0,sw-params.width)));params.y=Math.max(0,Math.min(params.y,Math.max(0,sh-dp(60))));}

    private Notification notification(){
        Intent open=new Intent(this,AutoMainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,AutoOverlayService.class).setAction(ACTION_STOP);PendingIntent spi=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setContentTitle("RoyaleVision Auto").setContentText("Automatic local-CV overlay active").setSmallIcon(R.drawable.ic_notification_elixir).setContentIntent(pi).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",spi).build()).build();
    }
    private void createChannel(){if(Build.VERSION.SDK_INT<26)return;NotificationChannel c=new NotificationChannel(CHANNEL,"RoyaleVision Auto overlay",NotificationManager.IMPORTANCE_LOW);c.setShowBadge(false);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}

    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(wm!=null&&root!=null)try{wm.removeView(root);}catch(RuntimeException ignored){};root=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}

    private TextView label(String t,int s,int c,boolean bold){TextView v=new TextView(this);v.setText(t);v.setTextSize(s);v.setTextColor(c);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private TextView smallButton(String t){TextView v=label(t,11,Color.rgb(220,207,229),true);v.setGravity(Gravity.CENTER);v.setBackground(panel(Color.rgb(49,40,59),9,Color.rgb(78,65,91),1));return v;}
    private TextView actionButton(String t,int fill){TextView v=label(t,9,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setBackground(panel(fill,9,Color.argb(80,255,255,255),1));return v;}
    private GradientDrawable panel(int fill,int radius,int stroke,int sw){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT&&sw>0)d.setStroke(dp(sw),stroke);return d;}
    private LinearLayout.LayoutParams h(int dp){return new LinearLayout.LayoutParams(-1,dp(dp));}
    private LinearLayout.LayoutParams fixed(int width,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(width),dp(34));p.rightMargin=dp(margin);return p;}
    private LinearLayout.LayoutParams weight(int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1f);p.rightMargin=dp(margin);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
