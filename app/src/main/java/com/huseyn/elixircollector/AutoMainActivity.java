package com.huseyn.elixircollector;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
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

/** Setup screen for v5.2 sound + calibrated-hand fusion. */
public final class AutoMainActivity extends Activity {
    private static final int REQ_CAPTURE=4105,REQ_AUDIO=4106;
    private TextView overlayBadge,captureBadge,deckBadge,audioBadge;private boolean openGameAfterPermission,pendingStart;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(14,10,20));getWindow().setNavigationBarColor(Color.rgb(14,10,20));buildUi();}
    @Override protected void onResume(){super.onResume();updateBadges();}

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(14,10,20));LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(20),dp(18),dp(28));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        LinearLayout hero=box(Color.rgb(42,24,58),Color.rgb(140,73,188));hero.setGravity(Gravity.CENTER);page.addView(hero,match(12));TextView title=text("ROYALEVISION AUTO v5.2",25,Color.WHITE,true);title.setGravity(Gravity.CENTER);hero.addView(title,match(4));TextView sub=text("sound + calibrated hand + vision • no cloud",13,Color.rgb(229,208,241),false);sub.setGravity(Gravity.CENTER);hero.addView(sub,match(0));

        LinearLayout how=box(Color.rgb(29,23,38),Color.rgb(67,55,79));page.addView(how,match(12));how.addView(text("HOW THIS BUILD DECIDES",14,Color.rgb(230,197,249),true),match(7));how.addView(line("• You choose your exact 8-card deck before the match."),match(4));how.addView(line("• Your 4 live hand slots are compared only against those 8 cards."),match(4));how.addView(line("• Hand change + your Elixir drop = YOUR play, so enemy tracking is suppressed."),match(4));how.addView(line("• Game sound transients are learned from your own identified plays and reused as supporting evidence."),match(4));how.addView(line("• Enemy Elixir is auto-spent only when a deployment cost badge is also detected."),match(4));how.addView(line("• No debug/calibration boxes are drawn over the game."),match(0));

        LinearLayout status=box(Color.rgb(29,23,38),Color.rgb(67,55,79));page.addView(status,match(12));status.addView(text("SETUP",14,Color.rgb(230,197,249),true),match(7));deckBadge=badge();status.addView(deckBadge,match(5));overlayBadge=badge();status.addView(overlayBadge,match(5));audioBadge=badge();status.addView(audioBadge,match(5));captureBadge=badge();status.addView(captureBadge,match(0));

        Button deck=button("1 • CALIBRATE MY 8-CARD DECK",Color.rgb(94,58,132));deck.setOnClickListener(v->startActivity(new Intent(this,DeckCalibrationActivity.class)));page.addView(deck,height(56,8));Button overlay=button("2 • ALLOW FLOATING WINDOW",Color.rgb(102,53,140));overlay.setOnClickListener(v->requestOverlayPermission());page.addView(overlay,height(54,8));Button start=button("3 • START AUTO + OPEN CLASH ROYALE",Color.rgb(156,67,205));start.setOnClickListener(v->startAutomatic(true));page.addView(start,height(61,8));Button startOnly=button("START AUTO TRACKING",Color.rgb(51,104,163));startOnly.setOnClickListener(v->startAutomatic(false));page.addView(startOnly,height(53,8));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);page.addView(row,height(48,12));Button game=button("OPEN GAME",Color.rgb(46,54,72));game.setTextSize(12);game.setOnClickListener(v->openGame());LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,dp(48),1);a.rightMargin=dp(4);row.addView(game,a);Button stop=button("STOP ALL",Color.rgb(91,42,58));stop.setTextSize(12);stop.setOnClickListener(v->stopAll());LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(0,dp(48),1);c.leftMargin=dp(4);row.addView(stop,c);

        LinearLayout note=box(Color.rgb(29,23,38),Color.rgb(67,55,79));page.addView(note,match(0));note.addView(text("WHAT “NO DEBUG BOXES” MEANS",13,Color.rgb(230,197,249),true),match(6));TextView body=text("The earlier suggestion was to temporarily draw rectangles around the Elixir bar and each detected hand slot so a recording would show exactly what the detector was looking at. You asked for a version without that, so v5.2 keeps those calibration measurements internal and only shows the transparent opponent overlay.",12,Color.rgb(185,170,195),false);body.setLineSpacing(0,1.15f);note.addView(body,match(0));
        setContentView(scroll);updateBadges();
    }

    private void startAutomatic(boolean openGame){
        if(DeckCalibrationActivity.loadDeck(this).size()!=8){Toast.makeText(this,"Calibrate your exact 8-card deck first",Toast.LENGTH_LONG).show();startActivity(new Intent(this,DeckCalibrationActivity.class));return;}
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){requestOverlayPermission();Toast.makeText(this,"Enable floating-window permission, then tap Start again",Toast.LENGTH_LONG).show();return;}
        openGameAfterPermission=openGame;
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingStart=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO);return;}
        beginCapture();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==REQ_AUDIO&&pendingStart){pendingStart=false;if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)Toast.makeText(this,"Sound permission denied • tracking will fall back to hand + vision",Toast.LENGTH_LONG).show();beginCapture();}}
    private void beginCapture(){startOverlayService();MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQ_CAPTURE)return;if(resultCode!=RESULT_OK||data==null){Toast.makeText(this,"Screen capture permission was not granted",Toast.LENGTH_LONG).show();return;}Intent service=new Intent(this,AutoCaptureService.class);service.putExtra(AutoCaptureService.EXTRA_RESULT_CODE,resultCode);service.putExtra(AutoCaptureService.EXTRA_RESULT_DATA,data);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);Toast.makeText(this,"Auto fusion started",Toast.LENGTH_SHORT).show();if(openGameAfterPermission)getWindow().getDecorView().postDelayed(this::openGame,350);updateBadges();}

    private void requestOverlayPermission(){if(Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this)){Toast.makeText(this,"Floating window already enabled",Toast.LENGTH_SHORT).show();return;}startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));}
    private void startOverlayService(){Intent i=new Intent(this,AutoOverlayService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void stopAll(){stopService(new Intent(this,AutoCaptureService.class));stopService(new Intent(this,AutoOverlayService.class));getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE).edit().putBoolean(AutoCaptureService.K_CAPTURE,false).putBoolean(AutoCaptureService.K_MATCH,false).apply();Toast.makeText(this,"RoyaleVision stopped",Toast.LENGTH_SHORT).show();updateBadges();}
    private void openGame(){Intent launch=getPackageManager().getLaunchIntentForPackage("com.supercell.clashroyale");if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);return;}try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=com.supercell.clashroyale")));}catch(ActivityNotFoundException e){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id=com.supercell.clashroyale")));}}

    private void updateBadges(){if(overlayBadge==null)return;boolean overlay=Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this),capture=getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE).getBoolean(AutoCaptureService.K_CAPTURE,false),deck=DeckCalibrationActivity.loadDeck(this).size()==8,audio=Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;setBadge(deckBadge,deck?"✓ 8-CARD DECK CALIBRATED":"! CALIBRATE YOUR 8-CARD DECK",deck);setBadge(overlayBadge,overlay?"✓ FLOATING WINDOW ENABLED":"! FLOATING WINDOW REQUIRED",overlay);setBadge(audioBadge,audio?"✓ SOUND PERMISSION READY":"○ SOUND PERMISSION NOT GRANTED",audio);setBadge(captureBadge,capture?"✓ AUTO CAPTURE ACTIVE":"○ AUTO CAPTURE OFF",capture);}
    private TextView badge(){TextView v=text("",12,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(8),dp(8),dp(8),dp(8));return v;}private void setBadge(TextView v,String t,boolean good){v.setText(t);v.setTextColor(good?Color.rgb(178,255,198):Color.rgb(255,196,204));v.setBackground(panel(good?Color.rgb(30,79,49):Color.rgb(84,39,49),12,good?Color.rgb(71,139,89):Color.rgb(137,65,78)));}
    private LinearLayout box(int fill,int stroke){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(15),dp(14),dp(15),dp(14));b.setBackground(panel(fill,18,stroke));return b;}private TextView line(String s){TextView t=text(s,13,Color.rgb(224,216,231),false);t.setLineSpacing(0,1.12f);return t;}private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT_BOLD);b.setGravity(Gravity.CENTER);b.setBackground(panel(color,16,Color.argb(75,255,255,255)));return b;}private GradientDrawable panel(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}private LinearLayout.LayoutParams match(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);return p;}private LinearLayout.LayoutParams height(int h,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(h));p.bottomMargin=dp(bottom);return p;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
