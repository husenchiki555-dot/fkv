package com.huseyn.elixircollector;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups asynchronous observations into owned game events. Detectors never
 * mutate opponent state; only emitted OPPONENT_CARD events may do so.
 */
public final class EventFusionEngine {
    public enum Kind { LOCAL_CARD, LOCAL_ABILITY, OPPONENT_CARD }

    public static final class GameEvent {
        public final Kind kind;
        public final long timeMs;
        public final String localCardId;
        public final int cost;
        public final double confidence;
        public final FrameRect visualRegion;
        public final AudioEvidenceEngine.Fingerprint audio;

        GameEvent(Kind kind, long timeMs, String localCardId, int cost,
                  double confidence, FrameRect visualRegion,
                  AudioEvidenceEngine.Fingerprint audio) {
            this.kind = kind;
            this.timeMs = timeMs;
            this.localCardId = localCardId;
            this.cost = cost;
            this.confidence = confidence;
            this.visualRegion = visualRegion;
            this.audio = audio;
        }
    }

    private HandStateTracker.Observation lastHand;
    private ElixirBarTracker.Reading lastDrop;
    private CostBadgeDetector.Detection recentBadge;
    private PendingEnemy pendingEnemy;
    private long localGuardUntil;
    private long emittedHandMs;
    private long emittedDropMs;
    private long lastOpponentMs;

    public List<GameEvent> update(boolean inBattle,
                                  HandStateTracker.Observation hand,
                                  ElixirBarTracker.Reading elixir,
                                  ArenaMotionDetector.Observation arena,
                                  CostBadgeDetector.Detection badge,
                                  AudioEvidenceEngine.Fingerprint audio,
                                  long nowMs) {
        ArrayList<GameEvent> out = new ArrayList<>();
        if (!inBattle) {
            pendingEnemy = null;
            recentBadge = badge;
            return out;
        }

        if (badge != null && badge.confidence >= 0.55) recentBadge = badge;
        if (hand != null && hand.changed) {
            lastHand = hand;
            localGuardUntil = Math.max(localGuardUntil, hand.timeMs + 1350);
            if (pendingEnemy != null && Math.abs(pendingEnemy.startMs - hand.timeMs) <= 1150) pendingEnemy = null;
        }
        if (elixir != null && elixir.sharpDrop && elixir.dropAmount >= 0.42) {
            lastDrop = elixir;
            localGuardUntil = Math.max(localGuardUntil, elixir.timeMs + 1050);
        }

        if (lastHand != null && lastHand.timeMs > emittedHandMs) {
            boolean pairedDrop = lastDrop != null && lastDrop.timeMs > emittedDropMs
                    && Math.abs(lastDrop.timeMs - lastHand.timeMs) <= 1150;
            if (pairedDrop) {
                int cost = clampCost((int)Math.round(lastDrop.dropAmount));
                if (lastHand.playedCardId != null) {
                    CardCatalog.Card card = CardCatalog.find(lastHand.playedCardId);
                    if (card != null && !card.mirror) cost = card.cost;
                }
                double confidence = ColorMath.clamp(lastHand.confidence * 0.48
                        + lastDrop.confidence * 0.44 + (lastHand.playedCardId != null ? 0.08 : 0.0), 0.0, 1.0);
                out.add(new GameEvent(Kind.LOCAL_CARD, Math.max(lastHand.timeMs, lastDrop.timeMs),
                        lastHand.playedCardId, cost, confidence, null, nearAudio(audio,
                        Math.max(lastHand.timeMs, lastDrop.timeMs), 900)));
                emittedHandMs = lastHand.timeMs;
                emittedDropMs = lastDrop.timeMs;
                pendingEnemy = null;
            } else if (nowMs - lastHand.timeMs >= 430 && lastHand.playedCardId != null) {
                CardCatalog.Card card = CardCatalog.find(lastHand.playedCardId);
                int cost = card == null || card.mirror ? 0 : card.cost;
                out.add(new GameEvent(Kind.LOCAL_CARD, lastHand.timeMs, lastHand.playedCardId,
                        cost, lastHand.confidence * 0.82, null,
                        nearAudio(audio, lastHand.timeMs, 900)));
                emittedHandMs = lastHand.timeMs;
                pendingEnemy = null;
            }
        }

        if (lastDrop != null && lastDrop.timeMs > emittedDropMs
                && nowMs - lastDrop.timeMs >= 520
                && (lastHand == null || Math.abs(lastHand.timeMs - lastDrop.timeMs) > 1150)) {
            int cost = clampCost((int)Math.round(lastDrop.dropAmount));
            out.add(new GameEvent(Kind.LOCAL_ABILITY, lastDrop.timeMs, null, cost,
                    lastDrop.confidence * 0.76, null, nearAudio(audio, lastDrop.timeMs, 850)));
            emittedDropMs = lastDrop.timeMs;
        }

        if (arena != null && arena.deploymentLike && !arena.globalTransition
                && arena.timeMs > localGuardUntil && arena.timeMs - lastOpponentMs > 760) {
            if (pendingEnemy == null || arena.timeMs - pendingEnemy.lastMs > 700
                    || distance(arena.centerX, arena.centerY, pendingEnemy.centerX, pendingEnemy.centerY) > 0.18) {
                pendingEnemy = new PendingEnemy(arena);
            } else pendingEnemy.merge(arena);
        }

        if (pendingEnemy != null) {
            CostBadgeDetector.Detection usableBadge = recentBadge;
            if (usableBadge != null && Math.abs(usableBadge.timeMs - pendingEnemy.lastMs) <= 950) {
                double bx = usableBadge.normalizedX;
                double by = usableBadge.normalizedY;
                if (distance(bx, by, pendingEnemy.centerX, pendingEnemy.centerY) <= 0.34) {
                    pendingEnemy.badge = usableBadge;
                }
            }
            AudioEvidenceEngine.Fingerprint near = nearAudio(audio, pendingEnemy.lastMs, 850);
            double confidence = pendingEnemy.bestScore * 0.43
                    + (pendingEnemy.badge == null ? 0.0 : pendingEnemy.badge.confidence * 0.37)
                    + ColorMath.clamp((pendingEnemy.persistence - 1.0) / 2.0, 0.0, 1.0) * 0.12
                    + (near == null ? 0.0 : 0.08);
            if (pendingEnemy.badge != null && confidence >= 0.67 && nowMs > localGuardUntil) {
                FrameRect region = pendingEnemy.region != null ? pendingEnemy.region : pendingEnemy.badge.region;
                out.add(new GameEvent(Kind.OPPONENT_CARD, pendingEnemy.lastMs, null,
                        pendingEnemy.badge.cost, ColorMath.clamp(confidence, 0.0, 1.0), region, near));
                lastOpponentMs = pendingEnemy.lastMs;
                recentBadge = null;
                pendingEnemy = null;
            } else if (nowMs - pendingEnemy.lastMs > 1250) pendingEnemy = null;
        }
        return out;
    }

