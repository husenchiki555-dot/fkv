package com.huseyn.elixircollector;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class EventFusionEngineTest {
    @Test public void handAndElixirPairIsLocalAndSuppressesEnemyMutation() {
        EventFusionEngine engine = new EventFusionEngine();
        HandStateTracker.Observation hand = new HandStateTracker.Observation(true, true,
                1, "miner", 0.86, 1_000);
        ElixirBarTracker.Reading drop = new ElixirBarTracker.Reading(
                ElixirBarTracker.State.LOCKED, 4.0, 4.0, 0.90, 0.85,
                null, true, 3.0, 1_080);
        ArenaMotionDetector.Observation arena = new ArenaMotionDetector.Observation(
                0.04, 0.75, 0.82, true, false, 0.45, 0.48,
                new FrameRect(0.40,0.42,0.50,0.55), 2, 1_100);
        CostBadgeDetector.Detection badge = new CostBadgeDetector.Detection(3, 0.85,
                500, 1100, 0.46, 0.46, new FrameRect(0.42,0.43,0.49,0.51), 1_100);
        List<EventFusionEngine.GameEvent> events = engine.update(true, hand, drop, arena,
                badge, null, 1_120);
        assertEquals(1, events.size());
        assertEquals(EventFusionEngine.Kind.LOCAL_CARD, events.get(0).kind);
        assertEquals("miner", events.get(0).localCardId);
    }

    @Test public void arenaPlusCostBadgeCanCommitUnknownOpponentCard() {
        EventFusionEngine engine = new EventFusionEngine();
        ArenaMotionDetector.Observation arena = new ArenaMotionDetector.Observation(
                0.04, 0.82, 0.90, true, false, 0.46, 0.40,
                new FrameRect(0.40,0.34,0.51,0.48), 3, 2_000);
        CostBadgeDetector.Detection badge = new CostBadgeDetector.Detection(4, 0.92,
                500, 970, 0.46, 0.40, new FrameRect(0.42,0.36,0.50,0.45), 2_000);
        List<EventFusionEngine.GameEvent> events = engine.update(true, null, null,
                arena, badge, null, 2_050);
        assertEquals(1, events.size());
        assertEquals(EventFusionEngine.Kind.OPPONENT_CARD, events.get(0).kind);
        assertEquals(4, events.get(0).cost);
        assertNull(events.get(0).localCardId);
    }
}
