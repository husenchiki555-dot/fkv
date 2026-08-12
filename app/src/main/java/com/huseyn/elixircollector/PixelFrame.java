package com.huseyn.elixircollector;

/**
 * Minimal RGB frame abstraction used by both Android MediaProjection frames and
 * deterministic desktop/unit-test replay frames.
 */
public interface PixelFrame {
    int width();
    int height();
    int rgb(int x, int y);

    default int rgbNormalized(double x, double y) {
        int px = (int)Math.round(ColorMath.clamp(x, 0.0, 1.0) * (width() - 1));
        int py = (int)Math.round(ColorMath.clamp(y, 0.0, 1.0) * (height() - 1));
        return rgb(px, py);
    }
}

final class ColorMath {
    private ColorMath() {}

    static int red(int rgb) { return (rgb >>> 16) & 255; }
    static int green(int rgb) { return (rgb >>> 8) & 255; }
    static int blue(int rgb) { return rgb & 255; }

    static double luminance(int rgb) {
        return (red(rgb) * 0.299 + green(rgb) * 0.587 + blue(rgb) * 0.114) / 255.0;
    }

    static double saturation(int rgb) {
        double r = red(rgb) / 255.0;
        double g = green(rgb) / 255.0;
        double b = blue(rgb) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        return max <= 1e-6 ? 0.0 : (max - min) / max;
    }

    /** Hue in [0, 1), matching Android/HSV hue divided by 360. */
    static double hue(int rgb) {
        double r = red(rgb) / 255.0;
        double g = green(rgb) / 255.0;
        double b = blue(rgb) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double d = max - min;
        if (d < 1e-6) return 0.0;
        double h;
        if (max == r) h = ((g - b) / d) % 6.0;
        else if (max == g) h = (b - r) / d + 2.0;
        else h = (r - g) / d + 4.0;
        h /= 6.0;
        return h < 0.0 ? h + 1.0 : h;
    }

    static double value(int rgb) {
        return Math.max(red(rgb), Math.max(green(rgb), blue(rgb))) / 255.0;
    }

    static double hueDistance(double a, double b) {
        double d = Math.abs(a - b);
        return Math.min(d, 1.0 - d);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