    public void reset() {
        lastHand = null;
        lastDrop = null;
        recentBadge = null;
        pendingEnemy = null;
        localGuardUntil = emittedHandMs = emittedDropMs = lastOpponentMs = 0L;
    }

    private static AudioEvidenceEngine.Fingerprint nearAudio(AudioEvidenceEngine.Fingerprint audio,
                                                              long timeMs, long windowMs) {
        return audio != null && Math.abs(audio.timeMs - timeMs) <= windowMs ? audio : null;
    }

    private static int clampCost(int cost) { return cost < 1 || cost > 10 ? 0 : cost; }

    private static double distance(double x1, double y1, double x2, double y2) {
        if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return 0.0;
        return Math.hypot(x1 - x2, y1 - y2);
    }

    private static final class PendingEnemy {
        final long startMs;
        long lastMs;
        double centerX, centerY, bestScore;
        int persistence;
        FrameRect region;
        CostBadgeDetector.Detection badge;

        PendingEnemy(ArenaMotionDetector.Observation arena) {
            startMs = lastMs = arena.timeMs;
            centerX = arena.centerX;
            centerY = arena.centerY;
            bestScore = arena.score;
            persistence = arena.persistenceFrames;
            region = arena.region;
        }

        void merge(ArenaMotionDetector.Observation arena) {
            lastMs = arena.timeMs;
            centerX = centerX * 0.55 + arena.centerX * 0.45;
            centerY = centerY * 0.55 + arena.centerY * 0.45;
            bestScore = Math.max(bestScore, arena.score);
            persistence = Math.max(persistence, arena.persistenceFrames);
            if (arena.region != null) region = arena.region;
        }
    }
}
