package com.huseyn.elixircollector;

import android.content.Context;

/** Orchestrates independent CV observations. It owns no opponent game state. */
public final class FrameAnalyzer {
    public static final class Result {
        public final HudLayoutTracker.Observation hud;
        public final ElixirBarTracker.Reading elixir;
        public final BattleCueDetector.Signals battleSignals;
        public final BattleStateMachine.Update battle;
        public final HandCardRecognizer.Result hand;
        public final HandStateTracker.Observation handTransition;
        public final ArenaMotionDetector.Observation arena;
        public final CostBadgeDetector.Detection badge;

        Result(HudLayoutTracker.Observation hud, ElixirBarTracker.Reading elixir,
               BattleCueDetector.Signals signals, BattleStateMachine.Update battle,
               HandCardRecognizer.Result hand, HandStateTracker.Observation transition,
               ArenaMotionDetector.Observation arena, CostBadgeDetector.Detection badge) {
            this.hud = hud;
            this.elixir = elixir;
            battleSignals = signals;
            this.battle = battle;
            this.hand = hand;
            handTransition = transition;
            this.arena = arena;
            this.badge = badge;
        }
    }

    private final HudLayoutTracker hud = new HudLayoutTracker();
    private final ElixirBarTracker elixir = new ElixirBarTracker();
    private final BattleCueDetector cues = new BattleCueDetector();
    private final BattleStateMachine battle = new BattleStateMachine();
    private final HandCardRecognizer handRecognizer;
    private final HandStateTracker handTracker = new HandStateTracker();
    private final ArenaMotionDetector arena = new ArenaMotionDetector();
    private final CostBadgeDetector badge = new CostBadgeDetector();

    public FrameAnalyzer(Context context) { handRecognizer = new HandCardRecognizer(context); }

    public Result analyze(PixelFrame frame, long nowMs, boolean audioAvailable) {
        HudLayoutTracker.Observation hudObservation = hud.update(frame);
        ElixirBarTracker.Reading elixirReading = elixir.update(frame, hudObservation, nowMs);
        HandCardRecognizer.Result hand = handRecognizer.recognize(frame,
                hudObservation == null ? null : hudObservation.layout);
        BattleCueDetector.Signals signals = cues.detect(frame, hudObservation, elixirReading,
                audioAvailable, hand == null ? 0 : hand.strongSlots);
        BattleStateMachine.State previousBattleState = battle.state();
        BattleStateMachine.Update battleUpdate = battle.update(signals, nowMs);
        boolean newCandidate = previousBattleState == BattleStateMachine.State.OUTSIDE_BATTLE
                && battleUpdate.state == BattleStateMachine.State.BATTLE_CANDIDATE;
        if (newCandidate) {
            // Throw away menu geometry and event history as soon as strict
            // battle anchors appear. The following verification frames then
            // establish a clean hand baseline before the first playable frame.
            hud.resetForNewCapture();
            elixir.resetAll();
            handTracker.reset();
            arena.reset();
            badge.reset();
        }
        HandStateTracker.Observation transition = handTracker.update(newCandidate ? null : hand, nowMs);
        ArenaMotionDetector.Observation arenaObservation = arena.update(frame, nowMs);
        CostBadgeDetector.Detection cost = null;
        if (battleUpdate.state != BattleStateMachine.State.OUTSIDE_BATTLE
                && battleUpdate.state != BattleStateMachine.State.BATTLE_CANDIDATE) {
            cost = badge.detect(frame, nowMs);
        }

        Result result = new Result(hudObservation, elixirReading, signals, battleUpdate,
                hand, transition, arenaObservation, cost);
        if (battleUpdate.exitedBattle) {
            handTracker.reset();
            arena.reset();
            badge.reset();
            elixir.resetValueButKeepGeometry();
            hud.markBattleEnded();
        }
        return result;
    }

    public void reloadDeck() {
        handRecognizer.reloadDeck();
        handTracker.reset();
    }

    public void resetCapture() {
        hud.resetForNewCapture();
        elixir.resetAll();
        battle.reset();
        handTracker.reset();
        arena.reset();
        badge.reset();
        handRecognizer.reloadDeck();
    }
}
