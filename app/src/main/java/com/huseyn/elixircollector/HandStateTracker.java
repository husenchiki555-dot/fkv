package com.huseyn.elixircollector;

import java.util.HashSet;
import java.util.Set;

/** Debounces classified hands and emits a transition only after a stable before/after pair. */
public final class HandStateTracker {
    public static final class Observation {
        public final boolean stable;
        public final boolean changed;
        public final int changedSlots;
        public final String playedCardId;
        public final double confidence;
        public final long timeMs;

        Observation(boolean stable, boolean changed, int changedSlots,
                    String playedCardId, double confidence, long timeMs) {
            this.stable = stable;
            this.changed = changed;
            this.changedSlots = changedSlots;
            this.playedCardId = playedCardId;
            this.confidence = confidence;
            this.timeMs = timeMs;
        }
    }

    private HandCardRecognizer.Result stable;
    private HandCardRecognizer.Result pending;
    private String pendingKey;
    private int pendingHits;
    private long lastTransitionMs;

    public Observation update(HandCardRecognizer.Result current, long nowMs) {
        if (current == null) return none(nowMs);
        String key = key(current.slotIds);
        boolean classifiable = current.strongSlots >= 3;
        if (classifiable) {
            if (key.equals(pendingKey)) {
                pendingHits++;
            } else {
                pending = copy(current);
                pendingKey = key;
                pendingHits = 1;
            }
            if (pendingHits >= 2) {
                HandCardRecognizer.Result promoted = pending;
                pending = null;
                pendingKey = null;
                pendingHits = 0;
                if (stable == null) {
                    stable = copy(promoted);
                    return new Observation(true, false, 0, null, averageConfidence(stable), nowMs);
                }
                if (!sameIds(stable.slotIds, promoted.slotIds)) {
                    Transition t = transition(stable, promoted);
                    stable = copy(promoted);
                    if (t.changed && nowMs - lastTransitionMs >= 360) {
                        lastTransitionMs = nowMs;
                        return new Observation(true, true, t.changedSlots, t.playedCard,
                                t.confidence, nowMs);
                    }
                } else {
                    stable = copy(promoted);
                }
            }
        } else if (pendingHits > 0) {
            pendingHits = Math.max(0, pendingHits - 1);
            if (pendingHits == 0) { pending = null; pendingKey = null; }
        }

        int visualChanges = visualChanges(stable, current, 0.31);
        return new Observation(stable != null, false, visualChanges, null,
                stable == null ? 0.0 : averageConfidence(stable), nowMs);
    }

    public void reset() {
        stable = pending = null;
        pendingKey = null;
        pendingHits = 0;
        lastTransitionMs = 0L;
    }

    private static Transition transition(HandCardRecognizer.Result before,
                                         HandCardRecognizer.Result after) {
        Set<String> oldSet = ids(before.slotIds);
        Set<String> newSet = ids(after.slotIds);
        String disappeared = null;
        int disappearedCount = 0;
        for (String id : oldSet) if (!newSet.contains(id)) {
            disappeared = id;
            disappearedCount++;
        }
        int changed = visualChanges(before, after, 0.24);
        boolean valid = disappearedCount == 1 || changed == 1;
        String played = disappearedCount == 1 ? disappeared : null;
        double confidence = Math.min(averageConfidence(before), averageConfidence(after));
        if (played != null) {
            for (int i = 0; i < before.slotIds.length; i++) if (played.equals(before.slotIds[i])) {
                confidence = Math.min(confidence, before.confidence[i]);
            }
        }
        return new Transition(valid, Math.max(changed, disappearedCount), played, confidence);
    }

    private static int visualChanges(HandCardRecognizer.Result a,
                                     HandCardRecognizer.Result b, double threshold) {
        if (a == null || b == null || a.appearance == null || b.appearance == null) return 0;
        int n = Math.min(a.appearance.length, b.appearance.length), changed = 0;
        for (int i = 0; i < n; i++) {
            if (a.appearance[i] != null && b.appearance[i] != null
                    && CardVisualFeatures.visualDelta(a.appearance[i], b.appearance[i]) >= threshold) changed++;
        }
        return changed;
    }

    private static Set<String> ids(String[] values) {
        HashSet<String> out = new HashSet<>();
        if (values != null) for (String v : values) if (v != null) out.add(v);
        return out;
    }

    private static String key(String[] ids) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) b.append('|');
            b.append(ids != null && i < ids.length && ids[i] != null ? ids[i] : "?");
        }
        return b.toString();
    }

    private static boolean sameIds(String[] a, String[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null ? b[i] != null : !a[i].equals(b[i])) return false;
        }
        return true;
    }

    private static double averageConfidence(HandCardRecognizer.Result r) {
        if (r == null) return 0.0;
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < r.confidence.length; i++) if (r.slotIds[i] != null) {
            sum += r.confidence[i];
            n++;
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static HandCardRecognizer.Result copy(HandCardRecognizer.Result r) {
        return new HandCardRecognizer.Result(r.slotIds.clone(), r.confidence.clone(),
                r.appearance == null ? null : r.appearance.clone(), r.strongSlots);
    }

    private Observation none(long nowMs) {
        return new Observation(stable != null, false, 0, null, 0.0, nowMs);
    }

    private static final class Transition {
        final boolean changed;
        final int changedSlots;
        final String playedCard;
        final double confidence;
        Transition(boolean changed, int slots, String playedCard, double confidence) {
            this.changed = changed;
            changedSlots = slots;
            this.playedCard = playedCard;
            this.confidence = confidence;
        }
    }
}
