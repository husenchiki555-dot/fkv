package com.huseyn.elixircollector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/** Adaptive rail locator plus asymmetric temporal filter for the visible local Elixir bar. */
public final class ElixirBarTracker {
    public enum State { SEARCHING, CANDIDATE, LOCKED, TEMPORARILY_LOST, REACQUIRE }

    public static final class Reading {
        public final State state;
        public final double rawValue;
        public final double value;
        public final double confidence;
        public final double geometryScore;
        public final FrameRect rail;
        public final boolean sharpDrop;
        public final double dropAmount;
        public final long timeMs;

        Reading(State state, double rawValue, double value, double confidence,
                double geometryScore, FrameRect rail, boolean sharpDrop,
                double dropAmount, long timeMs) {
            this.state = state;
            this.rawValue = rawValue;
            this.value = value;
            this.confidence = confidence;
            this.geometryScore = geometryScore;
            this.rail = rail;
            this.sharpDrop = sharpDrop;
            this.dropAmount = dropAmount;
            this.timeMs = timeMs;
        }

        public boolean hasValue() { return !Double.isNaN(value); }
        public boolean locked() { return state == State.LOCKED && hasValue(); }
    }

    private static final double ACQUIRE = 0.54;
    private static final double KEEP = 0.43;
    private State state = State.SEARCHING;
    private Candidate tracked;
    private Candidate candidate;
    private int hits;
    private int misses;
    private double purpleHue = 0.82;
    private final ArrayDeque<Double> initialValues = new ArrayDeque<>();
    private double filtered = Double.NaN;
    private double pendingDrop = Double.NaN;
    private int pendingDropHits;
    private long lastValueMs;

    public Reading update(PixelFrame frame, HudLayoutTracker.Observation hud, long nowMs) {
        if (frame == null || hud == null || hud.layout == null) return miss(nowMs);
        Candidate best = tracked != null && misses < 8
                ? search(frame, hud.layout, tracked, true)
                : search(frame, hud.layout, null, false);
        double threshold = tracked == null ? ACQUIRE : KEEP;
        if (best == null || best.score < threshold) return miss(nowMs);

        misses = 0;
        if (tracked != null) {
            tracked = blend(tracked, best, geometryDistance(tracked, best) < 0.025 ? 0.20 : 0.38);
            state = State.LOCKED;
        } else if (candidate != null && geometryDistance(candidate, best) < 0.035
                && Math.abs(candidate.value - best.value) < 0.85) {
            hits++;
            candidate = blend(candidate, best, 0.42);
            state = State.CANDIDATE;
            if (hits >= 3) {
                tracked = candidate;
                candidate = null;
                hits = 0;
                state = State.LOCKED;
            }
        } else {
            candidate = best;
            hits = 1;
            state = State.CANDIDATE;
        }

        Candidate active = tracked != null ? tracked : candidate;
        if (active == null) return empty(nowMs, state);
        if (active.hueSamples > 6 && active.score >= 0.60) {
            double delta = signedHueDelta(purpleHue, active.meanHue);
            purpleHue = wrapHue(purpleHue + delta * 0.08);
        }
        return filter(active, nowMs);
    }

    public void resetValueButKeepGeometry() {
        filtered = Double.NaN;
        pendingDrop = Double.NaN;
        pendingDropHits = 0;
        initialValues.clear();
        lastValueMs = 0L;
        if (tracked != null) {
            state = State.REACQUIRE;
            misses = 5;
        } else state = State.SEARCHING;
    }

    public void resetAll() {
        state = State.SEARCHING;
        tracked = candidate = null;
        hits = misses = 0;
        filtered = pendingDrop = Double.NaN;
        pendingDropHits = 0;
        initialValues.clear();
        lastValueMs = 0L;
        purpleHue = 0.82;
    }

