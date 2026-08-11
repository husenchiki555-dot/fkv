package com.huseyn.elixircollector;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * AI-less local computer-vision heuristics.
 *
 * It deliberately does NOT claim exact opponent-card identity. Its job is to:
 * 1) detect the battle HUD from the local Elixir bar + four-card hand;
 * 2) estimate the local visible Elixir bar;
 * 3) detect hand-slot changes;
 * 4) pair hand change + Elixir drop as a strong LOCAL card-play signal;
 * 5) detect arena-change spikes and suppress them around LOCAL plays, leaving a
 *    higher-quality opponent-action candidate stream.
 */
public final class FrameAnalyzer {
    public static final class Result {
        public boolean battleHud;
        public double localElixir = Double.NaN;
        public double elixirConfidence;
        public boolean handChanged;
        public double handChangeScore;
        public boolean localPlay;
        public boolean localAbilitySpend;
        public boolean arenaSpike;
        public double arenaChange;
        public boolean enemyCandidate;
        public String effectHint = "";
    }

    private static final int HAND_SLOTS = 4;
    private static final int HASH_W = 5;
    private static final int HASH_H = 6;
    private static final int HASH_VALUES = HASH_W * HASH_H * 3;

    private final double[][] previousHand = new double[HAND_SLOTS][HASH_VALUES];
    private boolean handInitialized;
    private double[] previousArena;
    private double arenaBaseline = 0.035;
    private double previousElixir = Double.NaN;
    private long lastHandChangeMs;
    private long lastLocalPlayMs;
    private long lastEnemyCandidateMs;

    public Result analyze(Image image, long nowMs) {
        Result out = new Result();
        if (image == null || image.getPlanes().length == 0) return out;

        PlaneReader p = new PlaneReader(image);
        if (!p.valid()) return out;
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 200 || h < 300) return out;

        ElixirReading er = readElixir(p, w, h);
        out.localElixir = er.value;
        out.elixirConfidence = er.confidence;

        HandReading hr = readHand(p, w, h);
        out.handChangeScore = hr.maxDelta;
        out.battleHud = er.confidence >= 0.26 && hr.texture >= 0.045;

        if (handInitialized && hr.maxDelta >= 0.115 && nowMs - lastHandChangeMs > 430) {
            out.handChanged = true;
            lastHandChangeMs = nowMs;
        }
        copyHand(hr.hashes);
        handInitialized = true;

        if (!Double.isNaN(er.value) && !Double.isNaN(previousElixir)
                && er.confidence >= 0.28) {
            double drop = previousElixir - er.value;
            if (drop >= 0.55 && drop <= 9.5) {
                boolean handNear = out.handChanged || nowMs - lastHandChangeMs <= 900;
                if (handNear) {
                    out.localPlay = true;
                    lastLocalPlayMs = nowMs;
                } else {
                    out.localAbilitySpend = true;
                    lastLocalPlayMs = nowMs;
                }
            }
        }
        if (!Double.isNaN(er.value) && er.confidence >= 0.22) {
            if (Double.isNaN(previousElixir)) previousElixir = er.value;
            else previousElixir = previousElixir * 0.58 + er.value * 0.42;
        }

        ArenaReading ar = readArena(p, w, h);
        out.arenaChange = ar.change;
        double threshold = Math.max(0.075, arenaBaseline * 1.85 + 0.020);
        out.arenaSpike = ar.change > threshold;
        // Adapt slowly so sustained troop motion does not continuously fire.
        arenaBaseline = arenaBaseline * 0.94 + Math.min(0.20, ar.change) * 0.06;

