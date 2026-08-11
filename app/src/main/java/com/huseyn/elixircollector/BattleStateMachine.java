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
        boolean hand = s != null && s.handScore >= 0.43;
        boolean positive = s != null && hand && s.corroboratingSensors >= 2 && s.composite >= 0.52;
        boolean strong = s != null && hand && s.corroboratingSensors >= 3 && s.composite >= 0.62;
        boolean absent = s == null || s.composite < 0.26 || s.handScore < 0.20;
        if (positive) lastPositive = nowMs;
        confidence = confidence * 0.72 + (s == null ? 0.0 : s.composite) * 0.28;
        boolean entered = false, exited = false;

        switch (state) {
            case OUTSIDE_BATTLE:
                if (positive) transition(State.BATTLE_CANDIDATE, nowMs);
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
