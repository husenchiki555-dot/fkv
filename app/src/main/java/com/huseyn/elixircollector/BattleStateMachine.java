package com.huseyn.elixircollector;

/** Time-based multi-sensor battle detector. A missed frame cannot end a match. */
public final class BattleStateMachine {
    public enum State { OUTSIDE_BATTLE, BATTLE_CANDIDATE, VERIFYING, IN_BATTLE, END_CANDIDATE }

    public static final class Update {
        public final State state;
        public final boolean enteredBattle;
        public final boolean exitedBattle;
        public final double confidence;

        Update(State state, boolean entered, boolean exited, double confidence) {
            this.state = state;
            enteredBattle = entered;
            exitedBattle = exited;
            this.confidence = confidence;
        }
    }

    private State state = State.OUTSIDE_BATTLE;
    private long stateSince;
    private long lastPositive;
    private long lowSince;
    private double confidence;

    public Update update(BattleCueDetector.Signals s, long nowMs) {
        if (stateSince == 0L) stateSince = nowMs;
        boolean hand = s != null && s.handScore >= 0.50;
        boolean railAnchor = s != null && s.elixirScore >= 0.58;
        boolean recognitionAnchor = s != null && s.recognizedHandSlots >= 2;
        boolean timer = s != null && s.timerScore >= 0.55;
        boolean towers = s != null && s.crownScore >= 0.55;
        // Arena texture and layout stability support confidence, but they are
        // correlated/generic and cannot independently turn a menu into a match.
        // Without an Elixir rail, calibrated artwork must agree with BOTH clock
        // and tower layout; this keeps match detection separate from rail lock
        // without making two recognized cards sufficient on their own.
        boolean anchoredHud = railAnchor && (timer || towers)
                || recognitionAnchor && timer && towers;
        // Starting a session is intentionally stricter than sustaining one.
        // The deck-calibration screen in 10665.mp4 contains four artwork tiles,
        // a purple horizontal accent, and timer-like text; it can satisfy the
        // relaxed rail+timer gate for several seconds.  A real battle opening
        // also has the ordered red/blue tower-health layout, so require that
        // independent anchor before leaving OUTSIDE_BATTLE.  Once latched, the
        // normal relaxed gate tolerates tower bars being covered by effects.
        boolean entryAnchor = timer && towers && (railAnchor || recognitionAnchor);
        boolean positive = s != null && hand && anchoredHud && s.composite >= 0.50;
        boolean entryPositive = s != null && hand && entryAnchor && s.composite >= 0.50;
        boolean strong = s != null && hand && anchoredHud
                && (timer && towers || recognitionAnchor && (timer || towers))
                && s.composite >= 0.61;
        boolean absent = s == null || s.composite < 0.26 || s.handScore < 0.20;
        if (positive) lastPositive = nowMs;
        confidence = confidence * 0.72 + (s == null ? 0.0 : s.composite) * 0.28;
        boolean entered = false, exited = false;

        switch (state) {
            case OUTSIDE_BATTLE:
                if (entryPositive) transition(State.BATTLE_CANDIDATE, nowMs);
                break;
            case BATTLE_CANDIDATE:
                if (positive && nowMs - stateSince >= 420) transition(State.VERIFYING, nowMs);
                else if (nowMs - lastPositive > 900) transition(State.OUTSIDE_BATTLE, nowMs);
                break;
            case VERIFYING:
                if ((strong && nowMs - stateSince >= 620)
                        || (positive && nowMs - stateSince >= 1300)) {
                    transition(State.IN_BATTLE, nowMs);
                    entered = true;
                    lowSince = 0L;
                } else if (nowMs - lastPositive > 1050) transition(State.OUTSIDE_BATTLE, nowMs);
                break;
            case IN_BATTLE:
                if (absent) {
                    if (lowSince == 0L) lowSince = nowMs;
                    if (nowMs - lowSince >= 1900) transition(State.END_CANDIDATE, nowMs);
                } else lowSince = 0L;
                break;
            case END_CANDIDATE:
                if (positive) {
                    transition(State.IN_BATTLE, nowMs);
                    lowSince = 0L;
                } else if (nowMs - stateSince >= 3600) {
                    transition(State.OUTSIDE_BATTLE, nowMs);
                    exited = true;
                    confidence = 0.0;
                    lowSince = 0L;
                }
                break;
        }
        return new Update(state, entered, exited, ColorMath.clamp(confidence, 0.0, 1.0));
    }

    public State state() { return state; }
    public boolean inBattle() { return state == State.IN_BATTLE || state == State.END_CANDIDATE; }

    public void reset() {
        state = State.OUTSIDE_BATTLE;
        stateSince = lastPositive = lowSince = 0L;
        confidence = 0.0;
    }

    private void transition(State next, long nowMs) {
        if (state == next) return;
        state = next;
        stateSince = nowMs;
    }
}