    private Reading filter(Candidate c, long nowMs) {
        double raw = c.value >= 9.82 ? 10.0 : ColorMath.clamp(c.value, 0.0, 10.0);
        boolean sharp = false;
        double drop = 0.0;
        if (Double.isNaN(filtered)) {
            initialValues.addLast(raw);
            while (initialValues.size() > 4) initialValues.removeFirst();
            if (initialValues.size() >= 3) {
                double lo = Collections.min(initialValues);
                double hi = Collections.max(initialValues);
                if (hi - lo <= 0.65) filtered = median(initialValues);
            }
        } else {
            double old = filtered;
            double delta = raw - old;
            if (delta <= -0.45) {
                if (c.score >= 0.72) {
                    filtered = raw;
                    pendingDrop = Double.NaN;
                    pendingDropHits = 0;
                } else if (!Double.isNaN(pendingDrop) && Math.abs(pendingDrop - raw) <= 0.50) {
                    pendingDropHits++;
                    pendingDrop = (pendingDrop + raw) * 0.5;
                    if (pendingDropHits >= 2) {
                        filtered = pendingDrop;
                        pendingDrop = Double.NaN;
                        pendingDropHits = 0;
                    }
                } else {
                    pendingDrop = raw;
                    pendingDropHits = 1;
                }
                if (filtered < old - 0.38) {
                    sharp = true;
                    drop = old - filtered;
                }
            } else {
                pendingDrop = Double.NaN;
                pendingDropHits = 0;
                double dt = lastValueMs <= 0 ? 0.10 : ColorMath.clamp((nowMs - lastValueMs) / 1000.0, 0.02, 1.0);
                double target = raw;
                if (delta > 0.0) target = Math.min(raw, old + 1.65 * dt + 0.16);
                double alpha = Math.abs(target - old) < 0.18 ? 0.58 : (target > old ? 0.40 : 0.52);
                filtered = old + (target - old) * alpha;
                if (filtered > 9.84 && raw > 9.75) filtered = 10.0;
            }
        }
        lastValueMs = nowMs;
        double temporal = Double.isNaN(filtered) ? 0.35 : 1.0;
        double confidence = ColorMath.clamp(c.score * 0.82 + temporal * 0.18, 0.0, 1.0);
        return new Reading(state, raw, filtered, confidence, c.geometry,
                c.rect(), sharp, drop, nowMs);
    }

    private Reading miss(long nowMs) {
        misses++;
        if (tracked == null) {
            hits = Math.max(0, hits - 1);
            if (hits == 0) candidate = null;
            state = candidate == null ? State.SEARCHING : State.CANDIDATE;
            return empty(nowMs, state);
        }
        if (misses <= 7) state = State.TEMPORARILY_LOST;
        else if (misses <= 20) state = State.REACQUIRE;
        else {
            tracked = candidate = null;
            hits = 0;
            state = State.SEARCHING;
        }
        double confidence = tracked == null ? 0.0 : Math.max(0.05, tracked.score * Math.exp(-misses * 0.22));
        return new Reading(state, Double.NaN, filtered, confidence,
                tracked == null ? 0.0 : tracked.geometry,
                tracked == null ? null : tracked.rect(), false, 0.0, nowMs);
    }

    private Reading empty(long nowMs, State s) {
        return new Reading(s, Double.NaN, filtered, 0.0, 0.0, null, false, 0.0, nowMs);
    }

    private Candidate search(PixelFrame f, HudLayoutTracker.Layout layout,
                             Candidate around, boolean local) {
        Candidate best = null;
        double baseLeft = around == null ? layout.expectedRail.left : around.left;
        double baseRight = around == null ? layout.expectedRail.right : around.right;
        double startY = local ? around.y - 0.014 : layout.expectedRail.top;
        double endY = local ? around.y + 0.014 : 0.994;
        double[] endpointOffsets = local
                ? new double[]{-0.014, -0.007, 0.0, 0.007, 0.014}
                : new double[]{-0.032, -0.016, 0.0, 0.016, 0.032};
        double yStep = local ? 0.0035 : 0.0055;
        startY = ColorMath.clamp(startY, 0.875, 0.994);
        endY = ColorMath.clamp(Math.max(startY, endY), startY, 0.996);
        for (double dl : endpointOffsets) for (double dr : endpointOffsets) {
            double left = baseLeft + dl;
            double right = baseRight + dr;
            if (right - left < 0.42 || right - left > 0.82) continue;
            for (double y = startY; y <= endY; y += yStep) {
                Candidate c = evaluate(f, left, right, y, layout.expectedRail);
                if (c != null && (best == null || c.rank > best.rank)) best = c;
            }
        }
        return best;
    }

