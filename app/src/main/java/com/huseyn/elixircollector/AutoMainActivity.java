package com.huseyn.elixircollector;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Launcher for the automatic, fully on-device, no-cloud/no-AI build. */
public final class AutoMainActivity extends Activity {
    private static final int REQ_CAPTURE = 4105;
    private TextView overlayBadge;
    private TextView captureBadge;
    private boolean openGameAfterPermission;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(14,10,20));
        getWindow().setNavigationBarColor(Color.rgb(14,10,20));
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        updateBadges();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(14,10,20));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(22),dp(18),dp(30));page.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));

        LinearLayout hero=box(Color.rgb(42,24,58),Color.rgb(140,73,188));hero.setGravity(Gravity.CENTER);page.addView(hero,match(dp(13)));
        TextView title=text("ROYALEVISION AUTO v5",25,Color.WHITE,true);title.setGravity(Gravity.CENTER);hero.addView(title,match(dp(4)));
        TextView sub=text("AI-less • fully local screen CV",14,Color.rgb(229,208,241),false);sub.setGravity(Gravity.CENTER);hero.addView(sub,match(0));

        LinearLayout what=box(Color.rgb(29,23,38),Color.rgb(67,55,79));page.addView(what,match(dp(12)));
        what.addView(text("WHAT IS AUTOMATIC NOW",14,Color.rgb(230,197,249),true),match(dp(8)));
        what.addView(line("• Detects the Clash Royale battle HUD automatically"),match(dp(5)));
        what.addView(line("• Reads your visible purple Elixir bar instead of assuming a 5-Elixir start"),match(dp(5)));
        what.addView(line("• Fingerprints all 4 local hand slots and detects when your card changes"),match(dp(5)));
        what.addView(line("• Hand change + your Elixir drop = strong YOUR-PLAY evidence"),match(dp(5)));
        what.addView(line("• Arena changes near your own play are suppressed; remaining spikes become opponent-action candidates"),match(dp(5)));
        what.addView(line("• Automatically opens the card picker when an opponent-action candidate is detected"),match(0));

        LinearLayout status=box(Color.rgb(29,23,38),Color.rgb(67,55,79));page.addView(status,match(dp(12)));
        status.addView(text("SETUP STATUS",14,Color.rgb(230,197,249),true),match(dp(8)));
        overlayBadge=text("",13,Color.WHITE,true);overlayBadge.setGravity(Gravity.CENTER);overlayBadge.setPadding(dp(8),dp(9),dp(8),dp(9));status.addView(overlayBadge,match(dp(6)));
        captureBadge=text("",13,Color.WHITE,true);captureBadge.setGravity(Gravity.CENTER);captureBadge.setPadding(dp(8),dp(9),dp(8),dp(9));status.addView(captureBadge,match(0));

        Button permission=button("1 • ALLOW FLOATING WINDOW",Color.rgb(102,53,140));permission.setOnClickListener(v->requestOverlayPermission());page.addView(permission,height(55,8));
        Button auto=button("2 • START AUTO TRACKING + OPEN GAME",Color.rgb(156,67,205));auto.setOnClickListener(v->startAutomatic(true));page.addView(auto,height(60,8));
        Button autoOnly=button("START AUTO TRACKING",Color.rgb(51,104,163));autoOnly.setOnClickListener(v->startAutomatic(false));page.addView(autoOnly,height(54,8));

        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);page.addView(row,height(49,12));
        Button game=button("OPEN GAME",Color.rgb(46,54,72));game.setTextSize(12);game.setOnClickListener(v->openGame());LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,dp(49),1f);hp.rightMargin=dp(4);row.addView(game,hp);
        Button stop=button("STOP ALL",Color.rgb(91,42,58));stop.setTextSize(12);stop.setOnClickListener(v->stopAll());LinearLayout.LayoutParams hp2=new LinearLayout.LayoutParams(0,dp(49),1f);hp2.leftMargin=dp(4);row.addView(stop,hp2);

        LinearLayout note=box(Color.rgb(29,23,38),Color.rgb(67,55,79));page.addView(note,match(0));
        note.addView(text("IMPORTANT LIMIT",14,Color.rgb(230,197,249),true),match(dp(7)));
        TextView body=text("This build uses MediaProjection and classical pixel/motion heuristics only. It does not use Gemini, a server, game memory, packets, or an ML model. It can automatically detect match state, your Elixir, your hand changes, and likely opponent-action timing. Exact opponent card identity is NOT silently guessed: when the evidence says the opponent probably acted, the picker opens so you can choose the exact card. This is intentional because a no-AI pixel heuristic cannot reliably distinguish every current troop/spell under overlap and effects without a trained visual model.",12,Color.rgb(185,170,195),false);body.setLineSpacing(0,1.17f);note.addView(body,match(0));

        setContentView(scroll);updateBadges();
    }

    private void requestOverlayPermission(){
        if(Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this)){Toast.makeText(this,"Floating-window permission already enabled",Toast.LENGTH_SHORT).show();return;}
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));
    }

    private void startAutomatic(boolean openGame){
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){requestOverlayPermission();Toast.makeText(this,"Enable floating-window permission, then tap Start again",Toast.LENGTH_LONG).show();return;}
        startOverlayService();
        openGameAfterPermission=openGame;
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_CAPTURE)return;
        if(resultCode!=RESULT_OK||data==null){Toast.makeText(this,"Screen capture permission was not granted",Toast.LENGTH_LONG).show();return;}
        Intent service=new Intent(this,AutoCaptureService.class);
        service.putExtra(AutoCaptureService.EXTRA_RESULT_CODE,resultCode);
        service.putExtra(AutoCaptureService.EXTRA_RESULT_DATA,data);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
        Toast.makeText(this,"Automatic local CV started",Toast.LENGTH_SHORT).show();
        updateBadges();
        if(openGameAfterPermission)getWindow().getDecorView().postDelayed(this::openGame,350L);
    }

    private void startOverlayService(){Intent i=new Intent(this,AutoOverlayService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}

    private void stopAll(){
        stopService(new Intent(this,AutoCaptureService.class));stopService(new Intent(this,AutoOverlayService.class));
        getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE).edit().putBoolean(AutoCaptureService.K_CAPTURE,false).putBoolean(AutoCaptureService.K_MATCH,false).apply();
        Toast.makeText(this,"RoyaleVision Auto stopped",Toast.LENGTH_SHORT).show();updateBadges();
    }

    private void openGame(){Intent launch=getPackageManager().getLaunchIntentForPackage("com.supercell.clashroyale");if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);return;}try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=com.supercell.clashroyale")));}catch(ActivityNotFoundException e){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id=com.supercell.clashroyale")));}}

    private void updateBadges(){
        if(overlayBadge==null)return;
        boolean overlay=Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this);
        boolean capture=getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE).getBoolean(AutoCaptureService.K_CAPTURE,false);
        setBadge(overlayBadge,overlay?"✓ FLOATING WINDOW ENABLED":"! FLOATING WINDOW REQUIRED",overlay);
        setBadge(captureBadge,capture?"✓ LOCAL SCREEN CV ACTIVE":"○ SCREEN CV NOT STARTED",capture);
    }
    private void setBadge(TextView v,String t,boolean good){v.setText(t);v.setTextColor(good?Color.rgb(178,255,198):Color.rgb(255,196,204));v.setBackground(panel(good?Color.rgb(30,79,49):Color.rgb(84,39,49),12,good?Color.rgb(71,139,89):Color.rgb(137,65,78)));}

    private LinearLayout box(int fill,int stroke){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(15),dp(14),dp(15),dp(14));b.setBackground(panel(fill,18,stroke));return b;}
    private TextView line(String s){TextView t=text(s,13,Color.rgb(224,216,231),false);t.setLineSpacing(0,1.12f);return t;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT_BOLD);b.setGravity(Gravity.CENTER);b.setBackground(panel(color,16,Color.argb(75,255,255,255)));return b;}
    private GradientDrawable panel(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}
    private LinearLayout.LayoutParams match(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=bottom;return p;}
    private LinearLayout.LayoutParams height(int h,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(h));p.bottomMargin=dp(bottom);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
