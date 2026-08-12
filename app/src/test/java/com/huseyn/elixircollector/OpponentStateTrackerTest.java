package com.huseyn.elixircollector;

import org.junit.Test;

import static org.junit.Assert.*;

public class OpponentStateTrackerTest {
    @Test public void hiddenElixirStartsUnknownRatherThanAtFive() {
        OpponentStateTracker tracker = new OpponentStateTracker();
        tracker.start(1_000);
        OpponentStateTracker.Estimate estimate = tracker.estimate(1_000);
        assertTrue(Double.isNaN(estimate.best));
        assertEquals(0.0, estimate.min, 0.001);
        assertEquals(10.0, estimate.max, 0.001);
        assertFalse(estimate.anchored);
    }

    @Test public void cleanVisibleAnchorAndObservedSpendMaintainInterval() {
        OpponentStateTracker tracker = new OpponentStateTracker();
        tracker.start(1_000);
        assertTrue(tracker.anchorFromLocal(1_000, 7.2, 0.90, true));
        tracker.setRegenRate(new RegenRateEstimator.Estimate(0.50, 0.47, 0.53, 0.90));
        OpponentStateTracker.Estimate before = tracker.estimate(3_000);
        assertEquals(8.2, before.best, 0.08);
        tracker.onOpponentCard(3_000, null, 4, 0.0, 0.82);
        OpponentStateTracker.Estimate after = tracker.estimate(3_000);
        assertEquals(4.2, after.best, 0.12);
        assertTrue(after.min <= after.best && after.best <= after.max);
    }

    @Test public void unknownPlaysAdvanceCycleWithoutInventingIdentity() {
        OpponentStateTracker tracker = new OpponentStateTracker();
        tracker.start(1_000);
        tracker.onOpponentCard(1_100, "miner", 3, 0.95, 0.85);
        tracker.onOpponentCard(1_200, null, 2, 0.0, 0.80);
        tracker.onOpponentCard(1_300, null, 4, 0.0, 0.80);
        tracker.onOpponentCard(1_400, null, 3, 0.0, 0.80);
        assertEquals(4, tracker.cycleCount());
        assertEquals(1, tracker.knownCount());
        assertEquals(1, tracker.deckSlots().get(0).cardsUntilReturn);
        tracker.onOpponentCard(1_500, null, 1, 0.0, 0.80);
        assertEquals(0, tracker.deckSlots().get(0).cardsUntilReturn);
        assertTrue(tracker.canPlay("miner"));
    }

    @Test public void cycleRejectedIdentityCannotLeakItsCostIntoUnknownEvent() {
        OpponentStateTracker tracker = new OpponentStateTracker();
        tracker.start(1_000);
        tracker.onOpponentCard(1_100, "miner", 3, 0.95, 0.90);
        OpponentStateTracker.Event rejected = tracker.onOpponentCard(
                1_300, "miner", 0, 0.95, 0.80);
        assertNotNull(rejected);
        assertNull(rejected.cardId);
        assertEquals(0, rejected.costMin);
        assertEquals(10, rejected.costMax);
    }
}
