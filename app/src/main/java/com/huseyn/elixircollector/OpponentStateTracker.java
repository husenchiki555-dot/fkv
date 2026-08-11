package com.huseyn.elixircollector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical hidden opponent state with explicit Elixir uncertainty and cycle constraints. */
public final class OpponentStateTracker {
    public static final class Estimate {
        public final double best;
        public final double min;
        public final double max;
        public final double confidence;
        public final boolean anchored;

        Estimate(double best, double min, double max, double confidence, boolean anchored) {
            this.best = best;
            this.min = min;
            this.max = max;
            this.confidence = confidence;
            this.anchored = anchored;
        }
    }

    public static final class DeckSlot {
        public final String cardId;
        public final String displayName;
        public final int cardsUntilReturn;
        public final double identityConfidence;

        DeckSlot(String cardId, String name, int until, double confidence) {
            this.cardId = cardId;
            displayName = name;
            cardsUntilReturn = until;
            identityConfidence = confidence;
        }

        public boolean known() { return cardId != null; }
        public boolean available() { return known() && cardsUntilReturn == 0; }
    }

    public static final class Event {
        public final long timeMs;
        public final String kind;
        public final String cardId;
        public final int costMin;
        public final int costMax;
        public final double confidence;
        public final int cycleIndex;

        Event(long timeMs, String kind, String cardId, int costMin, int costMax,
              double confidence, int cycleIndex) {
            this.timeMs = timeMs;
            this.kind = kind;
            this.cardId = cardId;
            this.costMin = costMin;
            this.costMax = costMax;
            this.confidence = confidence;
            this.cycleIndex = cycleIndex;
        }
    }

    private static final class KnownCard {
        final String id;
        String name;
        int lastCycleIndex;
        double confidence;
        KnownCard(String id, String name, int index, double confidence) {
            this.id = id; this.name = name; lastCycleIndex = index; this.confidence = confidence;
        }
    }

    private boolean active;
    private long startMs;
    private long lastUpdateMs;
    private double minElixir;
    private double maxElixir;
    private double bestElixir = Double.NaN;
    private double anchorQuality;
    private RegenRateEstimator.Estimate regen = new RegenRateEstimator.Estimate(0.50, 0.24, 1.25, 0.0);
    private final ArrayList<Event> events = new ArrayList<>();
    private final LinkedHashMap<String, KnownCard> known = new LinkedHashMap<>();
    private int cycleCount;
    private int uncertainEvents;

    public void start(long nowMs) {
        active = true;
        startMs = Math.max(1L, nowMs);
        lastUpdateMs = startMs;
        minElixir = 0.0;
        maxElixir = 10.0;
        bestElixir = Double.NaN;
        anchorQuality = 0.0;
        events.clear();
        known.clear();
        cycleCount = 0;
        uncertainEvents = 0;
    }

    public void reset() {
        active = false;
        startMs = lastUpdateMs = 0L;
        minElixir = maxElixir = 0.0;
        bestElixir = Double.NaN;
        anchorQuality = 0.0;
        events.clear();
        known.clear();
        cycleCount = uncertainEvents = 0;
    }

    public boolean anchorFromLocal(long nowMs, double visibleLocal, double confidence, boolean cleanStart) {
        if (!active || !cleanStart || Double.isNaN(visibleLocal) || confidence < 0.52
                || !events.isEmpty() || nowMs - startMs > 8500) return false;
        advance(nowMs);
        double radius = 0.18 + (1.0 - confidence) * 0.72;
        minElixir = ColorMath.clamp(visibleLocal - radius, 0.0, 10.0);
        maxElixir = ColorMath.clamp(visibleLocal + radius, 0.0, 10.0);
        bestElixir = ColorMath.clamp(visibleLocal, 0.0, 10.0);
        anchorQuality = ColorMath.clamp(confidence, 0.0, 1.0);
        return true;
    }

    public void setRegenRate(RegenRateEstimator.Estimate estimate) {
        if (estimate != null) regen = estimate;
    }

    public Event onOpponentCard(long timeMs, String proposedCardId, int cost,
                                double identityConfidence, double eventConfidence) {
        if (!active) return null;
        advance(timeMs);
        String cardId = canonical(proposedCardId);
        if (cardId != null && !canPlay(cardId)) {
            cardId = null;
            identityConfidence = 0.0;
        }
        CardCatalog.Card card = CardCatalog.find(proposedCardId);
        if (cost <= 0 && card != null && !card.mirror) cost = card.cost;
        int minCost = cost >= 1 ? cost : 0;
        int maxCost = cost >= 1 ? cost : 10;
        applySpend(minCost, maxCost);

        int index = cycleCount++;
        if (cardId != null) {
            KnownCard entry = known.get(cardId);
            String display = card == null ? cardId.replace('_', ' ') : card.displayName;
            if (entry == null && known.size() < 8) {
                entry = new KnownCard(cardId, display, index, identityConfidence);
                known.put(cardId, entry);
            } else if (entry != null) {
                entry.lastCycleIndex = index;
                entry.confidence = Math.max(entry.confidence, identityConfidence);
                entry.name = display;
            }
            if (entry == null) cardId = null;
        }
        if (cardId == null || cost <= 0) uncertainEvents++;
        Event event = new Event(timeMs, "CARD", cardId, minCost, maxCost,
                ColorMath.clamp(eventConfidence, 0.0, 1.0), index);
        events.add(event);
        return event;
    }

