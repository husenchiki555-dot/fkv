package com.huseyn.elixircollector;

import java.util.Arrays;

/**
 * Locates and then tracks the four equally-spaced hand cards. Coordinates are
 * card centres, not card left edges. The measured 1080x2400 layout is only a
 * search prior; every coordinate used by later detectors comes from the
 * winning frame candidate and is tracked over time.
 */
public final class HudLayoutTracker {
    public enum State { SEARCHING, CANDIDATE, LOCKED, TEMPORARILY_LOST, REACQUIRE }

    public static final class Layout {
        public final double[] handCenters;
        public final double handTop;
        public final double handBottom;
        public final double cardWidth;
        public final FrameRect expectedRail;
        public final double confidence;
        public final double stability;

        Layout(double first, double spacing, double top, double height,
               double confidence, double stability) {
            handCenters = new double[]{first, first + spacing, first + spacing * 2.0, first + spacing * 3.0};
            handTop = top;
            handBottom = Math.min(0.952, top + height);
            cardWidth = spacing * 0.91;
            // The purple droplet is left of the actual ten-segment rail. Do not
            // include it in fillWidth/totalRailWidth or every value is biased high.
            double railLeft = ColorMath.clamp(handCenters[0] - spacing * 0.20, 0.16, 0.48);
            double railRight = ColorMath.clamp(handCenters[3] + spacing * 0.57,
                    railLeft + 0.54, 0.985);
            double railTop = ColorMath.clamp(handBottom - 0.014, 0.885, 0.968);
            expectedRail = new FrameRect(railLeft, railTop, railRight, 0.990);
            this.confidence = confidence;
            this.stability = stability;
        }

        public FrameRect slotRect(int index) {
            int i = ColorMath.clamp(index, 0, 3);
            return new FrameRect(handCenters[i] - cardWidth * 0.5, handTop,
                    handCenters[i] + cardWidth * 0.5, handBottom);
        }

        double first() { return handCenters[0]; }
        double spacing() { return handCenters[1] - handCenters[0]; }
        double height() { return handBottom - handTop; }
    }

    public static final class Observation {
        public final State state;
        public final Layout layout;
        public final double handStructureScore;
        public final int visuallyValidSlots;

        Observation(State state, Layout layout, double score, int validSlots) {
            this.state = state;
            this.layout = layout;
            handStructureScore = score;
            visuallyValidSlots = validSlots;
        }

        public boolean usable() {
            return layout != null && (state == State.LOCKED || state == State.TEMPORARILY_LOST || state == State.REACQUIRE);
        }
    }

    // Measured card centres in 10665.mp4 are approximately .31/.50/.68/.87.
    // Earlier builds accidentally treated the measured left edges as centres.
    private static final double SEED_FIRST = 0.313;
    private static final double SEED_SPACING = 0.1845;
    private static final double ACQUIRE_SCORE = 0.58;
    private static final double KEEP_SCORE = 0.47;

    private State state = State.SEARCHING;
    private Layout tracked;
    private Layout candidate;
    private int candidateHits;
    private int misses;

    public Observation update(PixelFrame frame) {
        if (frame == null || frame.width() < 200 || frame.height() < 300) {
            return miss();
        }

        Candidate best = tracked != null && misses < 8
                ? searchLocal(frame, tracked)
                : searchGlobal(frame);
        double threshold = tracked == null ? ACQUIRE_SCORE : KEEP_SCORE;
        if (best == null || best.score < threshold || best.validSlots < 3) return miss();

        misses = 0;
        Layout measured = best.toLayout(tracked);
        if (tracked != null) {
            double d = geometryDistance(tracked, measured);
            double stability = Math.exp(-d * 35.0);
            tracked = blend(tracked, measured, d < 0.025 ? 0.18 : 0.34, best.score, stability);
            state = State.LOCKED;
            return new Observation(state, tracked, best.score, best.validSlots);
        }

        if (candidate != null && geometryDistance(candidate, measured) <= 0.040) {
            candidateHits++;
            candidate = blend(candidate, measured, 0.42, best.score,
                    Math.exp(-geometryDistance(candidate, measured) * 30.0));
        } else {
            candidate = measured;
            candidateHits = 1;
        }
        if (candidateHits >= 3) {
            tracked = candidate;
            candidate = null;
            candidateHits = 0;
            state = State.LOCKED;
            return new Observation(state, tracked, best.score, best.validSlots);
        }
        state = State.CANDIDATE;
        return new Observation(state, candidate, best.score, best.validSlots);
    }

