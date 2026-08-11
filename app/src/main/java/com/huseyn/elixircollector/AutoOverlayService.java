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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Transparent, icon-first automatic overlay. No manual card picker. */
public final class AutoOverlayService extends Service {
    public static final String ACTION_STOP="com.huseyn.elixircollector.AUTO_OVERLAY_STOP";
    private static final String CHANNEL="royalevision_auto_overlay";private static final int NOTIFICATION_ID=9506;private static final String STATE_PREFS="royalevision_auto_overlay_state_v52",K_STATE="state_json";
    private final Handler handler=new Handler(Looper.getMainLooper());private final AutoState state=new AutoState();private SharedPreferences cv;
    private WindowManager wm;private WindowManager.LayoutParams params;private LinearLayout root,expanded;private TextView opponentText,statusText,clockText,detailText,lastText;
    private final SlotView[] compactSlots=new SlotView[8],expandedSlots=new SlotView[8];private boolean expandedVisible=false;private int handledSession;private long handledEnemyEvent;private boolean previousMatch;

    private final Runnable ticker=new Runnable(){@Override public void run(){updateAutomaticState();refresh();handler.postDelayed(this,120);}};

    @Override public void onCreate(){super.onCreate();cv=getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE);createChannel();restoreState();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}Notification n=notification();if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION_ID,n);if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}if(root==null){showOverlay();handler.post(ticker);}return START_STICKY;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);saveState();if(wm!=null&&root!=null)try{wm.removeView(root);}catch(RuntimeException ignored){}root=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}

    private void updateAutomaticState(){
        boolean match=cv.getBoolean(AutoCaptureService.K_MATCH,false);int session=cv.getInt(AutoCaptureService.K_SESSION,0);
        if(match&&session>0&&session!=handledSession){long clock=cv.getLong(AutoCaptureService.K_MATCH_CLOCK_START_MS,0),anchor=cv.getLong(AutoCaptureService.K_MATCH_ANCHOR_MS,0);double elixir=cv.getFloat(AutoCaptureService.K_MATCH_ANCHOR_ELIXIR,Float.NaN);if(clock>0&&anchor>0&&!Double.isNaN(elixir)){handledSession=session;handledEnemyEvent=0;state.start(clock,anchor,elixir);saveState();rebuildDeck();}}
        if(previousMatch&&!match){state.reset();handledEnemyEvent=0;saveState();rebuildDeck();}previousMatch=match;
        long enemy=cv.getLong(AutoCaptureService.K_ENEMY_EVENT_MS,0);if(match&&state.isStarted()&&enemy>handledEnemyEvent){handledEnemyEvent=enemy;int cost=cv.getInt(AutoCaptureService.K_ENEMY_COST,0);String id=cv.getString(AutoCaptureService.K_ENEMY_CARD_ID,null);AutoState.Event ev=id!=null&&id.length()>0?state.addCardId(id,enemy):state.addUnknownCost(cost,enemy);if(ev!=null){saveState();rebuildDeck();}}
    }

    private void showOverlay(){
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;params=new WindowManager.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels-dp(8),dp(560)),WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);params.gravity=Gravity.TOP|Gravity.START;params.x=dp(4);params.y=dp(42);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(3),dp(3),dp(3),dp(3));root.setAlpha(0.93f);root.setBackground(panel(Color.argb(58,8,7,12),14,Color.argb(80,190,100,240),1));
        buildCompact();expanded=new LinearLayout(this);expanded.setOrientation(LinearLayout.VERTICAL);expanded.setPadding(dp(5),dp(4),dp(5),dp(5));expanded.setVisibility(View.GONE);expanded.setBackground(panel(Color.argb(94,14,10,20),12,Color.argb(70,200,120,250),1));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);ep.topMargin=dp(2);root.addView(expanded,ep);buildExpanded();
        try{wm.addView(root,params);rebuildDeck();refresh();}catch(RuntimeException e){stopSelf();}
    }

    private void buildCompact(){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);root.addView(row,new LinearLayout.LayoutParams(-1,dp(43)));
        LinearLayout eb=new LinearLayout(this);eb.setOrientation(LinearLayout.VERTICAL);eb.setGravity(Gravity.CENTER);eb.setBackground(panel(Color.argb(92,74,35,96),9,Color.argb(100,210,140,250),1));row.addView(eb,new LinearLayout.LayoutParams(dp(58),dp(39)));opponentText=label("💧 ?",15,Color.WHITE,true);opponentText.setGravity(Gravity.CENTER);eb.addView(opponentText,new LinearLayout.LayoutParams(-1,dp(25)));TextView auto=label("AUTO",7,Color.rgb(228,205,240),true);auto.setGravity(Gravity.CENTER);eb.addView(auto,new LinearLayout.LayoutParams(-1,dp(11)));installDragAndTap(eb);
        for(int i=0;i<8;i++){compactSlots[i]=makeSlot(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(39),1f);p.leftMargin=dp(2);row.addView(compactSlots[i].root,p);}
        statusText=label("SEARCHING FOR MATCH",8,Color.rgb(226,209,236),true);statusText.setGravity(Gravity.CENTER);root.addView(statusText,new LinearLayout.LayoutParams(-1,dp(14)));
    }

    private void buildExpanded(){
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);expanded.addView(head,new LinearLayout.LayoutParams(-1,dp(34)));clockText=label("SEARCHING",11,Color.WHITE,true);clockText.setGravity(Gravity.CENTER_VERTICAL);head.addView(clockText,new LinearLayout.LayoutParams(0,dp(34),1));TextView collapse=button("−");collapse.setOnClickListener(v->setExpanded(false));head.addView(collapse,new LinearLayout.LayoutParams(dp(40),dp(32)));TextView close=button("×");close.setOnClickListener(v->stopSelf());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(40),dp(32));cp.leftMargin=dp(3);head.addView(close,cp);
        detailText=label("Sound + calibrated hand + vision",9,Color.rgb(206,228,248),true);detailText.setGravity(Gravity.CENTER);expanded.addView(detailText,new LinearLayout.LayoutParams(-1,dp(28)));
        TextView legend=label("Icon = confidently identified • ? = identity still unknown • IN = back in cycle",8,Color.rgb(190,177,200),false);legend.setGravity(Gravity.CENTER);expanded.addView(legend,new LinearLayout.LayoutParams(-1,dp(26)));
        LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.HORIZONTAL);expanded.addView(cards,new LinearLayout.LayoutParams(-1,dp(66)));for(int i=0;i<8;i++){expandedSlots[i]=makeSlot(false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(62),1);if(i>0)p.leftMargin=dp(3);cards.addView(expandedSlots[i].root,p);}
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(38));ap.topMargin=dp(4);expanded.addView(actions,ap);TextView undo=button("UNDO LAST AUTO");undo.setOnClickListener(v->{state.undo();saveState();rebuildDeck();refresh();});actions.addView(undo,new LinearLayout.LayoutParams(0,dp(36),1));TextView clear=button("CLEAR");clear.setOnClickListener(v->{state.reset();handledSession=cv.getInt(AutoCaptureService.K_SESSION,0);saveState();rebuildDeck();refresh();});LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(0,dp(36),0.45f);cl.leftMargin=dp(4);actions.addView(clear,cl);
        lastText=label("LAST: —",8,Color.rgb(205,191,214),false);lastText.setGravity(Gravity.CENTER);expanded.addView(lastText,new LinearLayout.LayoutParams(-1,dp(22)));
    }

    private SlotView makeSlot(boolean compact){
        FrameLayout f=new FrameLayout(this);f.setBackground(panel(Color.argb(72,30,25,36),8,Color.argb(70,110,90,125),1));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAlpha(0.82f);f.addView(image,new FrameLayout.LayoutParams(-1,-1));TextView q=label("?",compact?15:18,Color.argb(220,225,216,232),true);q.setGravity(Gravity.CENTER);f.addView(q,new FrameLayout.LayoutParams(-1,-1));TextView st=label("",compact?6:7,Color.WHITE,true);st.setGravity(Gravity.CENTER);st.setBackground(panel(Color.argb(125,0,0,0),5,Color.TRANSPARENT,0));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(compact?11:14),Gravity.BOTTOM);f.addView(st,sp);return new SlotView(f,image,q,st);
    }

    private void rebuildDeck(){
        List<AutoState.DeckStatus> deck=state.getDeck();for(int i=0;i<8;i++){AutoState.DeckStatus s=i<deck.size()?deck.get(i):null;applySlot(compactSlots[i],s);applySlot(expandedSlots[i],s);}
    }
    private void applySlot(SlotView v,AutoState.DeckStatus s){if(v==null)return;if(s==null){v.image.setImageDrawable(null);v.q.setVisibility(View.VISIBLE);v.status.setText("");return;}CardIconLoader.setCard(v.image,s.deckId);v.q.setVisibility(v.image.getDrawable()==null?View.VISIBLE:View.GONE);v.status.setText(s.cardsUntilReturn==0?"IN":"↻"+s.cardsUntilReturn);v.status.setTextColor(s.cardsUntilReturn==0?Color.rgb(180,255,196):Color.WHITE);}

    private void refresh(){long now=System.currentTimeMillis();boolean capture=cv.getBoolean(AutoCaptureService.K_CAPTURE,false),match=cv.getBoolean(AutoCaptureService.K_MATCH,false),audio=cv.getBoolean(AutoCaptureService.K_AUDIO_AVAILABLE,false);double local=cv.getFloat(AutoCaptureService.K_LOCAL_ELIXIR,Float.NaN),enemy=state.getOpponentElixir(now),conf=cv.getFloat(AutoCaptureService.K_ENEMY_CONF,0f);String status=cv.getString(AutoCaptureService.K_STATUS,"AUTO OFF");if(opponentText!=null)opponentText.setText(Double.isNaN(enemy)?"💧 ?":String.format(Locale.US,"💧 %.1f",enemy));if(statusText!=null){if(!capture)statusText.setText("AUTO OFF");else if(!match)statusText.setText("SEARCHING FOR MATCH");else statusText.setText((audio?"SOUND+":"")+"HAND+VISION • YOU "+(Double.isNaN(local)?"?":String.format(Locale.US,"%.1f",local)));}if(clockText!=null)clockText.setText(state.getClock(now));if(detailText!=null)detailText.setText(status+(conf>0?" • "+Math.round(conf*100)+"%":""));if(lastText!=null)lastText.setText(state.getLast());}

    private void setExpanded(boolean value){expandedVisible=value;if(expanded!=null)expanded.setVisibility(value?View.VISIBLE:View.GONE);if(params!=null&&wm!=null&&root!=null)try{wm.updateViewLayout(root,params);}catch(RuntimeException ignored){}}
    private void installDragAndTap(View target){target.setOnTouchListener(new View.OnTouchListener(){int sx,sy;float dx,dy;boolean drag;@Override public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=params.x;sy=params.y;dx=e.getRawX();dy=e.getRawY();drag=false;return true;case MotionEvent.ACTION_MOVE:float x=e.getRawX()-dx,y=e.getRawY()-dy;if(Math.abs(x)>dp(4)||Math.abs(y)>dp(4))drag=true;if(drag){params.x=sx+Math.round(x);params.y=sy+Math.round(y);clamp();try{wm.updateViewLayout(root,params);}catch(RuntimeException ignored){}}return true;case MotionEvent.ACTION_UP:if(!drag)setExpanded(!expandedVisible);return true;default:return true;}}});}
    private void clamp(){int sw=getResources().getDisplayMetrics().widthPixels,sh=getResources().getDisplayMetrics().heightPixels;params.x=Math.max(0,Math.min(params.x,Math.max(0,sw-params.width)));params.y=Math.max(0,Math.min(params.y,Math.max(0,sh-dp(60))));}

    private void saveState(){try{JSONObject j=new JSONObject();j.put("clock",state.getMatchStartMs());j.put("anchor",state.getElixirAnchorMs());j.put("initial",state.getInitialOpponentElixir());j.put("saved",System.currentTimeMillis());JSONArray a=new JSONArray();for(AutoState.Event e:state.getEvents()){JSONObject x=new JSONObject();x.put("t",e.timeMs);x.put("k",e.kind);x.put("d",e.deckId==null?JSONObject.NULL:e.deckId);x.put("n",e.name);x.put("x",e.delta);x.put("c",e.cycleAdvance);a.put(x);}j.put("events",a);getSharedPreferences(STATE_PREFS,MODE_PRIVATE).edit().putString(K_STATE,j.toString()).apply();}catch(Exception ignored){}}
    private void restoreState(){String raw=getSharedPreferences(STATE_PREFS,MODE_PRIVATE).getString(K_STATE,null);if(raw==null)return;try{JSONObject j=new JSONObject(raw);if(System.currentTimeMillis()-j.optLong("saved",0)>15*60*1000L)return;long clock=j.optLong("clock",0),anchor=j.optLong("anchor",0);double init=j.optDouble("initial",Double.NaN);ArrayList<AutoState.Event> list=new ArrayList<>();JSONArray a=j.optJSONArray("events");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String d=x.isNull("d")?null:x.optString("d",null);list.add(new AutoState.Event(x.optLong("t"),x.optString("k"),d,x.optString("n"),x.optDouble("x"),x.optBoolean("c")));}if(clock>0&&anchor>0&&!Double.isNaN(init))state.restore(clock,anchor,init,list);}catch(Exception ignored){}}

    private Notification notification(){Intent open=new Intent(this,AutoMainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setContentTitle("RoyaleVision Auto v5.2").setContentText("Transparent icon overlay is active").setSmallIcon(R.drawable.ic_notification_elixir).setContentIntent(pi).setOngoing(true).build();}
    private void createChannel(){if(Build.VERSION.SDK_INT<26)return;NotificationChannel c=new NotificationChannel(CHANNEL,"RoyaleVision overlay",NotificationManager.IMPORTANCE_LOW);c.setShowBadge(false);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}
    private TextView label(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}private TextView button(String s){TextView v=label(s,9,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setBackground(panel(Color.argb(90,55,42,68),8,Color.argb(90,150,120,170),1));return v;}private GradientDrawable panel(int fill,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT&&width>0)d.setStroke(dp(width),stroke);return d;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static final class SlotView{final FrameLayout root;final ImageView image;final TextView q,status;SlotView(FrameLayout r,ImageView i,TextView q,TextView s){root=r;image=i;this.q=q;status=s;}}
}