    public Event onOpponentAbility(long timeMs, int cost, double confidence) {
        if (!active || cost <= 0) return null;
        advance(timeMs);
        applySpend(cost, cost);
        Event event = new Event(timeMs, "ABILITY", null, cost, cost,
                ColorMath.clamp(confidence, 0.0, 1.0), -1);
        events.add(event);
        uncertainEvents++;
        return event;
    }

    public void advance(long nowMs) {
        if (!active) return;
        nowMs = Math.max(lastUpdateMs, nowMs);
        double dt = (nowMs - lastUpdateMs) / 1000.0;
        if (dt <= 0.0) return;
        minElixir = Math.min(10.0, minElixir + dt * regen.minPerSecond);
        maxElixir = Math.min(10.0, maxElixir + dt * regen.maxPerSecond);
        if (!Double.isNaN(bestElixir)) bestElixir = Math.min(10.0, bestElixir + dt * regen.bestPerSecond);
        lastUpdateMs = nowMs;
    }

    private void applySpend(int costMin, int costMax) {
        if (costMin <= 0 && costMax >= 10) {
            minElixir = 0.0;
            maxElixir = 10.0;
            bestElixir = Double.NaN;
            anchorQuality *= 0.35;
            return;
        }
        double constrainedMin = Math.max(minElixir, costMin);
        double constrainedMax = Math.max(maxElixir, costMin);
        if (maxElixir + 0.25 < costMin) {
            constrainedMin = constrainedMax = costMin;
            anchorQuality *= 0.55;
        }
        minElixir = ColorMath.clamp(constrainedMin - costMax, 0.0, 10.0);
        maxElixir = ColorMath.clamp(constrainedMax - costMin, minElixir, 10.0);
        if (!Double.isNaN(bestElixir)) {
            bestElixir = Math.max(bestElixir, costMin) - (costMin == costMax ? costMin : (costMin + costMax) * 0.5);
            bestElixir = ColorMath.clamp(bestElixir, minElixir, maxElixir);
        }
        if (costMin != costMax) anchorQuality *= 0.68;
    }

    public Estimate estimate(long nowMs) {
        advance(nowMs);
        double width = Math.max(0.0, maxElixir - minElixir);
        double intervalQuality = 1.0 - ColorMath.clamp(width / 10.0, 0.0, 1.0);
        double eventDecay = Math.exp(-uncertainEvents * 0.11);
        double confidence = Double.isNaN(bestElixir) ? 0.0
                : ColorMath.clamp(anchorQuality * intervalQuality * eventDecay, 0.0, 1.0);
        return new Estimate(bestElixir, minElixir, maxElixir, confidence, !Double.isNaN(bestElixir));
    }

    public List<DeckSlot> deckSlots() {
        ArrayList<DeckSlot> out = new ArrayList<>();
        for (Map.Entry<String, KnownCard> item : known.entrySet()) {
            KnownCard c = item.getValue();
            int since = cycleCount - 1 - c.lastCycleIndex;
            int until = Math.max(0, 4 - Math.max(0, since));
            out.add(new DeckSlot(c.id, c.name, until, c.confidence));
        }
        while (out.size() < 8) out.add(new DeckSlot(null, "Unknown", -1, 0.0));
        if (out.size() > 8) return new ArrayList<>(out.subList(0, 8));
        return out;
    }

    public boolean canPlay(String proposedCardId) {
        String id = canonical(proposedCardId);
        if (id == null) return true;
        KnownCard card = known.get(id);
        if (card == null) return known.size() < 8;
        int since = cycleCount - 1 - card.lastCycleIndex;
        return since >= 4;
    }

    public boolean isActive() { return active; }
    public int cycleCount() { return cycleCount; }
    public int knownCount() { return known.size(); }
    public long startMs() { return startMs; }
    public List<Event> events() { return new ArrayList<>(events); }

    public String lastLabel() {
        if (events.isEmpty()) return "LAST: —";
        Event e = events.get(events.size() - 1);
        if (e.cardId != null) {
            CardCatalog.Card card = CardCatalog.find(e.cardId);
            return "LAST: " + (card == null ? e.cardId : card.displayName);
        }
        if (e.kind.equals("ABILITY")) return "LAST: ability −" + e.costMin;
        return e.costMin == e.costMax && e.costMin > 0 ? "LAST: ? (" + e.costMin + ")" : "LAST: ?";
    }

    private static String canonical(String id) {
        if (id == null || id.isEmpty()) return null;
        CardCatalog.Card card = CardCatalog.find(id);
        return card == null ? id : card.deckId;
    }
}