    public void resetForNewCapture() {
        state = State.SEARCHING;
        tracked = null;
        candidate = null;
        candidateHits = 0;
        misses = 0;
    }

    /** Keep phone geometry between matches but require it to be reacquired. */
    public void markBattleEnded() {
        candidate = null;
        candidateHits = 0;
        misses = tracked == null ? 0 : 5;
        state = tracked == null ? State.SEARCHING : State.REACQUIRE;
    }

    private Observation miss() {
        misses++;
        if (tracked == null) {
            candidateHits = Math.max(0, candidateHits - 1);
            if (candidateHits == 0) candidate = null;
            state = candidate == null ? State.SEARCHING : State.CANDIDATE;
            return new Observation(state, candidate, 0.0, 0);
        }
        if (misses <= 8) state = State.TEMPORARILY_LOST;
        else if (misses <= 22) state = State.REACQUIRE;
        else {
            tracked = null;
            candidate = null;
            candidateHits = 0;
            state = State.SEARCHING;
        }
        return new Observation(state, tracked, 0.0, 0);
    }

    private Candidate searchLocal(PixelFrame f, Layout around) {
        Candidate best = null;
        double[] offsets = {-0.016, -0.008, 0.0, 0.008, 0.016};
        double[] spacingOffsets = {-0.008, -0.004, 0.0, 0.004, 0.008};
        double[] topOffsets = {-0.020, -0.010, 0.0, 0.010, 0.020};
        double[] heightOffsets = {-0.018, 0.0, 0.018};
        for (double dx : offsets) for (double ds : spacingOffsets)
            for (double dy : topOffsets) for (double dh : heightOffsets) {
                Candidate c = score(f, around.first() + dx, around.spacing() + ds,
                        around.handTop + dy, around.height() + dh);
                if (c != null && (best == null || c.rank > best.rank)) best = c;
            }
        return best;
    }

    private Candidate searchGlobal(PixelFrame f) {
        Candidate best = null;
        double widthOverHeight = f.width() / (double)Math.max(1, f.height());
        double nominalHeight = ColorMath.clamp(widthOverHeight * 0.40, 0.135, 0.215);
        for (double first = 0.285; first <= 0.345; first += 0.012) {
            for (double spacing = 0.176; spacing <= 0.200; spacing += 0.006) {
                if (first + spacing * 3.0 > 0.94) continue;
                for (double top = 0.700; top <= 0.805; top += 0.0175) {
                    for (double height : new double[]{nominalHeight - 0.025,
                            nominalHeight, nominalHeight + 0.025}) {
                        Candidate c = score(f, first, spacing, top, height);
                        if (c != null && (best == null || c.rank > best.rank)) best = c;
                    }
                }
            }
        }
        return best;
    }

