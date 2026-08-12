package com.huseyn.elixircollector;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Owns one capture session and is the only component allowed to mutate opponent state. */
public final class GameSessionEngine {
    private final FrameAnalyzer analyzer;
    private final EventFusionEngine fusion = new EventFusionEngine();
    private final OpponentStateTracker opponent = new OpponentStateTracker();
    private final RegenRateEstimator regen = new RegenRateEstimator();
    private final AudioProfileBank audioProfiles = new AudioProfileBank();
    private final OpponentCardIdentifier identifier;
    private int sessionId;
    private boolean preBattleActivity;
    private boolean wasCandidatePhase;
    private boolean cleanAnchorWindow;
    private long eventWarmupUntil;
    private String transientStatus = "";
    private long transientStatusUntil;

    public GameSessionEngine(Context context) {
        analyzer = new FrameAnalyzer(context);
        identifier = new OpponentCardIdentifier(context, audioProfiles);
    }

    public SessionSnapshot process(PixelFrame frame, long nowMs,
                                   boolean audioAvailable,
                                   AudioEvidenceEngine.Fingerprint latestAudio) {
        FrameAnalyzer.Result vision = analyzer.analyze(frame, nowMs, audioAvailable);
        boolean candidatePhase = vision.battle.state == BattleStateMachine.State.BATTLE_CANDIDATE
                || vision.battle.state == BattleStateMachine.State.VERIFYING;
        if (!candidatePhase && wasCandidatePhase
                && vision.battle.state == BattleStateMachine.State.OUTSIDE_BATTLE) {
            // A rejected candidate must not poison the opening anchor of the
            // next real match with stale intro/menu activity.
            preBattleActivity = false;
        }
        if (candidatePhase && ((vision.handTransition != null && vision.handTransition.changed)
                || (vision.elixir != null && vision.elixir.sharpDrop)
                || (vision.badge != null && vision.badge.confidence >= 0.55))) preBattleActivity = true;
        wasCandidatePhase = candidatePhase;

        if (vision.battle.enteredBattle) {
            sessionId++;
            opponent.start(nowMs);
            regen.reset();
            fusion.reset();
            audioProfiles.clear();
            cleanAnchorWindow = !preBattleActivity;
            preBattleActivity = false;
            // Candidate entry already reset menu history, and the VERIFYING
            // phase built a clean hand baseline. Accept the first real play;
            // a fixed post-entry warm-up dropped fast opening deployments.
            eventWarmupUntil = nowMs;
            setTransient("MATCH FOUND", nowMs, 900);
        }

        boolean inBattle = vision.battle.state == BattleStateMachine.State.IN_BATTLE
                || vision.battle.state == BattleStateMachine.State.END_CANDIDATE;
        if (inBattle && opponent.isActive()) {
            RegenRateEstimator.Estimate rate = regen.update(vision.elixir);
            opponent.setRegenRate(rate);
            boolean activityNow = (vision.handTransition != null && vision.handTransition.changed)
                    || (vision.elixir != null && vision.elixir.sharpDrop)
                    || (vision.badge != null && vision.badge.confidence >= 0.55);
            if (vision.elixir != null && vision.elixir.locked()) {
                opponent.anchorFromLocal(nowMs, vision.elixir.value,
                        vision.elixir.confidence, cleanAnchorWindow && !activityNow);
            }

            boolean eventWindowOpen = nowMs >= eventWarmupUntil;
            List<EventFusionEngine.GameEvent> events = fusion.update(eventWindowOpen,
                    eventWindowOpen ? vision.handTransition : null,
                    eventWindowOpen ? vision.elixir : null,
                    eventWindowOpen ? vision.arena : null,
                    eventWindowOpen ? vision.badge : null,
                    eventWindowOpen ? latestAudio : null, nowMs);
            for (EventFusionEngine.GameEvent event : events) apply(event, frame, nowMs);
            if (activityNow) cleanAnchorWindow = false;
        } else {
            fusion.update(false, vision.handTransition, vision.elixir, vision.arena,
                    vision.badge, latestAudio, nowMs);
        }

        if (vision.battle.exitedBattle) {
            opponent.reset();
            regen.reset();
            fusion.reset();
            audioProfiles.clear();
            cleanAnchorWindow = false;
            eventWarmupUntil = 0L;
            setTransient("MATCH ENDED", nowMs, 1200);
        }
        return snapshot(vision, nowMs, audioAvailable);
    }

