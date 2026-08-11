package com.huseyn.elixircollector;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures game playback when Android and the source app permit it. It emits
 * short transient fingerprints used only as supporting evidence.
 */
public final class AudioEvidenceEngine {
    public static final class Fingerprint {
        public final long timeMs;
        public final double strength;
        public final double zcr;
        public final double brightness;
        Fingerprint(long t,double s,double z,double b){timeMs=t;strength=s;zcr=z;brightness=b;}
    }

    public interface Listener {
        void onAudioAvailability(boolean available);
        void onTransient(Fingerprint fingerprint);
    }

    private static final int SAMPLE_RATE=16000;
    private static final int WINDOW=400; // 25 ms
    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private AudioRecord record;
    private Thread thread;
    private double noiseFloor=280.0;
    private long lastTransientMs;

    public AudioEvidenceEngine(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}

    public boolean start(MediaProjection projection){
        stop();
        if(Build.VERSION.SDK_INT<29||projection==null||context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){listener.onAudioAvailability(false);return false;}
        try{
            AudioPlaybackCaptureConfiguration cfg=new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            record=new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min,WINDOW*2*10)).setAudioPlaybackCaptureConfig(cfg).build();
            record.startRecording();
            if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING){stop();listener.onAudioAvailability(false);return false;}
            running.set(true);thread=new Thread(this::loop,"RoyaleVisionAudio");thread.start();listener.onAudioAvailability(true);return true;
        }catch(RuntimeException e){stop();listener.onAudioAvailability(false);return false;}
    }

    private void loop(){
        short[] b=new short[WINDOW];
        while(running.get()){
            int n;try{n=record.read(b,0,b.length,AudioRecord.READ_BLOCKING);}catch(RuntimeException e){break;}
            if(n<=0)continue;
            double ss=0,peak=0,diff=0;int crossings=0;short prev=b[0];
            for(int i=0;i<n;i++){
                double v=b[i];ss+=v*v;peak=Math.max(peak,Math.abs(v));
                if(i>0){diff+=Math.abs(v-prev);if((v>=0)!=(prev>=0))crossings++;prev=b[i];}
            }
            double rms=Math.sqrt(ss/Math.max(1,n));
            if(rms<noiseFloor*1.55)noiseFloor=noiseFloor*0.94+rms*0.06;else noiseFloor=noiseFloor*0.995+rms*0.005;
            noiseFloor=Math.max(70,Math.min(12000,noiseFloor));
            double ratio=rms/Math.max(100,noiseFloor);long now=System.currentTimeMillis();
            if(rms>=650&&peak>=1700&&ratio>=2.0&&now-lastTransientMs>=160){
                lastTransientMs=now;
                double zcr=crossings/(double)Math.max(1,n-1);
                double brightness=diff/(Math.max(1,n-1)*Math.max(500.0,rms));
                listener.onTransient(new Fingerprint(now,Math.min(6.0,ratio),Math.min(1.0,zcr),Math.min(4.0,brightness)));
            }
        }
        listener.onAudioAvailability(false);
    }

    public void stop(){
        running.set(false);
        if(record!=null){try{record.stop();}catch(RuntimeException ignored){}try{record.release();}catch(RuntimeException ignored){}record=null;}
        if(thread!=null){try{thread.join(180);}catch(InterruptedException e){Thread.currentThread().interrupt();}thread=null;}
    }

    public static double distance(Fingerprint a,Fingerprint b){
        if(a==null||b==null)return Double.POSITIVE_INFINITY;
        double ds=(Math.log1p(a.strength)-Math.log1p(b.strength))/1.6;
        double dz=(a.zcr-b.zcr)/0.20;
        double db=(a.brightness-b.brightness)/0.75;
        return Math.sqrt(ds*ds+dz*dz+db*db);
    }
}
