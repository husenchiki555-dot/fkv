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
        assertEquals(0.313, observation.layout.handCenters[0], 0.045);
        assertEquals(0.867, observation.layout.handCenters[3], 0.055);
        assertTrue(observation.layout.handTop >= 0.70 && observation.layout.handTop <= 0.80);
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

    @Test public void losingGeometryClearsAStaleValueBeforeReacquisition() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        frame.setFill(0.10);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);
        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        assertNotNull(reading);
        assertEquals(1.0, reading.value, 0.85);

        for (int i = 0; i < 22; i++) reading = tracker.update(null, null, time += 100);
        assertNotNull(reading);
        assertFalse(reading.hasValue());

        frame.setFill(0.70);
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        assertTrue(reading.locked());
        assertEquals(7.0, reading.value, 0.85);
    }

    @Test public void emptyRailIsZeroAndHudTextCannotMasqueradeAsElixir() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);

        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        assertNotNull(reading);
        assertTrue(reading.locked());

        frame.setFill(0.0);
        frame.setLowerLabelNoise(true);
        boolean sawDrop = false;
        for (int i = 0; i < 4; i++) {
            reading = tracker.update(frame, hud, time += 100);
            sawDrop |= reading.sharpDrop;
        }
        assertTrue("A spend to empty must be emitted as a sharp drop", sawDrop);
        assertTrue(reading.locked());
        assertEquals(0.0, reading.value, 0.20);
        assertNotNull(reading.rail);
        assertTrue("Tracker drifted down into the Max:10 label",
                reading.rail.centerY() <= 0.986);

        frame.setBattleMessageNoise(true);
        for (int i = 0; i < 4; i++) reading = tracker.update(frame, hud, time += 100);
        assertTrue(reading.locked());
        assertEquals("Battle message was mistaken for filled Elixir",
                0.0, reading.value, 0.20);

        frame.setDeploymentFlash(true);
        for (int i = 0; i < 2; i++) reading = tracker.update(frame, hud, time += 100);
        assertTrue(reading.hasValue());
        assertTrue("Pale deployment flash was mistaken for two Elixir",
                reading.value <= 0.35);
    }

    @Test public void fullRailLocksAtTenDespiteLowerHudText() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        frame.setFill(1.0);
        frame.setLowerLabelNoise(true);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);

        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        assertNotNull(reading);
        assertTrue(reading.locked());
        assertEquals(10.0, reading.rawValue, 0.18);
        assertEquals(10.0, reading.value, 0.18);
        assertTrue(reading.rail.centerY() <= 0.986);
    }

    @Test public void verticalRailCalibrationMovesBeyondPhoneSeed() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        frame.setRailOffsetY(-0.012);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);

        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        assertNotNull(reading);
        assertTrue(reading.locked());
        assertEquals(7.0, reading.value, 0.85);
        assertNotNull(reading.rail);
        assertEquals("Rail remained hard-coded to the phone seed",
                0.9655, reading.rail.centerY(), 0.007);
    }

    @Test public void emptyBlueStripCannotInitializeARailByItself() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        frame.setFill(0.0);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);

        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 10; i++) reading = tracker.update(frame, hud, time += 100);
        assertNotNull(reading);
        assertFalse("A loading/deck strip initialized a fake zero rail", reading.locked());
        assertFalse(reading.hasValue());

        frame.setFill(0.70);
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        assertTrue(reading.locked());
        assertEquals(7.0, reading.value, 0.85);
    }
}