    private Candidate score(PixelFrame f, double first, double spacing, double top, double height) {
        if (first < 0.275 || first > 0.36 || spacing < 0.174 || spacing > 0.210 || top < 0.67
                || height < 0.12 || height > 0.24 || top + height > 0.965) return null;
        double width = spacing * 0.91;
        double[] slot = new double[4];
        double[] textures = new double[4];
        int valid = 0;
        for (int i = 0; i < 4; i++) {
            double cx = first + spacing * i;
            PatchMetrics p = patchMetrics(f, new FrameRect(cx - width * 0.5, top,
                    cx + width * 0.5, top + height));
            textures[i] = p.texture;
            double textureScore = ColorMath.clamp((p.texture - 0.032) / 0.135, 0.0, 1.0);
            double saturationScore = ColorMath.clamp((p.saturation - 0.11) / 0.34, 0.0, 1.0);
            double gradientScore = ColorMath.clamp((p.gradient - 0.030) / 0.18, 0.0, 1.0);
            double borderScore = ColorMath.clamp((p.borderContrast - 0.025) / 0.13, 0.0, 1.0);
            slot[i] = textureScore * 0.30 + saturationScore * 0.14
                    + gradientScore * 0.25 + borderScore * 0.31;
            if (slot[i] >= 0.43 && p.texture >= 0.040 && borderScore >= 0.18) valid++;
        }

        double avg = Arrays.stream(slot).average().orElse(0.0);
        double min = Arrays.stream(slot).min().orElse(0.0);
        double variance = 0.0;
        for (double v : slot) variance += (v - avg) * (v - avg);
        variance = Math.sqrt(variance / slot.length);
        double consistency = 1.0 - ColorMath.clamp(variance / 0.30, 0.0, 1.0);

        double gapTexture = 0.0;
        for (int i = 0; i < 3; i++) {
            double x = first + spacing * (i + 0.5);
            gapTexture += patchMetrics(f, new FrameRect(x - spacing * 0.032,
                    top + height * 0.10, x + spacing * 0.032, top + height * 0.90)).texture;
        }
        gapTexture /= 3.0;
        double cardTexture = Arrays.stream(textures).average().orElse(0.0);
        double separation = ColorMath.clamp((cardTexture - gapTexture + 0.010) / 0.105, 0.0, 1.0);
        double badgePattern = costBadgePattern(f, first, spacing, top, height);
        double panel = bottomPanelScore(f, first, spacing, top, height);
        double score = avg * 0.48 + min * 0.10 + consistency * 0.10
                + separation * 0.12 + badgePattern * 0.12 + panel * 0.08;

        // This is deliberately weak: it helps the first frame on the user's phone
        // but cannot make a low-quality candidate pass the acquisition threshold.
        double seedDistance = Math.abs(first - SEED_FIRST) + Math.abs(spacing - SEED_SPACING) * 1.6;
        double seedPrior = Math.exp(-seedDistance * 11.0) * 0.025;
        return new Candidate(first, spacing, top, height, score, score + seedPrior, valid);
    }

    /** Four aligned purple cost medallions are a useful cue, but never sufficient alone. */
    private double costBadgePattern(PixelFrame f, double first, double spacing,
                                    double top, double height) {
        double total = 0.0;
        int supported = 0;
        for (int card = 0; card < 4; card++) {
            double cx = first + spacing * card;
            int purple = 0, brightGlyph = 0, n = 0;
            for (int gy = 0; gy < 5; gy++) for (int gx = 0; gx < 7; gx++) {
                double x = cx + (gx - 3) * spacing * 0.026;
                double y = top + height * (0.80 + gy * 0.035);
                int rgb = f.rgbNormalized(x, y);
                double h = ColorMath.hue(rgb), s = ColorMath.saturation(rgb), v = ColorMath.value(rgb);
                if (s >= 0.31 && v >= 0.30 && h >= 0.70 && h <= 0.96) purple++;
                int max = Math.max(ColorMath.red(rgb), Math.max(ColorMath.green(rgb), ColorMath.blue(rgb)));
                int min = Math.min(ColorMath.red(rgb), Math.min(ColorMath.green(rgb), ColorMath.blue(rgb)));
                if (ColorMath.luminance(rgb) >= 0.66 && max - min <= 100) brightGlyph++;
                n++;
            }
            double score = ColorMath.clamp(purple / (double)Math.max(1, n) / 0.34, 0.0, 1.0) * 0.78
                    + ColorMath.clamp(brightGlyph / 4.0, 0.0, 1.0) * 0.22;
            total += score;
            if (score >= 0.30) supported++;
        }
        double coverage = ColorMath.clamp((supported - 1.0) / 3.0, 0.0, 1.0);
        return total / 4.0 * 0.68 + coverage * 0.32;
    }

    /** The hand sits in a blue HUD tray below the artwork on the recorded phone. */
    private double bottomPanelScore(PixelFrame f, double first, double spacing,
                                    double top, double height) {
        int blue = 0, dark = 0, n = 0;
        double y = Math.min(0.972, top + height + 0.018);
        for (int i = 0; i < 36; i++) {
            double x = ColorMath.lerp(first - spacing * 0.55,
                    first + spacing * 3.55, (i + 0.5) / 36.0);
            int rgb = f.rgbNormalized(x, y);
            double h = ColorMath.hue(rgb), s = ColorMath.saturation(rgb), v = ColorMath.value(rgb);
            if (s >= 0.30 && h >= 0.50 && h <= 0.70) blue++;
            if (v <= 0.34) dark++;
            n++;
        }
        return ColorMath.clamp((blue + dark * 0.30) / Math.max(1.0, n * 0.58), 0.0, 1.0);
    }

