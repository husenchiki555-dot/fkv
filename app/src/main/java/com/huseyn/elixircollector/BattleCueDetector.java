package com.huseyn.elixircollector;

/** Independent structural cues used by the battle state machine. */
public final class BattleCueDetector {
    public static final class Signals {
        public final double handScore;
        public final double elixirScore;
        public final double timerScore;
        public final double crownScore;
        public final double arenaScore;
        public final double layoutStability;
        public final double audioScore;
        public final double composite;
        public final int corroboratingSensors;
        public final int recognizedHandSlots;

        Signals(double hand, double elixir, double timer, double crown, double arena,
                double stability, double audio, double composite, int sensors) {
            this(hand, elixir, timer, crown, arena, stability, audio, composite, sensors, 0);
        }

        Signals(double hand, double elixir, double timer, double crown, double arena,
                double stability, double audio, double composite, int sensors,
                int recognizedSlots) {
            handScore = hand;
            elixirScore = elixir;
            timerScore = timer;
            crownScore = crown;
            arenaScore = arena;
            layoutStability = stability;
            audioScore = audio;
            this.composite = composite;
            corroboratingSensors = sensors;
            recognizedHandSlots = recognizedSlots;
        }
    }

    public Signals detect(PixelFrame frame, HudLayoutTracker.Observation hud,
                          ElixirBarTracker.Reading elixir, boolean audioAvailable) {
        return detect(frame, hud, elixir, audioAvailable, 0);
    }

    public Signals detect(PixelFrame frame, HudLayoutTracker.Observation hud,
                          ElixirBarTracker.Reading elixir, boolean audioAvailable,
                          int recognizedHandSlots) {
        if (frame == null) return new Signals(0,0,0,0,0,0,0,0,0,0);
        double structure = hud == null ? 0.0 : hud.handStructureScore;
        double recognition = ColorMath.clamp(recognizedHandSlots / 3.0, 0.0, 1.0);
        double hand = ColorMath.clamp(structure * 0.88 + recognition * 0.12, 0.0, 1.0);
        boolean railState = elixir != null && (elixir.state == ElixirBarTracker.State.CANDIDATE
                || elixir.state == ElixirBarTracker.State.LOCKED);
        double rail = !railState || elixir.geometryScore < 0.54 ? 0.0 : elixir.confidence;
        double timer = timerCue(frame);
        double crown = crownCue(frame);
        double arena = arenaCue(frame);
        double stability = hud == null || hud.layout == null ? 0.0 : hud.layout.stability;
        if (hud != null && hud.state == HudLayoutTracker.State.LOCKED) stability = Math.max(stability, 0.72);
        // Audio capture being available is not evidence that a battle is active.
        // Actual audio transients are fused later with visual deployment events.
        double audio = 0.0;

        int sensors = 0;
        if (rail >= 0.58) sensors++;
        if (timer >= 0.55) sensors++;
        if (crown >= 0.55) sensors++;
        if (recognizedHandSlots >= 2) sensors++;

        double composite = hand * 0.32 + rail * 0.22 + timer * 0.13 + crown * 0.14
                + arena * 0.13 + stability * 0.04 + recognition * 0.02;
        return new Signals(hand, rail, timer, crown, arena, stability, audio,
                ColorMath.clamp(composite, 0.0, 1.0), sensors, recognizedHandSlots);
    }