    private Candidate evaluate(PixelFrame f, double left, double right, double y,
                               FrameRect expected) {
        final int n = 120;
        final int rows = 5;
        boolean[] supported = new boolean[n];
        int[] rowCounts = new int[rows];
        double hueX = 0.0, hueY = 0.0;
        int hueN = 0;
        double radius = Math.max(0.0015, 3.2 / Math.max(800.0, f.height()));
        for (int i = 0; i < n; i++) {
            double x = ColorMath.lerp(left, right, (i + 0.5) / n);
            int hits = 0;
            for (int row = 0; row < rows; row++) {
                double yy = y + (row - 2) * radius;
                int rgb = f.rgbNormalized(x, yy);
                if (isPurple(rgb)) {
                    hits++;
                    rowCounts[row]++;
                    double h = ColorMath.hue(rgb) * Math.PI * 2.0;
                    hueX += Math.cos(h);
                    hueY += Math.sin(h);
                    hueN++;
                }
            }
            supported[i] = hits >= 2;
        }

        // Bridge anti-aliased segment separators, but never long unrelated gaps.
        for (int pass = 0; pass < 2; pass++) {
            boolean[] copy = supported.clone();
            for (int i = 1; i < n - 1; i++) {
                if (!copy[i] && copy[i - 1] && copy[i + 1]) supported[i] = true;
                if (i >= 2 && !copy[i] && copy[i - 2] && copy[i + 1]) supported[i] = true;
            }
        }

        int first = -1, last = -1, purple = 0;
        for (int i = 0; i < n; i++) if (supported[i]) {
            if (first < 0) first = i;
            last = i;
            purple++;
        }
        if (first < 0 || purple < 3 || first > 18) return null;

        int holes = 0, stray = 0;
        for (int i = first; i <= last; i++) if (!supported[i]) holes++;
        int longGap = 0;
        for (int i = first; i < n; i++) {
            if (!supported[i]) longGap++;
            else {
                if (longGap >= 7 && i < last) stray += longGap;
                longGap = 0;
            }
        }
        double anchorScore = 1.0 - ColorMath.clamp(first / 18.0, 0.0, 1.0);
        double prefixScore = 1.0 - ColorMath.clamp((holes + stray * 1.5) / Math.max(5.0, last - first + 1.0), 0.0, 1.0);
        double value = ColorMath.clamp((last + 1.0) / n * 10.0, 0.0, 10.0);

        double rowMean = 0.0;
        for (int v : rowCounts) rowMean += v;
        rowMean /= rows;
        double rowDev = 0.0;
        for (int v : rowCounts) rowDev += Math.abs(v - rowMean);
        rowDev /= Math.max(1.0, rows * rowMean);
        double rowConsistency = 1.0 - ColorMath.clamp(rowDev / 0.65, 0.0, 1.0);

        double edge = railEdgeScore(f, left, right, y, radius * 3.5);
        double expectedDelta = Math.abs(left - expected.left) + Math.abs(right - expected.right);
        double widthPrior = Math.exp(-expectedDelta * 14.0);
        double runSupport = ColorMath.clamp(purple / 28.0, 0.0, 1.0);
        double geometry = ColorMath.clamp(edge * 0.42 + widthPrior * 0.35
                + rowConsistency * 0.23, 0.0, 1.0);
        double score = prefixScore * 0.29 + anchorScore * 0.19 + rowConsistency * 0.16
                + edge * 0.16 + widthPrior * 0.12 + runSupport * 0.08;
        double meanHue = hueN == 0 ? purpleHue : wrapHue(Math.atan2(hueY, hueX) / (Math.PI * 2.0));
        return new Candidate(left, right, y, value, ColorMath.clamp(score, 0.0, 1.0),
                ColorMath.clamp(score + widthPrior * 0.025, 0.0, 1.05), geometry, meanHue, hueN);
    }

