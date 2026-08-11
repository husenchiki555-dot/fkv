package com.huseyn.elixircollector;

import java.util.Arrays;

/** Spatial colour, histogram, and gradient descriptor for tiny card artwork. */
public final class CardVisualFeatures {
    private static final int COLS = 8;
    private static final int ROWS = 10;
    private static final int HUE_BINS = 16;
    private static final int SAT_BINS = 8;
    private static final int LUM_BINS = 8;
    private static final int CELL_X = 4;
    private static final int CELL_Y = 5;
    private static final int ORIENTATIONS = 4;

    public static final class Feature {
        final float[] spatial;
        final float[] histogram;
        final float[] gradients;
        final double texture;

        Feature(float[] spatial, float[] histogram, float[] gradients, double texture) {
            this.spatial = spatial;
            this.histogram = histogram;
            this.gradients = gradients;
            this.texture = texture;
        }
    }

    public static Feature extract(PixelFrame frame, FrameRect rect) {
        if (frame == null || rect == null || rect.width() <= 0.005 || rect.height() <= 0.005) return null;
        double[][] lum = new double[ROWS][COLS];
        int[][] pixels = new int[ROWS][COLS];
        double mean = 0.0, meanSq = 0.0;
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            double nx = ColorMath.lerp(rect.left, rect.right, (x + 0.5) / COLS);
            double ny = ColorMath.lerp(rect.top, rect.bottom, (y + 0.5) / ROWS);
            int rgb = frame.rgbNormalized(nx, ny);
            pixels[y][x] = rgb;
            double l = ColorMath.luminance(rgb);
            lum[y][x] = l;
            mean += l;
            meanSq += l * l;
        }
        int n = COLS * ROWS;
        mean /= n;
        double sd = Math.sqrt(Math.max(0.0, meanSq / n - mean * mean));
        double norm = Math.max(0.075, sd);

        float[] spatial = new float[n * 3];
        float[] histogram = new float[HUE_BINS + SAT_BINS + LUM_BINS];
        int p = 0;
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            int rgb = pixels[y][x];
            double r = ColorMath.red(rgb) / 255.0;
            double g = ColorMath.green(rgb) / 255.0;
            double b = ColorMath.blue(rgb) / 255.0;
            double sum = r + g + b + 0.10;
            spatial[p++] = (float)(r / sum);
            spatial[p++] = (float)(g / sum);
            spatial[p++] = (float)ColorMath.clamp((lum[y][x] - mean) / norm / 3.0, -1.0, 1.0);
            int hb = Math.min(HUE_BINS - 1, (int)(ColorMath.hue(rgb) * HUE_BINS));
            int sb = Math.min(SAT_BINS - 1, (int)(ColorMath.saturation(rgb) * SAT_BINS));
            int lb = Math.min(LUM_BINS - 1, (int)(lum[y][x] * LUM_BINS));
            histogram[hb] += 1f;
            histogram[HUE_BINS + sb] += 1f;
            histogram[HUE_BINS + SAT_BINS + lb] += 1f;
        }
        normalizeL1(histogram, 0, HUE_BINS);
        normalizeL1(histogram, HUE_BINS, HUE_BINS + SAT_BINS);
        normalizeL1(histogram, HUE_BINS + SAT_BINS, histogram.length);

        float[] gradients = new float[CELL_X * CELL_Y * ORIENTATIONS];
        for (int y = 1; y < ROWS - 1; y++) for (int x = 1; x < COLS - 1; x++) {
            double gx = lum[y][x + 1] - lum[y][x - 1];
            double gy = lum[y + 1][x] - lum[y - 1][x];
            double mag = Math.sqrt(gx * gx + gy * gy);
            double angle = Math.atan2(gy, gx);
            if (angle < 0) angle += Math.PI;
            if (angle >= Math.PI) angle -= Math.PI;
            int orientation = Math.min(ORIENTATIONS - 1, (int)(angle / Math.PI * ORIENTATIONS));
            int cellX = Math.min(CELL_X - 1, x * CELL_X / COLS);
            int cellY = Math.min(CELL_Y - 1, y * CELL_Y / ROWS);
            gradients[(cellY * CELL_X + cellX) * ORIENTATIONS + orientation] += (float)mag;
        }
        normalizeL2(gradients);
        return new Feature(spatial, histogram, gradients, sd);
    }

    public static double similarity(Feature a, Feature b) {
        if (a == null || b == null) return 0.0;
        double spatialDistance = meanAbsolute(a.spatial, b.spatial);
        double histogramDistance = l1(a.histogram, b.histogram) * 0.5;
        double gradientSimilarity = cosine(a.gradients, b.gradients);
        double textureSimilarity = 1.0 - ColorMath.clamp(Math.abs(a.texture - b.texture) / 0.22, 0.0, 1.0);
        double spatialSimilarity = Math.exp(-spatialDistance * 4.2);
        double histogramSimilarity = 1.0 - ColorMath.clamp(histogramDistance / 3.0, 0.0, 1.0);
        return ColorMath.clamp(spatialSimilarity * 0.52 + histogramSimilarity * 0.18
                + gradientSimilarity * 0.24 + textureSimilarity * 0.06, 0.0, 1.0);
    }

    public static double visualDelta(Feature a, Feature b) {
        return 1.0 - similarity(a, b);
    }

    private static double meanAbsolute(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 1.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) sum += Math.abs(a[i] - b[i]);
        return sum / n;
    }

    private static double l1(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double sum = 0.0;
        for (int i = 0; i < n; i++) sum += Math.abs(a[i] - b[i]);
        return sum;
    }

    private static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0.0, aa = 0.0, bb = 0.0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        // Two equally flat descriptors are a match, not a disagreement.  This
        // matters for low-detail crops and prevents the gradient channel from
        // subtracting 24% from otherwise identical artwork.
        if (aa <= 1e-9 && bb <= 1e-9) return 1.0;
        if (aa <= 1e-9 || bb <= 1e-9) return 0.0;
        return ColorMath.clamp(dot / Math.sqrt(aa * bb), 0.0, 1.0);
    }

    private static void normalizeL1(float[] a, int start, int end) {
        float sum = 0f;
        for (int i = start; i < end; i++) sum += a[i];
        if (sum <= 1e-6f) return;
        for (int i = start; i < end; i++) a[i] /= sum;
    }

    private static void normalizeL2(float[] a) {
        double sum = 0.0;
        for (float v : a) sum += v * v;
        if (sum <= 1e-9) { Arrays.fill(a, 0f); return; }
        double d = Math.sqrt(sum);
        for (int i = 0; i < a.length; i++) a[i] /= d;
    }

    private CardVisualFeatures() {}
}