    private PatchMetrics patchMetrics(PixelFrame f, FrameRect r) {
        final int cols = 7, rows = 9;
        double sum = 0.0, sumSq = 0.0, sat = 0.0, gradient = 0.0;
        double leftEdge = 0.0, rightEdge = 0.0, topEdge = 0.0, bottomEdge = 0.0;
        int count = 0;
        double dx = Math.max(0.002, r.width() / cols * 0.55);
        double dy = Math.max(0.002, r.height() / rows * 0.55);
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            double nx = ColorMath.lerp(r.left, r.right, (x + 0.5) / cols);
            double ny = ColorMath.lerp(r.top, r.bottom, (y + 0.5) / rows);
            int c = f.rgbNormalized(nx, ny);
            double l = ColorMath.luminance(c);
            sum += l;
            sumSq += l * l;
            sat += ColorMath.saturation(c);
            gradient += Math.abs(l - ColorMath.luminance(f.rgbNormalized(nx + dx, ny)));
            gradient += Math.abs(l - ColorMath.luminance(f.rgbNormalized(nx, ny + dy)));
            count++;
        }
        double mean = sum / Math.max(1, count);
        double texture = Math.sqrt(Math.max(0.0, sumSq / Math.max(1, count) - mean * mean));
        sat /= Math.max(1, count);
        gradient /= Math.max(1, count * 2);
        for (int i = 1; i <= 7; i++) {
            double y = ColorMath.lerp(r.top, r.bottom, i / 8.0);
            leftEdge += Math.abs(ColorMath.luminance(f.rgbNormalized(r.left + dx, y))
                    - ColorMath.luminance(f.rgbNormalized(r.left - dx, y)));
            rightEdge += Math.abs(ColorMath.luminance(f.rgbNormalized(r.right - dx, y))
                    - ColorMath.luminance(f.rgbNormalized(r.right + dx, y)));
        }
        for (int i = 1; i <= 6; i++) {
            double x = ColorMath.lerp(r.left, r.right, i / 7.0);
            topEdge += Math.abs(ColorMath.luminance(f.rgbNormalized(x, r.top + dy))
                    - ColorMath.luminance(f.rgbNormalized(x, r.top - dy)));
            bottomEdge += Math.abs(ColorMath.luminance(f.rgbNormalized(x, r.bottom - dy))
                    - ColorMath.luminance(f.rgbNormalized(x, r.bottom + dy)));
        }
        double border = (leftEdge / 7.0 + rightEdge / 7.0
                + topEdge / 6.0 + bottomEdge / 6.0) / 4.0;
        return new PatchMetrics(texture, sat, gradient, border);
    }

    private static double geometryDistance(Layout a, Layout b) {
        if (a == null || b == null) return Double.POSITIVE_INFINITY;
        return Math.abs(a.first() - b.first()) + Math.abs(a.spacing() - b.spacing()) * 1.8
                + Math.abs(a.handTop - b.handTop) + Math.abs(a.height() - b.height()) * 0.7;
    }

    private static Layout blend(Layout a, Layout b, double alpha, double score, double stability) {
        return new Layout(
                ColorMath.lerp(a.first(), b.first(), alpha),
                ColorMath.lerp(a.spacing(), b.spacing(), alpha),
                ColorMath.lerp(a.handTop, b.handTop, alpha),
                ColorMath.lerp(a.height(), b.height(), alpha),
                ColorMath.clamp(score, 0.0, 1.0),
                ColorMath.clamp(stability, 0.0, 1.0));
    }

    private static final class Candidate {
        final double first, spacing, top, height, score, rank;
        final int validSlots;
        Candidate(double first, double spacing, double top, double height,
                  double score, double rank, int validSlots) {
            this.first = first;
            this.spacing = spacing;
            this.top = top;
            this.height = height;
            this.score = score;
            this.rank = rank;
            this.validSlots = validSlots;
        }
        Layout toLayout(Layout previous) {
            double stability = previous == null ? 0.0 : Math.exp(-geometryDistance(previous,
                    new Layout(first, spacing, top, height, score, 0.0)) * 35.0);
            return new Layout(first, spacing, top, height, score, stability);
        }
    }

    private static final class PatchMetrics {
        final double texture, saturation, gradient, borderContrast;
        PatchMetrics(double texture, double saturation, double gradient, double borderContrast) {
            this.texture = texture;
            this.saturation = saturation;
            this.gradient = gradient;
            this.borderContrast = borderContrast;
        }
    }
}
