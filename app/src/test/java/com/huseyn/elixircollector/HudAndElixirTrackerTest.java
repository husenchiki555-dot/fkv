package com.huseyn.elixircollector;

import org.junit.Test;

import static org.junit.Assert.*;

public class HudAndElixirTrackerTest {
    @Test public void measuredPhoneProfileIsFoundAndTrackedAdaptively() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        HudLayoutTracker tracker = new HudLayoutTracker();
        HudLayoutTracker.Observation observation = null;
        for (int i = 0; i < 5; i++) observation = tracker.update(frame);
        assertNotNull(observation);
        assertEquals(HudLayoutTracker.State.LOCKED, observation.state);
        assertTrue(observation.handStructureScore >= 0.49);
        assertEquals(0.24, observation.layout.handCenters[0], 0.055);
        assertEquals(0.70, observation.layout.handCenters[3], 0.065);
        assertTrue(observation.layout.handTop >= 0.79 && observation.layout.handTop <= 0.91);
    }

    @Test public void elixirLocksWithoutAssumingStartAndPassesSharpSpendQuickly() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);
        assertNotNull(hud);

        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 7; i++) {
            reading = tracker.update(frame, hud, time += 100);
        }
        assertNotNull(reading);
        assertTrue(reading.locked());
        assertEquals(7.0, reading.value, 0.85);

        frame.setFill(0.30);
        boolean sawDrop = false;
        for (int i = 0; i < 3; i++) {
            reading = tracker.update(frame, hud, time += 100);
            sawDrop |= reading.sharpDrop;
        }
        assertTrue("A real spend must not be smoothed into a slow transition", sawDrop);
        assertTrue(reading.value < 4.2);
    }
}
