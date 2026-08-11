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

        Signals(double hand, double elixir, double timer, double crown, double arena,
                double stability, double audio, double composite, int sensors) {
            handScore = hand;
            elixirScore = elixir;
            timerScore = timer;
            crownScore = crown;
            arenaScore = arena;
            layoutStability = stability;
            audioScore = audio;
            this.composite = composite;
            corroboratingSensors = sensors;
        }
    }

    public Signals detect(PixelFrame frame, HudLayoutTracker.Observation hud,
                          ElixirBarTracker.Reading elixir, boolean audioAvailable) {
        if (frame == null) return new Signals(0,0,0,0,0,0,0,0,0);
        double hand = hud == null ? 0.0 : hud.handStructureScore;
        double rail = elixir == null ? 0.0 : elixir.confidence;
        double timer = timerCue(frame);
        double crown = crownCue(frame);
        double arena = arenaCue(frame);
        double stability = hud == null || hud.layout == null ? 0.0 : hud.layout.stability;
        if (hud != null && hud.state == HudLayoutTracker.State.LOCKED) stability = Math.max(stability, 0.72);
        double audio = audioAvailable ? 0.35 : 0.0; // Supporting only; never sufficient.

        int sensors = 0;
        if (rail >= 0.43) sensors++;
        if (timer >= 0.48) sensors++;
        if (crown >= 0.42) sensors++;
        if (arena >= 0.48) sensors++;
        if (stability >= 0.58) sensors++;
        if (audio >= 0.50) sensors++;

        double composite = hand * 0.35 + rail * 0.17 + timer * 0.13 + crown * 0.11
                + arena * 0.14 + stability * 0.08 + audio * 0.02;
        return new Signals(hand, rail, timer, crown, arena, stability, audio,
                ColorMath.clamp(composite, 0.0, 1.0), sensors);
    }

    private double timerCue(PixelFrame f) {
        FrameRect r = new FrameRect(0.32, 0.015, 0.68, 0.155);
        final int cols = 42, rows = 22;
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
        double glyph = ColorMath.clamp((brightRatio - 0.010) / 0.12, 0.0, 1.0);
        if (brightRatio > 0.34) glyph *= 0.35;
        double grouping = groups >= 2 && groups <= 12 ? 1.0 : (groups == 1 || groups <= 16 ? 0.42 : 0.0);
        double contrast = ColorMath.clamp((darkRatio + edgeRatio * 1.8 - 0.12) / 0.62, 0.0, 1.0);
        return glyph * 0.48 + grouping * 0.26 + contrast * 0.26;
    }

    private double crownCue(PixelFrame f) {
        int red = 0, blue = 0, gold = 0, total = 0;
        // Crown/tower panels can move slightly between aspect ratios; sample both
        // upper corners and the narrow side bands rather than one fixed icon.
        for (int gy = 0; gy < 30; gy++) {
            double y = 0.035 + (gy + 0.5) / 30.0 * 0.235;
            for (int gx = 0; gx < 28; gx++) {
                double x = (gx + 0.5) / 28.0 * 0.31;
                int[] colors = {f.rgbNormalized(x, y), f.rgbNormalized(1.0 - x, y)};
                for (int rgb : colors) {
                    double h = ColorMath.hue(rgb), s = ColorMath.saturation(rgb), v = ColorMath.value(rgb);
                    if (s >= 0.42 && v >= 0.34) {
                        if (h <= 0.075 || h >= 0.94) red++;
                        if (h >= 0.50 && h <= 0.70) blue++;
                        if (h >= 0.10 && h <= 0.18 && v >= 0.55) gold++;
                    }
                    total++;
                }
            }
        }
        double colorSupport = ColorMath.clamp((red + blue) / (double)Math.max(1, total) / 0.065, 0.0, 1.0);
        double both = Math.min(red, blue) >= Math.max(3, total / 800) ? 1.0 : 0.32;
        double crownGold = ColorMath.clamp(gold / (double)Math.max(1, total) / 0.028, 0.0, 1.0);
        return colorSupport * 0.53 + both * 0.24 + crownGold * 0.23;
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