    private double timerCue(PixelFrame f) {
        // On the recorded portrait HUD the clock is a compact panel at upper
        // right. Scanning the centre mistook logos and menu labels for a timer.
        FrameRect r = new FrameRect(0.805, 0.030, 0.995, 0.125);
        final int cols = 34, rows = 22;
        int bright = 0, dark = 0, edges = 0, total = 0;
        boolean[] columnBright = new boolean[cols];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            double nx = ColorMath.lerp(r.left, r.right, (x + 0.5) / cols);
            double ny = ColorMath.lerp(r.top, r.bottom, (y + 0.5) / rows);
            double l = ColorMath.luminance(f.rgbNormalized(nx, ny));
            double s = ColorMath.saturation(f.rgbNormalized(nx, ny));
            if (l >= 0.69 && s <= 0.48) { bright++; columnBright[x] = true; }
            if (l <= 0.28) dark++;
            if (x + 1 < cols) {
                double l2 = ColorMath.luminance(f.rgbNormalized(
                        ColorMath.lerp(r.left, r.right, (x + 1.5) / cols), ny));
                if (Math.abs(l2 - l) >= 0.24) edges++;
            }
            total++;
        }
        int groups = 0;
        boolean in = false;
        for (boolean b : columnBright) {
            if (b && !in) { groups++; in = true; }
            else if (!b) in = false;
        }
        double brightRatio = bright / (double)Math.max(1, total);
        double darkRatio = dark / (double)Math.max(1, total);
        double edgeRatio = edges / (double)Math.max(1, total);
        double glyph = ColorMath.clamp((brightRatio - 0.018) / 0.16, 0.0, 1.0);
        if (brightRatio > 0.38) glyph *= 0.28;
        double grouping = groups >= 2 && groups <= 9 ? 1.0
                : (groups == 1 || (groups >= 10 && groups <= 13) ? 0.30 : 0.0);
        double contrast = ColorMath.clamp((darkRatio + edgeRatio * 1.9 - 0.18) / 0.60, 0.0, 1.0);
        return glyph * 0.50 + grouping * 0.30 + contrast * 0.20;
    }

    private double crownCue(PixelFrame f) {
        // Battle-specific ordering matters: red health bars belong to the two
        // upper Princess towers and blue bars to the two lower towers. Menus and
        // loading art often contain both colours globally, but not in this layout.
        double upperLeft = healthBand(f, new FrameRect(0.07, 0.125, 0.33, 0.315), true);
        double upperRight = healthBand(f, new FrameRect(0.67, 0.125, 0.93, 0.315), true);
        double lowerLeft = healthBand(f, new FrameRect(0.07, 0.475, 0.33, 0.690), false);
        double lowerRight = healthBand(f, new FrameRect(0.67, 0.475, 0.93, 0.690), false);
        double upper = (upperLeft + upperRight) * 0.5;
        double lower = (lowerLeft + lowerRight) * 0.5;
        double sideCoverage = ColorMath.clamp((Math.min(upperLeft, upperRight)
                + Math.min(lowerLeft, lowerRight)) * 0.5, 0.0, 1.0);
        return ColorMath.clamp(upper * 0.36 + lower * 0.36 + sideCoverage * 0.28, 0.0, 1.0);
    }

    private double healthBand(PixelFrame f, FrameRect r, boolean red) {
        final int cols = 34, rows = 24;
        int coloured = 0, total = cols * rows, bestRow = 0;
        for (int y = 0; y < rows; y++) {
            int row = 0, longest = 0, run = 0;
            for (int x = 0; x < cols; x++) {
                int rgb = f.rgbNormalized(
                        ColorMath.lerp(r.left, r.right, (x + 0.5) / cols),
                        ColorMath.lerp(r.top, r.bottom, (y + 0.5) / rows));
                double h = ColorMath.hue(rgb), s = ColorMath.saturation(rgb), v = ColorMath.value(rgb);
                boolean hit = s >= 0.48 && v >= 0.36 && (red
                        ? (h <= 0.075 || h >= 0.925)
                        : (h >= 0.50 && h <= 0.70));
                if (hit) { row++; coloured++; run++; longest = Math.max(longest, run); }
                else run = 0;
            }
            bestRow = Math.max(bestRow, Math.max(row, longest));
        }
        double fraction = coloured / (double)total;
        // A real health bar is a narrow run. A menu's blue background can fill
        // an entire lower quadrant and previously scored as a perfect tower.
        if (fraction > 0.28) return 0.04;
        double density = ColorMath.clamp(fraction / 0.075, 0.0, 1.0);
        double horizontal = ColorMath.clamp((bestRow - 2.0) / 12.0, 0.0, 1.0);
        return density * 0.38 + horizontal * 0.62;
    }

    private double arenaCue(PixelFrame f) {
        final int cols = 24, rows = 28;
        double sum = 0.0, sumSq = 0.0, sat = 0.0, gradient = 0.0, symmetry = 0.0;
        int n = 0;
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            double nx = 0.055 + (x + 0.5) / cols * 0.89;
            double ny = 0.12 + (y + 0.5) / rows * 0.61;
            int rgb = f.rgbNormalized(nx, ny);
            double l = ColorMath.luminance(rgb);
            sum += l;
            sumSq += l * l;
            sat += ColorMath.saturation(rgb);
            double l2 = ColorMath.luminance(f.rgbNormalized(Math.min(0.95, nx + 0.018), ny));
            gradient += Math.abs(l2 - l);
            double mirror = ColorMath.luminance(f.rgbNormalized(1.0 - nx, ny));
            symmetry += 1.0 - Math.min(1.0, Math.abs(mirror - l) / 0.46);
            n++;
        }
        double mean = sum / n;
        double texture = Math.sqrt(Math.max(0.0, sumSq / n - mean * mean));
        sat /= n;
        gradient /= n;
        symmetry /= n;
        double textureScore = ColorMath.clamp((texture - 0.055) / 0.18, 0.0, 1.0);
        double colorScore = ColorMath.clamp((sat - 0.10) / 0.38, 0.0, 1.0);
        double edgeScore = ColorMath.clamp((gradient - 0.025) / 0.16, 0.0, 1.0);
        double brightness = mean >= 0.12 && mean <= 0.82 ? 1.0 : 0.25;
        return textureScore * 0.30 + colorScore * 0.20 + edgeScore * 0.25
                + symmetry * 0.15 + brightness * 0.10;
    }
}
