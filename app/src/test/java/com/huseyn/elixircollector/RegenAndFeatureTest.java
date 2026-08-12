package com.huseyn.elixircollector;

import org.junit.Test;

import static org.junit.Assert.*;

public class RegenAndFeatureTest {
    @Test public void learnsRegenerationFromVisibleSlope() {
        RegenRateEstimator estimator = new RegenRateEstimator();
        for (int i = 0; i < 10; i++) {
            double value = 3.0 + i * 0.10;
            estimator.update(new ElixirBarTracker.Reading(ElixirBarTracker.State.LOCKED,
                    value, value, 0.90, 0.90, null, false, 0.0, 1_000 + i * 280L));
        }
        RegenRateEstimator.Estimate estimate = estimator.current();
        assertTrue(estimate.confidence > 0.35);
        assertEquals(0.357, estimate.bestPerSecond, 0.10);
    }

    @Test public void richerFeaturesSeparateDifferentArtworkPatterns() {
        PixelFrame a = pattern(false);
        PixelFrame b = pattern(false);
        PixelFrame c = pattern(true);
        FrameRect full = new FrameRect(0,0,1,1);
        CardVisualFeatures.Feature fa = CardVisualFeatures.extract(a, full);
        CardVisualFeatures.Feature fb = CardVisualFeatures.extract(b, full);
        CardVisualFeatures.Feature fc = CardVisualFeatures.extract(c, full);
        assertEquals(1.0, CardVisualFeatures.similarity(fa, fb), 0.0001);
        assertTrue(CardVisualFeatures.similarity(fa, fc) < 0.88);
    }

    @Test public void learnsTemporaryHighSpeedRegeneration() {
        RegenRateEstimator estimator = new RegenRateEstimator();
        for (int i = 0; i < 10; i++) {
            double value = 1.0 + i * 0.50;
            estimator.update(new ElixirBarTracker.Reading(ElixirBarTracker.State.LOCKED,
                    value, value, 0.92, 0.92, null, false, 0.0, 1_000 + i * 250L));
        }
        assertTrue(estimator.current().confidence > 0.35);
        assertEquals(2.0, estimator.current().bestPerSecond, 0.25);
    }

    private static PixelFrame pattern(final boolean swapped) {
        return new PixelFrame() {
            @Override public int width() { return 160; }
            @Override public int height() { return 200; }
            @Override public int rgb(int x, int y) {
                boolean block = ((x / 20) + (y / 20)) % 2 == 0;
                if (swapped) block = !block;
                return block ? 0xe2364f : 0x286fdd;
            }
        };
    }
}
