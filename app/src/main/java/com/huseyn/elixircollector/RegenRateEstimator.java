package com.huseyn.elixircollector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/** Learns the active Elixir regeneration rate directly from the visible local bar. */
public final class RegenRateEstimator {
    public static final class Estimate {
        public final double bestPerSecond;
        public final double minPerSecond;
        public final double maxPerSecond;
        public final double confidence;

        Estimate(double best, double min, double max, double confidence) {
            bestPerSecond = best;
            minPerSecond = min;
            maxPerSecond = max;
            this.confidence = confidence;
        }
    }

    private static final class Sample {
        final long timeMs; final double value;
        Sample(long timeMs, double value) { this.timeMs = timeMs; this.value = value; }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ArrayDeque<Double> slopes = new ArrayDeque<>();
    private Estimate current = new Estimate(0.50, 0.24, 1.25, 0.0);

    public Estimate update(ElixirBarTracker.Reading reading) {
        if (reading == null || !reading.hasValue() || reading.confidence < 0.52
                || reading.value >= 9.72 || reading.sharpDrop) {
            if (reading != null && reading.sharpDrop) samples.clear();
            return current;
        }
        Sample now = new Sample(reading.timeMs, reading.value);
        for (Sample old : samples) {
            double dt = (now.timeMs - old.timeMs) / 1000.0;
            double dv = now.value - old.value;
            if (dt >= 0.42 && dt <= 2.80 && dv >= 0.075) {
                double slope = dv / dt;
                if (slope >= 0.18 && slope <= 1.38) slopes.addLast(slope);
            }
        }
        samples.addLast(now);
        while (!samples.isEmpty() && now.timeMs - samples.peekFirst().timeMs > 3200) samples.removeFirst();
        while (slopes.size() > 21) slopes.removeFirst();
        if (slopes.size() >= 4) {
            ArrayList<Double> sorted = new ArrayList<>(slopes);
            Collections.sort(sorted);
            double median = median(sorted);
            ArrayList<Double> deviations = new ArrayList<>();
            for (double v : sorted) deviations.add(Math.abs(v - median));
            Collections.sort(deviations);
            double mad = median(deviations);
            double countConfidence = ColorMath.clamp((slopes.size() - 3.0) / 10.0, 0.0, 1.0);
            double stability = 1.0 - ColorMath.clamp(mad / Math.max(0.08, median * 0.32), 0.0, 1.0);
            double confidence = countConfidence * 0.55 + stability * 0.45;
            double spread = Math.max(0.045, median * (0.10 + (1.0 - confidence) * 0.32));
            current = new Estimate(median, Math.max(0.16, median - spread),
                    Math.min(1.55, median + spread), confidence);
        }
        return current;
    }

    public Estimate current() { return current; }

    public void reset() {
        samples.clear();
        slopes.clear();
        current = new Estimate(0.50, 0.24, 1.25, 0.0);
    }

    private static double median(ArrayList<Double> sorted) {
        if (sorted.isEmpty()) return Double.NaN;
        int m = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(m - 1) + sorted.get(m)) * 0.5 : sorted.get(m);
    }
}
