package com.huseyn.elixircollector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic opponent state for the AI-less automatic-CV build. */
public final class AutoState {
    public static final double MAX_ELIXIR = 10.0;
    public static final double BASE_SECONDS_PER_ELIXIR = 2.8;

    public static final class Event {
        public final long timeMs;
        public final String kind;
        public final String deckId;
        public final String name;
        public final double delta;
        public final boolean cycleAdvance;

        Event(long timeMs, String kind, String deckId, String name, double delta,
              boolean cycleAdvance) {
            this.timeMs = timeMs;
            this.kind = kind;
            this.deckId = deckId;
            this.name = name;
            this.delta = delta;
            this.cycleAdvance = cycleAdvance;
        }
    }

    public static final class DeckStatus {
        public final String deckId;
        public final String name;
        public final int cardsUntilReturn;
        public final double lastCost;

        DeckStatus(String deckId, String name, int cardsUntilReturn, double lastCost) {
            this.deckId = deckId;
            this.name = name;
            this.cardsUntilReturn = cardsUntilReturn;
            this.lastCost = lastCost;
        }
    }

    private final ArrayList<Event> events = new ArrayList<>();
    private long matchStartMs;
    private double initialOpponentElixir = 0.0;
    private double observedLocalAtAnchor = Double.NaN;

    public synchronized void start(long nowMs, double observedLocalElixir) {
        matchStartMs = nowMs;
        initialOpponentElixir = clamp(observedLocalElixir, 0.0, 10.0);
        observedLocalAtAnchor = observedLocalElixir;
        events.clear();
    }

    public synchronized void reset() {
        matchStartMs = 0L;
        initialOpponentElixir = 0.0;
        observedLocalAtAnchor = Double.NaN;
        events.clear();
    }

    public synchronized boolean isStarted() { return matchStartMs > 0L; }
    public synchronized long getMatchStartMs() { return matchStartMs; }
    public synchronized double getInitialOpponentElixir() { return initialOpponentElixir; }
    public synchronized double getObservedLocalAtAnchor() { return observedLocalAtAnchor; }

    public synchronized Event addCard(CardCatalog.Card card, long nowMs) {
        if (!isStarted()) return null;
        double cost = card.cost;
        if (card.mirror) {
            Event previous = lastCycleEvent();
            if (previous == null) return null;
            cost = Math.min(10.0, Math.max(0.0, -previous.delta + 1.0));
        }
        Event e = new Event(nowMs, "CARD_COMMIT", card.deckId, card.displayName,
                -cost, true);
        events.add(e);
        return e;
    }

    public synchronized Event addSpend(double amount, long nowMs) {
        if (!isStarted()) return null;
        Event e = new Event(nowMs, "RESOURCE_SPEND", null,
                "Ability −" + fmt(amount), -Math.abs(amount), false);
        events.add(e);
        return e;
    }

    public synchronized Event addGain(double amount, long nowMs) {
        if (!isStarted()) return null;
        Event e = new Event(nowMs, "RESOURCE_GAIN", null,
                "Gain +" + fmt(amount), Math.abs(amount), false);
        events.add(e);
        return e;
    }

    public synchronized Event undo() {
        if (events.isEmpty()) return null;
        return events.remove(events.size() - 1);
    }

    public synchronized List<Event> getEvents() { return new ArrayList<>(events); }

    public synchronized double getOpponentElixir(long nowMs) {
        if (!isStarted()) return Double.NaN;
        double value = initialOpponentElixir;
        long cursor = matchStartMs;
        for (Event e : events) {
            long t = Math.max(cursor, e.timeMs);
            value = Math.min(MAX_ELIXIR, value + regen(cursor, t));
            value = clamp(value + e.delta, 0.0, MAX_ELIXIR);
            cursor = t;
        }
        return Math.min(MAX_ELIXIR, value + regen(cursor, Math.max(cursor, nowMs)));
    }

    private double regen(long fromMs, long toMs) {
        if (!isStarted() || toMs <= fromMs) return 0.0;
        double from = Math.max(0.0, (fromMs - matchStartMs) / 1000.0);
        double to = Math.max(from, (toMs - matchStartMs) / 1000.0);
        double total = 0.0;
        total += segment(from, to, 0.0, 120.0, 1.0);
        total += segment(from, to, 120.0, 240.0, 2.0);
        total += segment(from, to, 240.0, 300.0, 3.0);
        return total;
    }

    private double segment(double from, double to, double a, double b, double mult) {
        double x = Math.max(from, a);
        double y = Math.min(to, b);
        if (y <= x) return 0.0;
        return (y - x) * mult / BASE_SECONDS_PER_ELIXIR;
    }

    public synchronized double getMultiplier(long nowMs) {
        if (!isStarted()) return 1.0;
        double e = Math.max(0.0, (nowMs - matchStartMs) / 1000.0);
        if (e < 120.0) return 1.0;
        if (e < 240.0) return 2.0;
        return 3.0;
    }

    public synchronized String getClock(long nowMs) {
        if (!isStarted()) return "SEARCHING";
        int elapsed = (int)Math.floor(Math.max(0.0, (nowMs - matchStartMs) / 1000.0));
        if (elapsed < 180) {
            int rem = 180 - elapsed;
            return String.format(Locale.US, "%d:%02d", rem / 60, rem % 60);
        }
        int ot = elapsed - 180;
        if (ot <= 120) {
            int rem = Math.max(0, 120 - ot);
            return String.format(Locale.US, "OT %d:%02d", rem / 60, rem % 60);
        }
        return "ENDED?";
    }

    private Event lastCycleEvent() {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).cycleAdvance) return events.get(i);
        }
        return null;
    }

    public synchronized int getCycleCount() {
        int n = 0;
        for (Event e : events) if (e.cycleAdvance) n++;
        return n;
    }

    public synchronized List<DeckStatus> getDeck() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> lastIndex = new LinkedHashMap<>();
        LinkedHashMap<String, Double> costs = new LinkedHashMap<>();
        int index = 0;
        for (Event e : events) {
            if (!e.cycleAdvance || e.deckId == null) continue;
            if (!names.containsKey(e.deckId)) names.put(e.deckId, e.name);
            lastIndex.put(e.deckId, index);
            costs.put(e.deckId, -e.delta);
            index++;
        }
        ArrayList<DeckStatus> out = new ArrayList<>();
        for (Map.Entry<String, String> x : names.entrySet()) {
            int last = lastIndex.get(x.getKey());
            int since = index - 1 - last;
            int until = Math.max(0, 4 - since);
            out.add(new DeckStatus(x.getKey(), x.getValue(), until, costs.get(x.getKey())));
        }
        return out;
    }

    public synchronized String getLast() {
        if (events.isEmpty()) return "LAST: —";
        Event e = events.get(events.size() - 1);
        return "LAST: " + e.name;
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.valueOf((int)Math.rint(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
