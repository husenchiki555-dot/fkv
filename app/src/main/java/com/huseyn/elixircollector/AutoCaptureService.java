package com.huseyn.elixircollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Local MediaProjection pipeline. Vision + calibrated hand recognition + optional
 * game playback audio are fused conservatively. Only strong cost-badge evidence
 * can automatically spend opponent Elixir; audio/card identity never acts alone.
 */
public final class AutoCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE="result_code",EXTRA_RESULT_DATA="result_data";
    public static final String ACTION_STOP="com.huseyn.elixircollector.AUTO_CAPTURE_STOP";
    public static final String PREFS="royalevision_auto_cv";
    public static final String K_CAPTURE="capture_active",K_MATCH="match_active",K_SESSION="session_id",K_LOCAL_ELIXIR="local_elixir",K_ELIXIR_CONF="elixir_conf",K_HAND_CHANGE_MS="hand_change_ms",K_LOCAL_PLAY_MS="local_play_ms",K_LOCAL_ABILITY_MS="local_ability_ms",K_ENEMY_CANDIDATE_MS="enemy_candidate_ms",K_ARENA_CHANGE="arena_change",K_HINT="effect_hint",K_STATUS="status",K_MATCH_CLOCK_START_MS="match_clock_start_ms",K_MATCH_ANCHOR_MS="match_anchor_ms",K_MATCH_ANCHOR_ELIXIR="match_anchor_elixir";
    public static final String K_AUDIO_AVAILABLE="audio_available",K_AUDIO_TRANSIENT_MS="audio_transient_ms",K_LOCAL_CARD_ID="local_card_id",K_ENEMY_EVENT_MS="enemy_event_ms",K_ENEMY_COST="enemy_cost",K_ENEMY_CARD_ID="enemy_card_id",K_ENEMY_CONF="enemy_conf";

    private static final String CHANNEL="royalevision_auto_capture";private static final int NOTIFICATION_ID=9505;private static final long ANALYZE_EVERY_NS=80_000_000L;private static final int START_STABLE_FRAMES=8,END_MISSING_FRAMES=30;

    private MediaProjection projection;private VirtualDisplay virtualDisplay;private ImageReader reader;private HandlerThread analysisThread;private Handler analysisHandler;private SharedPreferences prefs;
    private final FrameAnalyzer analyzer=new FrameAnalyzer();private final CostBadgeDetector badgeDetector=new CostBadgeDetector();private HandCardRecognizer handRecognizer;private final AudioProfileBank audioProfiles=new AudioProfileBank();private AudioEvidenceEngine audioEngine;
    private volatile AudioEvidenceEngine.Fingerprint latestAudio;private CostBadgeDetector.Detection latestBadge;private HandCardRecognizer.Result previousHand;
    private long lastAnalysisNs,lastEnemyEventMs,firstHudMs;private int hudStableFrames,hudMissingFrames;private boolean matchActive;private double smoothedElixir=Double.NaN,smoothedConfidence,stableMaxElixir=Double.NaN;

    @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences(PREFS,MODE_PRIVATE);createChannel();handRecognizer=new HandCardRecognizer(this);analysisThread=new HandlerThread("RoyaleVisionCV");analysisThread.start();analysisHandler=new Handler(analysisThread.getLooper());audioEngine=new AudioEvidenceEngine(this,new AudioEvidenceEngine.Listener(){
        @Override public void onAudioAvailability(boolean available){prefs.edit().putBoolean(K_AUDIO_AVAILABLE,available).apply();}
        @Override public void onTransient(AudioEvidenceEngine.Fingerprint f){latestAudio=f;prefs.edit().putLong(K_AUDIO_TRANSIENT_MS,f.timeMs).apply();}
    });}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        startCaptureForeground();if(projection!=null)return START_STICKY;if(intent==null){stopSelf();return START_NOT_STICKY;}
        int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,0);Intent resultData;
        if(Build.VERSION.SDK_INT>=33)resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent.class);else{ //noinspection deprecation
            resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA);}
        if(resultCode==0||resultData==null){writeStopped("Capture permission missing");stopSelf();return START_NOT_STICKY;}
        try{
            clearLiveDetectionState();handRecognizer.reloadDeck();
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);projection=manager.getMediaProjection(resultCode,resultData);
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){writeStopped("Screen capture stopped");stopSelf();}},analysisHandler);
            startVirtualDisplay();audioEngine.start(projection);
            prefs.edit().putBoolean(K_CAPTURE,true).putBoolean(K_MATCH,false).remove(K_LOCAL_ELIXIR).putFloat(K_ELIXIR_CONF,0f).putString(K_STATUS,"SEARCHING FOR BATTLE HUD").apply();
        }catch(RuntimeException e){writeStopped("Capture error: "+e.getClass().getSimpleName());stopSelf();}
        return START_STICKY;
    }

    private void startCaptureForeground(){Notification n=buildNotification();if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTIFICATION_ID,n);}
    private void startVirtualDisplay(){DisplayMetrics dm=new DisplayMetrics();WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE); //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(dm);int width=Math.max(360,dm.widthPixels),height=Math.max(640,dm.heightPixels),dpi=Math.max(160,dm.densityDpi);reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);reader.setOnImageAvailableListener(this::onImage,analysisHandler);virtualDisplay=projection.createVirtualDisplay("RoyaleVisionAutoCapture",width,height,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,analysisHandler);}

    private void onImage(ImageReader source){Image image=null;try{image=source.acquireLatestImage();if(image==null)return;long nowNs=System.nanoTime();if(nowNs-lastAnalysisNs<ANALYZE_EVERY_NS)return;lastAnalysisNs=nowNs;long nowMs=System.currentTimeMillis();
        HandCardRecognizer.Result hand=handRecognizer.recognize(image);CostBadgeDetector.Detection badge=badgeDetector.detect(image,nowMs);if(badge!=null&&badge.confidence>=0.54)latestBadge=badge;FrameAnalyzer.Result r=analyzer.analyze(image,nowMs);updateFromResult(r,hand,nowMs);previousHand=hand;
    }catch(RuntimeException ignored){}finally{if(image!=null)image.close();}}

    private void updateFromResult(FrameAnalyzer.Result r,HandCardRecognizer.Result hand,long nowMs){
        boolean reliable=r.battleHud&&!Double.isNaN(r.localElixir)&&r.localElixir>=0.35&&r.elixirConfidence>=0.34;
        if(reliable){if(Double.isNaN(smoothedElixir))smoothedElixir=r.localElixir;else{double a=r.localElixir<smoothedElixir-0.45?0.78:0.30;smoothedElixir=smoothedElixir*(1-a)+r.localElixir*a;}smoothedConfidence=smoothedConfidence*0.68+r.elixirConfidence*0.32;if(hudStableFrames==0){firstHudMs=nowMs;stableMaxElixir=smoothedElixir;}hudStableFrames++;hudMissingFrames=0;stableMaxElixir=Double.isNaN(stableMaxElixir)?smoothedElixir:Math.max(stableMaxElixir,smoothedElixir);}else{hudMissingFrames++;if(!matchActive){hudStableFrames=Math.max(0,hudStableFrames-2);if(hudStableFrames==0){firstHudMs=0;stableMaxElixir=Double.NaN;}if(hudMissingFrames>=6){smoothedElixir=Double.NaN;smoothedConfidence=0;}}else hudStableFrames=Math.max(0,hudStableFrames-1);}

        if(!matchActive&&hudStableFrames>=START_STABLE_FRAMES&&smoothedConfidence>=0.34&&!Double.isNaN(stableMaxElixir)&&stableMaxElixir>=0.45){matchActive=true;int session=prefs.getInt(K_SESSION,0)+1;long clockStart=firstHudMs>0?firstHudMs:nowMs;double anchor=Math.max(0.45,Math.min(10,stableMaxElixir));audioProfiles.clear();lastEnemyEventMs=0;prefs.edit().putInt(K_SESSION,session).putLong(K_MATCH_CLOCK_START_MS,clockStart).putLong(K_MATCH_ANCHOR_MS,nowMs).putFloat(K_MATCH_ANCHOR_ELIXIR,(float)anchor).apply();}
        else if(matchActive&&hudMissingFrames>=END_MISSING_FRAMES){matchActive=false;analyzer.resetTemporalState();badgeDetector.reset();previousHand=null;hudStableFrames=hudMissingFrames=0;firstHudMs=0;stableMaxElixir=Double.NaN;smoothedElixir=Double.NaN;smoothedConfidence=0;audioProfiles.clear();}

        SharedPreferences.Editor e=prefs.edit().putBoolean(K_CAPTURE,true).putBoolean(K_MATCH,matchActive).putFloat(K_ELIXIR_CONF,(float)smoothedConfidence).putFloat(K_ARENA_CHANGE,(float)r.arenaChange).putString(K_HINT,r.effectHint==null?"":r.effectHint);
        if(matchActive&&!Double.isNaN(smoothedElixir))e.putFloat(K_LOCAL_ELIXIR,(float)smoothedElixir);else if(!matchActive)e.remove(K_LOCAL_ELIXIR);

        String localCard=null;
        if(r.handChanged&&matchActive)e.putLong(K_HAND_CHANGE_MS,nowMs);
        if(r.localPlay&&matchActive){e.putLong(K_LOCAL_PLAY_MS,nowMs);localCard=HandCardRecognizer.inferPlayedCard(previousHand,hand);if(localCard!=null){e.putString(K_LOCAL_CARD_ID,localCard);AudioEvidenceEngine.Fingerprint a=nearAudio(nowMs,850);if(a!=null)audioProfiles.learn(localCard,a);}}
        if(r.localAbilitySpend&&matchActive)e.putLong(K_LOCAL_ABILITY_MS,nowMs);

        boolean emitted=false;int enemyCost=0;String enemyCard=null;double enemyConf=0;
        if(r.enemyCandidate&&matchActive&&nowMs-lastEnemyEventMs>800){
            e.putLong(K_ENEMY_CANDIDATE_MS,nowMs);CostBadgeDetector.Detection b=latestBadge;boolean badgeNear=b!=null&&Math.abs(nowMs-b.timeMs)<=850&&b.confidence>=0.58;
            AudioEvidenceEngine.Fingerprint a=nearAudio(nowMs,700);boolean audioNear=a!=null;
            if(badgeNear){enemyCost=b.cost;enemyConf=0.62+Math.min(0.25,b.confidence*0.22)+(audioNear?0.10:0);enemyCard=audioProfiles.match(a,enemyCost);if(enemyCard!=null)enemyConf=Math.max(enemyConf,0.88);emitted=true;}
            // No cost badge = no automatic Elixir mutation. Audio is only supporting evidence.
        }
        if(emitted){lastEnemyEventMs=nowMs;e.putLong(K_ENEMY_EVENT_MS,nowMs).putInt(K_ENEMY_COST,enemyCost).putFloat(K_ENEMY_CONF,(float)Math.min(0.99,enemyConf));if(enemyCard!=null)e.putString(K_ENEMY_CARD_ID,enemyCard);else e.remove(K_ENEMY_CARD_ID);}

        String status;if(!matchActive)status=hudStableFrames>0?"VERIFYING MATCH "+hudStableFrames+"/"+START_STABLE_FRAMES:"SEARCHING FOR BATTLE HUD";else if(r.localPlay)status=localCard==null?"YOUR PLAY • HAND+ELIXIR":"YOUR "+localCard.toUpperCase()+" • LEARNING SOUND";else if(emitted)status=enemyCard==null?"AUTO ENEMY SPEND −"+enemyCost:"AUTO ENEMY "+enemyCard.toUpperCase();else if(r.enemyCandidate)status="ENEMY CANDIDATE • WAITING FOR COST CONFIRM";else status=prefs.getBoolean(K_AUDIO_AVAILABLE,false)?"AUTO WATCHING • SOUND+HAND+VISION":"AUTO WATCHING • HAND+VISION";
        e.putString(K_STATUS,status).apply();
    }

    private AudioEvidenceEngine.Fingerprint nearAudio(long now,long window){AudioEvidenceEngine.Fingerprint a=latestAudio;return a!=null&&Math.abs(now-a.timeMs)<=window?a:null;}
    private void clearLiveDetectionState(){matchActive=false;hudStableFrames=hudMissingFrames=0;firstHudMs=0;stableMaxElixir=Double.NaN;smoothedElixir=Double.NaN;smoothedConfidence=0;lastEnemyEventMs=0;latestAudio=null;latestBadge=null;previousHand=null;audioProfiles.clear();analyzer.resetTemporalState();badgeDetector.reset();}

    private Notification buildNotification(){Intent open=new Intent(this,AutoMainActivity.class);PendingIntent openPi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Intent stop=new Intent(this,AutoCaptureService.class).setAction(ACTION_STOP);PendingIntent stopPi=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setContentTitle("RoyaleVision Auto v5.2").setContentText("Sound + calibrated hand + vision fusion").setSmallIcon(R.drawable.ic_notification_elixir).setContentIntent(openPi).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"Stop",stopPi).build()).build();}
    private void createChannel(){if(Build.VERSION.SDK_INT<26)return;NotificationChannel c=new NotificationChannel(CHANNEL,"RoyaleVision automatic capture",NotificationManager.IMPORTANCE_LOW);c.setDescription("On-device screen and optional playback-audio analysis");c.setShowBadge(false);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}
    private void writeStopped(String status){if(prefs!=null)prefs.edit().putBoolean(K_CAPTURE,false).putBoolean(K_MATCH,false).putBoolean(K_AUDIO_AVAILABLE,false).remove(K_LOCAL_ELIXIR).putFloat(K_ELIXIR_CONF,0f).putString(K_STATUS,status).apply();}
    @Override public void onDestroy(){writeStopped("AUTO STOPPED");if(audioEngine!=null)audioEngine.stop();if(reader!=null)try{reader.setOnImageAvailableListener(null,null);}catch(RuntimeException ignored){}if(virtualDisplay!=null){try{virtualDisplay.release();}catch(RuntimeException ignored){}virtualDisplay=null;}if(projection!=null){try{projection.stop();}catch(RuntimeException ignored){}projection=null;}if(reader!=null){try{reader.close();}catch(RuntimeException ignored){}reader=null;}if(analysisThread!=null)analysisThread.quitSafely();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
