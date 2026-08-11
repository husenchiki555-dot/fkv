package com.huseyn.elixircollector;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Local classical-CV analyzer for RoyaleVision Auto.
 *
 * Hard rule: this class emits observations only. It never mutates opponent
 * deck/cycle/elixir state and never equates ENTITY_APPEARED with CARD_COMMIT.
 */
public final class FrameAnalyzer {
    public static final class Result {
        public boolean battleHud;
        public double localElixir = Double.NaN;
        public double elixirConfidence;
        public double elixirGeometryScore;
        public boolean handChanged;
        public int handChangedSlots;
        public double handChangeScore;
        public double handTexture;
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
    private double previousElixirConfidence;
    private long lastHandChangeMs;
    private long lastLocalPlayMs;
    private long lastEnemyCandidateMs;

    /** Call when leaving a battle or after a long HUD loss. */
    public void resetTemporalState() {
        handInitialized = false;
        previousArena = null;
        arenaBaseline = 0.035;
        previousElixir = Double.NaN;
        previousElixirConfidence = 0.0;
        lastHandChangeMs = 0L;
        lastLocalPlayMs = 0L;
        lastEnemyCandidateMs = 0L;
        for (int s = 0; s < HAND_SLOTS; s++) {
            for (int i = 0; i < HASH_VALUES; i++) previousHand[s][i] = 0.0;
        }
    }

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
        out.elixirGeometryScore = er.geometryScore;

        HandReading hr = readHand(p, w, h);
        out.handChangeScore = hr.maxDelta;
        out.handChangedSlots = hr.changedSlots;
        out.handTexture = hr.texture;

        // Deliberately strict. The old build accepted purple card-cost circles as
        // an Elixir bar, then started a false match at ~0.0 Elixir.
        out.battleHud = !Double.isNaN(er.value)
                && er.value >= 0.45
                && er.confidence >= 0.42
                && er.geometryScore >= 0.45
                && hr.texture >= 0.045
                && hr.validSlots >= 3;

        if (out.battleHud && handInitialized
                && hr.maxDelta >= 0.105
                && hr.changedSlots >= 1
                && nowMs - lastHandChangeMs > 430) {
            out.handChanged = true;
            lastHandChangeMs = nowMs;
        }

        // Only update hand history while the real battle HUD is present. This
        // prevents menu -> arena transitions from looking like a local card play.
        if (out.battleHud) {
            copyHand(hr.hashes);
            handInitialized = true;
        }

        if (out.battleHud
                && !Double.isNaN(er.value)
                && !Double.isNaN(previousElixir)
                && er.confidence >= 0.42
                && previousElixirConfidence >= 0.38) {
            double drop = previousElixir - er.value;
            if (drop >= 0.55 && drop <= 9.5) {
                boolean handNear = out.handChanged || nowMs - lastHandChangeMs <= 950;
                if (handNear) {
                    out.localPlay = true;
                    lastLocalPlayMs = nowMs;
                } else {
                    // Hero/Champion ability or another local non-card spend.
                    out.localAbilitySpend = true;
                    lastLocalPlayMs = nowMs;
                }
            }
        }

        if (out.battleHud && !Double.isNaN(er.value) && er.confidence >= 0.38) {
            previousElixir = er.value;
            previousElixirConfidence = er.confidence;
        }

        if (out.battleHud) {
            ArenaReading ar = readArena(p, w, h);
            out.arenaChange = ar.change;
            double threshold = Math.max(0.080, arenaBaseline * 1.90 + 0.021);
            out.arenaSpike = ar.change > threshold;
            arenaBaseline = arenaBaseline * 0.945 + Math.min(0.20, ar.change) * 0.055;

            boolean localGuard = out.localPlay || out.localAbilitySpend
                    || nowMs - lastLocalPlayMs <= 1150
                    || nowMs - lastHandChangeMs <= 900;
            if (out.arenaSpike && !localGuard && !out.handChanged
                    && nowMs - lastEnemyCandidateMs > 850) {
                out.enemyCandidate = true;
                lastEnemyCandidateMs = nowMs;
                out.effectHint = ar.effectHint;
            }
        }
        return out;
    }