        boolean localGuard = nowMs - lastLocalPlayMs <= 1050
                || nowMs - lastHandChangeMs <= 850;
        if (out.battleHud && out.arenaSpike && !localGuard
                && nowMs - lastEnemyCandidateMs > 720) {
            out.enemyCandidate = true;
            lastEnemyCandidateMs = nowMs;
            out.effectHint = ar.effectHint;
        }
        return out;
    }

    private ElixirReading readElixir(PlaneReader p, int w, int h) {
        // The exact HUD scales between devices, so search for the strongest
        // long purple horizontal run in the lower portion rather than using one pixel rectangle.
        int x0 = (int)(w * 0.035);
        int x1 = (int)(w * 0.965);
        int y0 = (int)(h * 0.835);
        int y1 = (int)(h * 0.985);
        int stepX = Math.max(2, w / 360);
        int stepY = Math.max(2, h / 650);

        int bestY = -1;
        int bestPurple = 0;
        int totalX = Math.max(1, (x1 - x0) / stepX);
        for (int y = y0; y < y1; y += stepY) {
            int count = 0;
            for (int x = x0; x < x1; x += stepX) {
                int rgb = p.rgb(x, y);
                if (isElixirPurple(rgb)) count++;
            }
            if (count > bestPurple) {
                bestPurple = count;
                bestY = y;
            }
        }
        if (bestY < 0 || bestPurple < Math.max(8, totalX / 35)) {
            return new ElixirReading(Double.NaN, 0.0);
        }

        // Work on x-columns near the best row. The real bar is contiguous; card art tends
        // to create short disconnected purple islands, so require column support and use
        // the longest substantially supported run from the left-side bar region.
        int barStart = (int)(w * 0.080);
        int barEnd = (int)(w * 0.945);
        int band = Math.max(3, h / 300);
        int samplesY = 0;
        for (int yy = bestY - band; yy <= bestY + band; yy += Math.max(1, band / 2)) samplesY++;

        int columns = Math.max(1, (barEnd - barStart) / stepX);
        boolean[] purple = new boolean[columns];
        int idx = 0;
        for (int x = barStart; x < barEnd && idx < columns; x += stepX, idx++) {
            int hits = 0;
            for (int yy = bestY - band; yy <= bestY + band; yy += Math.max(1, band / 2)) {
                if (isElixirPurple(p.rgb(x, clamp(yy, 0, h - 1)))) hits++;
            }
            purple[idx] = hits >= Math.max(1, samplesY / 3);
        }

        // Close tiny gaps (segment borders/text anti-aliasing).
        for (int i = 1; i < purple.length - 1; i++) {
            if (!purple[i] && purple[i - 1] && purple[i + 1]) purple[i] = true;
        }

        int first = -1;
        for (int i = 0; i < purple.length; i++) {
            if (purple[i]) { first = i; break; }
        }
        if (first < 0) return new ElixirReading(Double.NaN, 0.0);

        int last = first;
        int gap = 0;
        for (int i = first; i < purple.length; i++) {
            if (purple[i]) {
                last = i;
                gap = 0;
            } else {
                gap++;
                if (gap > 5) break;
            }
        }

        // Fixed normalized bar geometry is only used after dynamically finding its row.
        // Value is smoothed in CaptureService; this is intentionally conservative.
        double fill = (last + 1.0) / purple.length;
        double value = clamp(fill * 10.0, 0.0, 10.0);
        double runFrac = (last - first + 1.0) / purple.length;
        double rowFrac = bestPurple / (double)totalX;
        double confidence = clamp(rowFrac * 1.7 + runFrac * 0.65, 0.0, 1.0);
        return new ElixirReading(value, confidence);
    }

    private HandReading readHand(PlaneReader p, int w, int h) {
        // Four card slots. We do not identify the card here; we fingerprint its visual content.
        // If any slot materially changes while visible, that is strong evidence the LOCAL hand changed.
        double[] centers = {0.365, 0.515, 0.665, 0.815};
        double[][] hashes = new double[HAND_SLOTS][HASH_VALUES];
        double textureSum = 0.0;
        double maxDelta = 0.0;
        for (int slot = 0; slot < HAND_SLOTS; slot++) {
            double cx = centers[slot];
            double left = cx - 0.064;
            double right = cx + 0.064;
            double top = 0.755;
            double bottom = 0.900;
            int pos = 0;
            double mean = 0.0;
            double meanSq = 0.0;
            int count = 0;
            for (int gy = 0; gy < HASH_H; gy++) {
                for (int gx = 0; gx < HASH_W; gx++) {
                    int x = (int)(w * (left + (gx + 0.5) / HASH_W * (right - left)));
                    int y = (int)(h * (top + (gy + 0.5) / HASH_H * (bottom - top)));
                    int rgb = p.rgb(clamp(x,0,w-1), clamp(y,0,h-1));
                    double r = ((rgb >> 16) & 255) / 255.0;
                    double g = ((rgb >> 8) & 255) / 255.0;
                    double b = (rgb & 255) / 255.0;
                    hashes[slot][pos++] = r;
                    hashes[slot][pos++] = g;
                    hashes[slot][pos++] = b;
                    double lum = (r + g + b) / 3.0;
                    mean += lum;
                    meanSq += lum * lum;
                    count++;
                }
            }
            mean /= Math.max(1, count);
            meanSq /= Math.max(1, count);
            textureSum += Math.sqrt(Math.max(0.0, meanSq - mean * mean));
            if (handInitialized) {
                double delta = 0.0;
                for (int i = 0; i < HASH_VALUES; i++) {
                    delta += Math.abs(hashes[slot][i] - previousHand[slot][i]);
                }
                delta /= HASH_VALUES;
                maxDelta = Math.max(maxDelta, delta);
            }
        }
        return new HandReading(hashes, textureSum / HAND_SLOTS, maxDelta);
    }

    private ArenaReading readArena(PlaneReader p, int w, int h) {
        final int cols = 18;
        final int rows = 24;
        double[] now = new double[cols * rows * 3];
        int pos = 0;
        double changedR = 0, changedG = 0, changedB = 0;
        int changed = 0;
        int total = 0;
        for (int gy = 0; gy < rows; gy++) {
            double ny = 0.105 + (gy + 0.5) / rows * 0.625;
            for (int gx = 0; gx < cols; gx++) {
                double nx = 0.055 + (gx + 0.5) / cols * 0.890;
                int rgb = p.rgb((int)(w * nx), (int)(h * ny));
                double r = ((rgb >> 16) & 255) / 255.0;
                double g = ((rgb >> 8) & 255) / 255.0;
                double b = (rgb & 255) / 255.0;
                now[pos] = r; now[pos + 1] = g; now[pos + 2] = b;
                if (previousArena != null && previousArena.length == now.length) {
                    double d = Math.abs(r - previousArena[pos])
                            + Math.abs(g - previousArena[pos + 1])
                            + Math.abs(b - previousArena[pos + 2]);
                    if (d > 0.31) {
                        changed++;
                        changedR += r;
                        changedG += g;
                        changedB += b;
                    }
                    total++;
                }
                pos += 3;
            }
        }
        previousArena = now;
        if (total == 0) return new ArenaReading(0.0, "ARENA CHANGE");
        double frac = changed / (double)total;
        String hint = "DEPLOYMENT / ARENA CHANGE";
        if (changed > 2) {
            double r = changedR / changed;
            double g = changedG / changed;
            double b = changedB / changed;
            if (b > 0.58 && b > g * 1.12 && (r + b) > 1.05) {
                hint = "ELECTRIC / ICE-LIKE VFX";
            } else if (r > 0.50 && b > 0.42 && g < (r + b) * 0.42) {
                hint = "RAGE / POISON-LIKE VFX";
            } else if (r > 0.58 && r > g * 1.10 && g > b * 1.08) {
                hint = "FIRE / EXPLOSION-LIKE VFX";
            }
        }
        return new ArenaReading(frac, hint);
    }

    private void copyHand(double[][] hashes) {
        for (int s = 0; s < HAND_SLOTS; s++) {
            System.arraycopy(hashes[s], 0, previousHand[s], 0, HASH_VALUES);
        }
    }

    private static boolean isElixirPurple(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return r >= 82 && b >= 105 && g <= 170
                && r > g * 1.04 && b > g * 1.08
                && (r + b - 2 * g) > 45;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final class ElixirReading {
        final double value, confidence;
        ElixirReading(double value, double confidence) { this.value = value; this.confidence = confidence; }
    }
    private static final class HandReading {
        final double[][] hashes;
        final double texture;
        final double maxDelta;
        HandReading(double[][] hashes, double texture, double maxDelta) {
            this.hashes = hashes; this.texture = texture; this.maxDelta = maxDelta;
        }
    }
    private static final class ArenaReading {
        final double change;
        final String effectHint;
        ArenaReading(double change, String effectHint) { this.change = change; this.effectHint = effectHint; }
    }

    private static final class PlaneReader {
        final ByteBuffer buffer;
        final int rowStride;
        final int pixelStride;
        final int width;
        final int height;

        PlaneReader(Image image) {
            Image.Plane plane = image.getPlanes()[0];
            buffer = plane.getBuffer();
            rowStride = plane.getRowStride();
            pixelStride = plane.getPixelStride();
            width = image.getWidth();
            height = image.getHeight();
        }

        boolean valid() { return buffer != null && pixelStride >= 4 && rowStride > 0; }

        int rgb(int x, int y) {
            x = clamp(x, 0, width - 1);
            y = clamp(y, 0, height - 1);
            int index = y * rowStride + x * pixelStride;
            if (index < 0 || index + 2 >= buffer.limit()) return 0;
            int r = buffer.get(index) & 255;
            int g = buffer.get(index + 1) & 255;
            int b = buffer.get(index + 2) & 255;
            return (r << 16) | (g << 8) | b;
        }
    }
}