    private double railEdgeScore(PixelFrame f, double left, double right, double y, double dy) {
        double edge = 0.0;
        int samples = 30;
        for (int i = 0; i < samples; i++) {
            double x = ColorMath.lerp(left, right, (i + 0.5) / samples);
            double in = ColorMath.luminance(f.rgbNormalized(x, y));
            double up = ColorMath.luminance(f.rgbNormalized(x, y - dy));
            double down = ColorMath.luminance(f.rgbNormalized(x, y + dy));
            edge += Math.max(Math.abs(in - up), Math.abs(in - down));
        }
        edge /= samples;
        double endpoint = 0.0;
        for (int k = -2; k <= 2; k++) {
            double yy = y + k * dy * 0.25;
            endpoint += Math.abs(ColorMath.luminance(f.rgbNormalized(left + 0.004, yy))
                    - ColorMath.luminance(f.rgbNormalized(left - 0.004, yy)));
            endpoint += Math.abs(ColorMath.luminance(f.rgbNormalized(right - 0.004, yy))
                    - ColorMath.luminance(f.rgbNormalized(right + 0.004, yy)));
        }
        endpoint /= 10.0;
        return ColorMath.clamp((edge * 0.65 + endpoint * 0.35 - 0.015) / 0.20, 0.0, 1.0);
    }

    private boolean isPurple(int rgb) {
        double sat = ColorMath.saturation(rgb);
        double val = ColorMath.value(rgb);
        if (sat < 0.24 || val < 0.27) return false;
        double h = ColorMath.hue(rgb);
        int r = ColorMath.red(rgb), g = ColorMath.green(rgb), b = ColorMath.blue(rgb);
        boolean chroma = r + b >= g * 1.78 + 26 && b >= g * 0.92 && r >= g * 0.78;
        return chroma && ColorMath.hueDistance(h, purpleHue) <= 0.155;
    }

    private static Candidate blend(Candidate a, Candidate b, double t) {
        return new Candidate(ColorMath.lerp(a.left, b.left, t), ColorMath.lerp(a.right, b.right, t),
                ColorMath.lerp(a.y, b.y, t), b.value,
                ColorMath.lerp(a.score, b.score, t), b.rank,
                ColorMath.lerp(a.geometry, b.geometry, t), b.meanHue, b.hueSamples);
    }

    private static double geometryDistance(Candidate a, Candidate b) {
        return Math.abs(a.left - b.left) + Math.abs(a.right - b.right) + Math.abs(a.y - b.y) * 1.4;
    }

    private static double median(Iterable<Double> values) {
        ArrayList<Double> list = new ArrayList<>();
        for (Double v : values) if (v != null && !Double.isNaN(v)) list.add(v);
        if (list.isEmpty()) return Double.NaN;
        Collections.sort(list);
        int m = list.size() / 2;
        return list.size() % 2 == 0 ? (list.get(m - 1) + list.get(m)) * 0.5 : list.get(m);
    }

    private static double wrapHue(double h) {
        h %= 1.0;
        return h < 0.0 ? h + 1.0 : h;
    }

    private static double signedHueDelta(double from, double to) {
        double d = wrapHue(to) - wrapHue(from);
        if (d > 0.5) d -= 1.0;
        if (d < -0.5) d += 1.0;
        return d;
    }

    private static final class Candidate {
        final double left, right, y, value, score, rank, geometry, meanHue;
        final int hueSamples;
        Candidate(double left, double right, double y, double value, double score,
                  double rank, double geometry, double meanHue, int hueSamples) {
            this.left = left;
            this.right = right;
            this.y = y;
            this.value = value;
            this.score = score;
            this.rank = rank;
            this.geometry = geometry;
            this.meanHue = meanHue;
            this.hueSamples = hueSamples;
        }
        FrameRect rect() { return new FrameRect(left, y - 0.006, right, y + 0.006); }
    }
}
