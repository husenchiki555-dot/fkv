package com.huseyn.elixircollector;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures game playback when Android/the source app permit it and emits only
 * timing evidence (audio transients). It never commits a card by itself.
 */
public final class AudioEvidenceEngine {
    public interface Listener {
        void onAudioAvailability(boolean available);
        void onTransient(long timeMs, double strength);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW_SAMPLES = 320; // 20 ms
    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord record;
    private Thread thread;
    private double noiseFloor = 300.0;
    private long lastTransientMs;

    public AudioEvidenceEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean start(MediaProjection projection) {
        stop();
        if (Build.VERSION.SDK_INT < 29 || projection == null) {
            listener.onAudioAvailability(false);
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onAudioAvailability(false);
            return false;
        }
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(min, WINDOW_SAMPLES * 2 * 8);
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                stop();
                listener.onAudioAvailability(false);
                return false;
            }
            running.set(true);
            thread = new Thread(this::loop, "RoyaleVisionAudio");
            thread.start();
            listener.onAudioAvailability(true);
            return true;
        } catch (RuntimeException e) {
            stop();
            listener.onAudioAvailability(false);
            return false;
        }
    }

    private void loop() {
        short[] buffer = new short[WINDOW_SAMPLES];
        int quietWindows = 0;
        while (running.get()) {
            int n;
            try { n = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING); }
            catch (RuntimeException e) { break; }
            if (n <= 0) continue;
            double sumSq = 0.0;
            double peak = 0.0;
            for (int i=0;i<n;i++) {
                double v = buffer[i];
                sumSq += v*v;
                peak = Math.max(peak, Math.abs(v));
            }
            double rms = Math.sqrt(sumSq / Math.max(1,n));
            // Update the baseline much faster in quiet windows than during spikes.
            if (rms < noiseFloor * 1.55) {
                noiseFloor = noiseFloor * 0.94 + rms * 0.06;
                quietWindows++;
            } else {
                noiseFloor = noiseFloor * 0.995 + rms * 0.005;
                quietWindows = 0;
            }
            noiseFloor = Math.max(75.0, Math.min(12000.0, noiseFloor));
            long now = System.currentTimeMillis();
            double ratio = rms / Math.max(100.0, noiseFloor);
            boolean transientEvent = rms >= 700.0
                    && peak >= 1800.0
                    && ratio >= 2.15
                    && now - lastTransientMs >= 170L;
            if (transientEvent) {
                lastTransientMs = now;
                listener.onTransient(now, Math.min(6.0, ratio));
            }
        }
        listener.onAudioAvailability(false);
    }

    public void stop() {
        running.set(false);
        if (record != null) {
            try { record.stop(); } catch (RuntimeException ignored) {}
            try { record.release(); } catch (RuntimeException ignored) {}
            record = null;
        }
        if (thread != null) {
            try { thread.join(180); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            thread = null;
        }
    }
}