    /**
     * Read the purple fill of the real Elixir rail.
     *
     * v5 searched almost the entire lower screen and normalized the fill against
     * ~86% of screen width. That made the card-cost bubbles win the search on
     * some devices and produced a false 0.x reading. v5.1 searches only plausible
     * rail geometry below the hand, requires a long left-anchored run, and
     * normalizes from the detected run start to the rail end.
     */
    private ElixirReading readElixir(PlaneReader p, int w, int h) {
        double[][] profiles = {
                {0.30, 0.905, 0.905, 0.992},
                {0.34, 0.915, 0.905, 0.992},
                {0.37, 0.900, 0.900, 0.992}
        };
        ElixirReading best = new ElixirReading(Double.NaN, 0.0, 0.0);
        for (double[] profile : profiles) {
            ElixirReading r = readElixirProfile(p, w, h,
                    profile[0], profile[1], profile[2], profile[3]);
            if (r.confidence > best.confidence) best = r;
        }
        return best;
    }

    private ElixirReading readElixirProfile(PlaneReader p, int w, int h,
                                             double nx0, double nx1,
                                             double ny0, double ny1) {
        int x0 = clamp((int)(w * nx0), 0, w - 1);
        int x1 = clamp((int)(w * nx1), x0 + 2, w);
        int y0 = clamp((int)(h * ny0), 0, h - 1);
        int y1 = clamp((int)(h * ny1), y0 + 2, h);
        int stepX = Math.max(1, w / 720);
        int stepY = Math.max(1, h / 1200);
        int columns = Math.max(1, (x1 - x0) / stepX);

        int bestY = -1;
        double bestScore = 0.0;
        int bestLongest = 0;
        int bestPurple = 0;
        for (int y = y0; y < y1; y += stepY) {
            int longest = 0;
            int current = 0;
            int purpleCount = 0;
            for (int x = x0; x < x1; x += stepX) {
                boolean purple = isElixirPurple(p.rgb(x, y));
                if (purple) {
                    purpleCount++;
                    current++;
                    if (current > longest) longest = current;
                } else current = 0;
            }
            double score = longest / (double)columns
                    + 0.30 * purpleCount / (double)columns;
            if (score > bestScore) {
                bestScore = score;
                bestY = y;
                bestLongest = longest;
                bestPurple = purpleCount;
            }
        }

        if (bestY < 0 || bestLongest < Math.max(5, (int)(columns * 0.035))) {
            return new ElixirReading(Double.NaN, 0.0, 0.0);
        }

        int radius = Math.max(2, h / 650);
        int yStep = Math.max(1, radius / 2);
        int sampleRows = 0;
        for (int yy = bestY - radius; yy <= bestY + radius; yy += yStep) sampleRows++;

        boolean[] supported = new boolean[columns];
        int ci = 0;
        for (int x = x0; x < x1 && ci < columns; x += stepX, ci++) {
            int hits = 0;
            for (int yy = bestY - radius; yy <= bestY + radius; yy += yStep) {
                if (isElixirPurple(p.rgb(x, clamp(yy, 0, h - 1)))) hits++;
            }
            supported[ci] = hits >= Math.max(1, (int)Math.ceil(sampleRows * 0.34));
        }

        // Close tiny anti-alias/segment gaps without bridging unrelated card art.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < supported.length - 1; i++) {
                if (!supported[i] && supported[i - 1] && supported[i + 1]) supported[i] = true;
            }
        }

        // Find the longest run allowing only very small gaps.
        int bestStart = -1, bestEnd = -1;
        int runStart = -1, lastTrue = -1, gap = 0;
        for (int i = 0; i < supported.length; i++) {
            if (supported[i]) {
                if (runStart < 0) runStart = i;
                lastTrue = i;
                gap = 0;
            } else if (runStart >= 0) {
                gap++;
                if (gap > 3) {
                    if (bestStart < 0 || lastTrue - runStart > bestEnd - bestStart) {
                        bestStart = runStart;
                        bestEnd = lastTrue;
                    }
                    runStart = -1;
                    lastTrue = -1;
                    gap = 0;
                }
            }
        }
        if (runStart >= 0 && (bestStart < 0 || lastTrue - runStart > bestEnd - bestStart)) {
            bestStart = runStart;
            bestEnd = lastTrue;
        }

        if (bestStart < 0 || bestEnd <= bestStart) {
            return new ElixirReading(Double.NaN, 0.0, 0.0);
        }

        double startFrac = bestStart / (double)columns;
        double runFrac = (bestEnd - bestStart + 1.0) / columns;
        // Real Elixir fill begins near the left of the rail. Purple card-cost
        // bubbles usually form short islands much farther right.
        if (startFrac > 0.24 || runFrac < 0.035) {
            return new ElixirReading(Double.NaN, 0.0, 0.0);
        }

        int usable = Math.max(1, columns - bestStart);
        double fill = (bestEnd - bestStart + 1.0) / usable;
        double value = clamp(fill * 10.0, 0.0, 10.0);

        double leftAnchor = 1.0 - clamp(startFrac / 0.24, 0.0, 1.0);
        double longRun = clamp(runFrac / 0.28, 0.0, 1.0);
        double rowSupport = clamp(bestPurple / (double)Math.max(1, columns) / 0.30, 0.0, 1.0);
        double geometry = clamp(leftAnchor * 0.55 + longRun * 0.45, 0.0, 1.0);
        double confidence = clamp(geometry * 0.62 + rowSupport * 0.38, 0.0, 1.0);
        return new ElixirReading(value, confidence, geometry);
    }

    private HandReading readHand(PlaneReader p, int w, int h) {
        // Conservative geometry spanning the four visible hand cards. The exact
        // card identity is intentionally NOT inferred here.
        double[] centers = {0.370, 0.515, 0.660, 0.805};
        double[][] hashes = new double[HAND_SLOTS][HASH_VALUES];
        double textureSum = 0.0;
        double maxDelta = 0.0;
        int changedSlots = 0;
        int validSlots = 0;
        for (int slot = 0; slot < HAND_SLOTS; slot++) {
            double cx = centers[slot];
            double left = cx - 0.057;
            double right = cx + 0.057;
            double top = 0.760;
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
            double texture = Math.sqrt(Math.max(0.0, meanSq - mean * mean));
            textureSum += texture;
            if (texture >= 0.035) validSlots++;

            if (handInitialized) {
                double delta = 0.0;
                for (int i = 0; i < HASH_VALUES; i++) {
                    delta += Math.abs(hashes[slot][i] - previousHand[slot][i]);
                }
                delta /= HASH_VALUES;
                if (delta >= 0.105) changedSlots++;
                maxDelta = Math.max(maxDelta, delta);
            }
        }
        return new HandReading(hashes, textureSum / HAND_SLOTS, maxDelta,
                changedSlots, validSlots);
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
        int max = Math.max(r, b);
        return r >= 78 && b >= 100 && max >= 118
                && g <= 178
                && r > g * 1.02
                && b > g * 1.04
                && (r + b - 2 * g) > 34;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final class ElixirReading {
        final double value, confidence, geometryScore;
        ElixirReading(double value, double confidence, double geometryScore) {
            this.value = value;
            this.confidence = confidence;
            this.geometryScore = geometryScore;
        }
    }

    private static final class HandReading {
        final double[][] hashes;
        final double texture;
        final double maxDelta;
        final int changedSlots;
        final int validSlots;
        HandReading(double[][] hashes, double texture, double maxDelta,
                    int changedSlots, int validSlots) {
            this.hashes = hashes;
            this.texture = texture;
            this.maxDelta = maxDelta;
            this.changedSlots = changedSlots;
            this.validSlots = validSlots;
        }
    }

    private static final class ArenaReading {
        final double change;
        final String effectHint;
        ArenaReading(double change, String effectHint) {
            this.change = change;
            this.effectHint = effectHint;
        }
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

        boolean valid() {
            return buffer != null && pixelStride >= 4 && rowStride > 0;
        }

        int rgb(int x, int y) {
            x = clamp(x, 0, width - 1);
            y = clamp(y, 0, height - 1);
            int offset = y * rowStride + x * pixelStride;
            if (offset < 0 || offset + 2 >= buffer.limit()) return 0;
            int r = buffer.get(offset) & 255;
            int g = buffer.get(offset + 1) & 255;
            int b = buffer.get(offset + 2) & 255;
            return (r << 16) | (g << 8) | b;
        }
    }
}
