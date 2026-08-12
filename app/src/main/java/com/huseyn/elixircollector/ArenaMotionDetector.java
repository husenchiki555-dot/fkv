package com.huseyn.elixircollector;

import java.util.ArrayDeque;

/** Coarse connected motion/change detector for the battlefield only. */
public final class ArenaMotionDetector {
    public static final class Observation {
        public final double changedFraction;
        public final double concentration;
        public final double score;
        public final boolean deploymentLike;
        public final boolean globalTransition;
        public final double centerX;
        public final double centerY;
        public final FrameRect region;
        public final int persistenceFrames;
        public final long timeMs;

        Observation(double changed, double concentration, double score,
                    boolean deploymentLike, boolean globalTransition,
                    double centerX, double centerY, FrameRect region,
                    int persistenceFrames, long timeMs) {
            changedFraction = changed;
            this.concentration = concentration;
            this.score = score;
            this.deploymentLike = deploymentLike;
            this.globalTransition = globalTransition;
            this.centerX = centerX;
            this.centerY = centerY;
            this.region = region;
            this.persistenceFrames = persistenceFrames;
            this.timeMs = timeMs;
        }
    }

    private static final int COLS = 28;
    private static final int ROWS = 34;
    private float[] previous;
    private double noise = 0.035;
    private double previousCenterX = Double.NaN;
    private double previousCenterY = Double.NaN;
    private int persistence;
    private long previousCandidateMs;

    public Observation update(PixelFrame frame, long nowMs) {
        if (frame == null) return empty(nowMs);
        float[] current = new float[COLS * ROWS * 3];
        double[] diff = new double[COLS * ROWS];
        double meanDiff = 0.0;
        int pos = 0;
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            double nx = 0.045 + (x + 0.5) / COLS * 0.91;
            double ny = 0.105 + (y + 0.5) / ROWS * 0.655;
            int rgb = frame.rgbNormalized(nx, ny);
            current[pos] = ColorMath.red(rgb) / 255f;
            current[pos + 1] = ColorMath.green(rgb) / 255f;
            current[pos + 2] = ColorMath.blue(rgb) / 255f;
            int cell = y * COLS + x;
            if (previous != null) {
                double dr = current[pos] - previous[pos];
                double dg = current[pos + 1] - previous[pos + 1];
                double db = current[pos + 2] - previous[pos + 2];
                diff[cell] = Math.sqrt((dr * dr + dg * dg + db * db) / 3.0);
                meanDiff += diff[cell];
            }
            pos += 3;
        }
        float[] old = previous;
        previous = current;
        if (old == null) return empty(nowMs);
        meanDiff /= COLS * ROWS;
        double threshold = Math.max(0.105, noise * 2.55 + 0.040);
        boolean[] active = new boolean[COLS * ROWS];
        int activeCount = 0;
        for (int i = 0; i < active.length; i++) if (diff[i] >= threshold) {
            active[i] = true;
            activeCount++;
        }
        double changed = activeCount / (double)active.length;
        boolean global = changed >= 0.31 || meanDiff >= 0.25;
        if (!global) noise = ColorMath.clamp(noise * 0.94 + Math.min(meanDiff, 0.13) * 0.06, 0.015, 0.095);
        if (activeCount == 0 || global) {
            persistence = 0;
            return new Observation(changed, 0.0, 0.0, false, global,
                    Double.NaN, Double.NaN, null, 0, nowMs);
        }

        boolean[] visited = new boolean[active.length];
        int bestSize = 0, bestMinX = 0, bestMaxX = 0, bestMinY = 0, bestMaxY = 0;
        double bestMagnitude = 0.0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < active.length; start++) {
            if (!active[start] || visited[start]) continue;
            visited[start] = true;
            queue.add(start);
            int size = 0, minX = COLS, maxX = 0, minY = ROWS, maxY = 0;
            double magnitude = 0.0;
            while (!queue.isEmpty()) {
                int i = queue.removeFirst();
                int x = i % COLS, y = i / COLS;
                size++;
                magnitude += diff[i];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int xx = x + dx, yy = y + dy;
                    if (xx < 0 || xx >= COLS || yy < 0 || yy >= ROWS) continue;
                    int ni = yy * COLS + xx;
                    if (active[ni] && !visited[ni]) { visited[ni] = true; queue.add(ni); }
                }
            }
            if (size > bestSize || (size == bestSize && magnitude > bestMagnitude)) {
                bestSize = size;
                bestMagnitude = magnitude;
                bestMinX = minX; bestMaxX = maxX; bestMinY = minY; bestMaxY = maxY;
            }
        }

        double concentration = bestSize / (double)Math.max(1, activeCount);
        double cx = 0.045 + ((bestMinX + bestMaxX + 1.0) * 0.5 / COLS) * 0.91;
        double cy = 0.105 + ((bestMinY + bestMaxY + 1.0) * 0.5 / ROWS) * 0.655;
        FrameRect region = new FrameRect(
                0.045 + bestMinX / (double)COLS * 0.91,
                0.105 + bestMinY / (double)ROWS * 0.655,
                0.045 + (bestMaxX + 1.0) / COLS * 0.91,
                0.105 + (bestMaxY + 1.0) / ROWS * 0.655);
        double magnitude = bestMagnitude / Math.max(1, bestSize);
        double sizeScore = ColorMath.clamp((bestSize - 1.0) / 18.0, 0.0, 1.0);
        if (bestSize > 90) sizeScore *= 0.55;
        double changeScore = changed <= 0.22
                ? ColorMath.clamp((changed - 0.002) / 0.080, 0.0, 1.0)
                : ColorMath.clamp((0.31 - changed) / 0.09, 0.0, 1.0);
        double magnitudeScore = ColorMath.clamp((magnitude - threshold) / 0.22, 0.0, 1.0);

        if (!Double.isNaN(previousCenterX) && nowMs - previousCandidateMs <= 420
                && Math.hypot(cx - previousCenterX, cy - previousCenterY) <= 0.105) persistence++;
        else persistence = 1;
        previousCenterX = cx;
        previousCenterY = cy;
        previousCandidateMs = nowMs;
        double persistenceScore = ColorMath.clamp((persistence - 1.0) / 2.0, 0.0, 1.0);
        double score = concentration * 0.28 + sizeScore * 0.18 + changeScore * 0.20
                + magnitudeScore * 0.22 + persistenceScore * 0.12;
        boolean deployment = score >= 0.54 && bestSize >= 2 && changed <= 0.24;
        return new Observation(changed, concentration, ColorMath.clamp(score, 0.0, 1.0),
                deployment, false, cx, cy, region, persistence, nowMs);
    }

    public void reset() {
        previous = null;
        noise = 0.035;
        previousCenterX = previousCenterY = Double.NaN;
        persistence = 0;
        previousCandidateMs = 0L;
    }

    private Observation empty(long nowMs) {
        return new Observation(0,0,0,false,false,Double.NaN,Double.NaN,null,0,nowMs);
    }
}
