package com.huseyn.elixircollector;

final class SyntheticFrame implements PixelFrame {
    private final int width;
    private final int height;
    private double fill = 0.70;
    private final double[] centers = {0.24, 0.40, 0.55, 0.70};

    SyntheticFrame(int width, int height) { this.width = width; this.height = height; }
    void setFill(double fill) { this.fill = Math.max(0.0, Math.min(1.0, fill)); }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override public int rgb(int x, int y) {
        double nx = x / (double)Math.max(1, width - 1);
        double ny = y / (double)Math.max(1, height - 1);
        if (ny >= 0.955 && ny <= 0.965 && nx >= 0.15 && nx <= 0.84) {
            double pos = (nx - 0.15) / 0.69;
            if (pos <= fill) return rgb(190, 42, 226);
            return rgb(42, 28, 52);
        }
        if (ny >= 0.82) {
            for (int i = 0; i < centers.length; i++) {
                if (Math.abs(nx - centers[i]) <= 0.052 && ny >= 0.84 && ny <= 0.93) {
                    int gx = (int)(nx * width / 15.0);
                    int gy = (int)(ny * height / 18.0);
                    int shift = (gx + gy + i * 3) & 3;
                    if (shift == 0) return rgb(225 - i * 22, 72 + i * 25, 105 + i * 19);
                    if (shift == 1) return rgb(55 + i * 31, 155 - i * 13, 222 - i * 24);
                    if (shift == 2) return rgb(225, 186 - i * 20, 53 + i * 17);
                    return rgb(56, 45, 76);
                }
            }
            return rgb(17, 14, 24);
        }
        int wave = (int)((Math.sin(nx * 31.0) + Math.cos(ny * 37.0)) * 18.0);
        return rgb(70 + wave, 112 + wave / 2, 78 - wave / 3);
    }

    private static int rgb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g << 8) | b;
    }
}
