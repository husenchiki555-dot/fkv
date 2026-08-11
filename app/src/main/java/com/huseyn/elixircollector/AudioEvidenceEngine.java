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

/** Optional playback-audio transient capture. Failure never propagates into screen capture. */
public final class AudioEvidenceEngine {
    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW = 512;
    private static final double[] FREQUENCIES = {250, 450, 700, 1050, 1550, 2350, 3600, 5600};

    public static final class Fingerprint {
        public final long timeMs;
        public final double strength;
        public final double zcr;
        public final double brightness;
        public final double[] bands;

        public Fingerprint(long timeMs, double strength, double zcr,
                           double brightness, double[] bands) {
            this.timeMs = timeMs;
            this.strength = strength;
            this.zcr = zcr;
            this.brightness = brightness;
            this.bands = bands == null ? new double[0] : bands.clone();
        }
    }

    public interface Listener {
        void onAudioAvailability(boolean available);
        void onTransient(Fingerprint fingerprint);
    }

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord record;
    private Thread thread;
    private double noiseFloor = 280.0;
    private long lastTransientMs;

    public AudioEvidenceEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean start(MediaProjection projection) {
        stop();
        if (Build.VERSION.SDK_INT < 29 || projection == null
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onAudioAvailability(false);
            return false;
        }
        try {
            AudioPlaybackCaptureConfiguration configuration =
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
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min, WINDOW * 2 * 10))
                    .setAudioPlaybackCaptureConfig(configuration)
                    .build();
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                stop();
                listener.onAudioAvailability(false);
                return false;
            }
            running.set(true);
            thread = new Thread(this::loop, "RoyaleVisionAudio");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
            listener.onAudioAvailability(true);
            return true;
        } catch (RuntimeException error) {
            stop();
            listener.onAudioAvailability(false);
            return false;
        }
    }

    private void loop() {
        short[] samples = new short[WINDOW];
        while (running.get()) {
            int n;
            try {
                AudioRecord local = record;
                if (local == null) break;
                n = local.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException error) {
                break;
            }
            if (n <= 0) continue;
            double energy = 0.0, peak = 0.0, diff = 0.0;
            int crossings = 0;
            short previous = samples[0];
            for (int i = 0; i < n; i++) {
                double v = samples[i];
                energy += v * v;
                peak = Math.max(peak, Math.abs(v));
                if (i > 0) {
                    diff += Math.abs(v - previous);
                    if ((v >= 0) != (previous >= 0)) crossings++;
                    previous = samples[i];
                }
            }
            double rms = Math.sqrt(energy / Math.max(1, n));
            if (rms < noiseFloor * 1.55) noiseFloor = noiseFloor * 0.94 + rms * 0.06;
            else noiseFloor = noiseFloor * 0.995 + rms * 0.005;
            noiseFloor = ColorMath.clamp(noiseFloor, 70.0, 12000.0);
            double ratio = rms / Math.max(100.0, noiseFloor);
            long now = System.currentTimeMillis();
            if (rms >= 580 && peak >= 1550 && ratio >= 1.90 && now - lastTransientMs >= 145) {
                lastTransientMs = now;
                double zcr = crossings / (double)Math.max(1, n - 1);
                double brightness = diff / (Math.max(1, n - 1) * Math.max(500.0, rms));
                double[] bands = spectralBands(samples, n);
                listener.onTransient(new Fingerprint(now, Math.min(6.0, ratio),
                        Math.min(1.0, zcr), Math.min(4.0, brightness), bands));
            }
        }
        running.set(false);
        listener.onAudioAvailability(false);
    }

    private static double[] spectralBands(short[] samples, int n) {
        double[] out = new double[FREQUENCIES.length];
        double sum = 0.0;
        for (int k = 0; k < FREQUENCIES.length; k++) {
            double omega = 2.0 * Math.PI * FREQUENCIES[k] / SAMPLE_RATE;
            double coeff = 2.0 * Math.cos(omega), q0 = 0.0, q1 = 0.0, q2 = 0.0;
            for (int i = 0; i < n; i++) {
                double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, n - 1));
                q0 = coeff * q1 - q2 + samples[i] * window;
                q2 = q1;
                q1 = q0;
            }
            double power = Math.max(0.0, q1 * q1 + q2 * q2 - coeff * q1 * q2);
            out[k] = Math.log1p(power);
            sum += out[k];
        }
        if (sum > 1e-9) for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    public void stop() {
        running.set(false);
        AudioRecord local = record;
        record = null;
        if (local != null) {
            try { local.stop(); } catch (RuntimeException ignored) {}
            try { local.release(); } catch (RuntimeException ignored) {}
        }
        Thread localThread = thread;
        thread = null;
        if (localThread != null && localThread != Thread.currentThread()) {
            try { localThread.join(180); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    public static double distance(Fingerprint a, Fingerprint b) {
        if (a == null || b == null) return Double.POSITIVE_INFINITY;
        double ds = (Math.log1p(a.strength) - Math.log1p(b.strength)) / 1.6;
        double dz = (a.zcr - b.zcr) / 0.20;
        double db = (a.brightness - b.brightness) / 0.75;
        double spectral = 0.0;
        int n = Math.min(a.bands.length, b.bands.length);
        for (int i = 0; i < n; i++) {
            double d = a.bands[i] - b.bands[i];
            spectral += d * d;
        }
        spectral = n == 0 ? 0.8 : Math.sqrt(spectral / n) / 0.11;
        return Math.sqrt(ds * ds * 0.16 + dz * dz * 0.18 + db * db * 0.16 + spectral * spectral * 0.50);
    }
}