    public void resetCapture() {
        analyzer.resetCapture();
        fusion.reset();
        opponent.reset();
        regen.reset();
        audioProfiles.clear();
        identifier.clearVisualCache();
        preBattleActivity = wasCandidatePhase = cleanAnchorWindow = false;
        eventWarmupUntil = 0L;
        transientStatus = "";
        transientStatusUntil = 0L;
    }

    private void apply(EventFusionEngine.GameEvent event, PixelFrame frame, long nowMs) {
        if (event.kind == EventFusionEngine.Kind.LOCAL_CARD) {
            if (event.localCardId != null && event.audio != null && event.confidence >= 0.62) {
                audioProfiles.learn(event.localCardId, event.audio);
            }
            CardCatalog.Card card = CardCatalog.find(event.localCardId);
            setTransient(card == null ? "YOUR PLAY" : "YOUR " + card.displayName.toUpperCase(),
                    nowMs, 1350);
            return;
        }
        if (event.kind == EventFusionEngine.Kind.LOCAL_ABILITY) {
            setTransient("YOUR ABILITY −" + event.cost, nowMs, 1100);
            return;
        }
        OpponentCardIdentifier.Identification identity = identifier.identify(frame,
                event.visualRegion, event.cost, event.audio, opponent);
        String id = identity == null ? null : identity.cardId;
        double identityConfidence = identity == null ? 0.0 : identity.confidence;
        opponent.onOpponentCard(event.timeMs, id, event.cost, identityConfidence, event.confidence);
        if (identity == null) setTransient(event.cost > 0
                ? "ENEMY ? −" + event.cost : "ENEMY PLAY • COST ?", nowMs, 1450);
        else {
            CardCatalog.Card card = CardCatalog.find(identity.cardId);
            setTransient("ENEMY " + (card == null ? identity.cardId : card.displayName).toUpperCase(),
                    nowMs, 1600);
        }
    }

    private SessionSnapshot snapshot(FrameAnalyzer.Result v, long nowMs, boolean audioAvailable) {
        OpponentStateTracker.Estimate estimate = opponent.isActive()
                ? opponent.estimate(nowMs)
                : new OpponentStateTracker.Estimate(Double.NaN, 0.0, 10.0, 0.0, false);
        List<OpponentStateTracker.DeckSlot> deck = opponent.isActive()
                ? opponent.deckSlots() : unknownDeck();
        String status;
        if (nowMs < transientStatusUntil && !transientStatus.isEmpty()) status = transientStatus;
        else if (v.battle.state == BattleStateMachine.State.OUTSIDE_BATTLE) {
            status = "SEARCH • HAND " + percent(v.battleSignals.handScore)
                    + " • RAIL " + percent(v.battleSignals.elixirScore)
                    + " • TIMER " + percent(v.battleSignals.timerScore);
        } else if (v.battle.state == BattleStateMachine.State.BATTLE_CANDIDATE) status = "BATTLE CANDIDATE";
        else if (v.battle.state == BattleStateMachine.State.VERIFYING) status = "VERIFYING BATTLE HUD";
        else if (v.elixir == null || !v.elixir.locked()) status = "MATCH FOUND • ELIXIR CALIBRATING";
        else if (!estimate.anchored) status = "MATCH FOUND • OPPONENT ELIXIR UNCERTAIN";
        else status = audioAvailable ? "AUTO WATCHING • SOUND+HAND+VISION" : "AUTO WATCHING • HAND+VISION";

        return new SessionSnapshot(sessionId, nowMs, true, audioAvailable,
                v.battle.state.name(), v.battle.confidence,
                v.hud == null ? "SEARCHING" : v.hud.state.name(),
                v.battleSignals.handScore, v.hand == null ? 0 : v.hand.strongSlots,
                v.elixir == null ? "SEARCHING" : v.elixir.state.name(),
                v.elixir == null ? Double.NaN : v.elixir.value,
                v.elixir == null ? 0.0 : v.elixir.confidence,
                estimate, deck, status, opponent.lastLabel(),
                v.battleSignals.timerScore, v.battleSignals.crownScore,
                v.battleSignals.arenaScore);
    }

    private static List<OpponentStateTracker.DeckSlot> unknownDeck() {
        ArrayList<OpponentStateTracker.DeckSlot> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) out.add(new OpponentStateTracker.DeckSlot(null, "Unknown", -1, 0.0));
        return out;
    }

    private void setTransient(String text, long nowMs, long durationMs) {
        transientStatus = text;
        transientStatusUntil = nowMs + durationMs;
    }

    private static int percent(double value) { return (int)Math.round(ColorMath.clamp(value, 0.0, 1.0) * 100.0); }
}
