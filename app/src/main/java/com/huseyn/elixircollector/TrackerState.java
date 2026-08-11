package com.huseyn.elixircollector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TrackerState {
    public static final double BASE_SECONDS_PER_ELIXIR = 2.8;
    public static final double STARTING_ELIXIR = 5.0;
    public static final double MAX_ELIXIR = 10.0;

    public enum Mode {
        STANDARD_AUTO("AUTO"),
        FIXED_1X("1×"),
        FIXED_2X("2×"),
        FIXED_3X("3×");

        public final String label;
        Mode(String label) { this.label = label; }
    }

    public static final class Event {
        public final long wallMs;
        public final String kind;
        public final String deckId;
        public final String displayName;
        public final double deltaElixir;
        public final boolean cycleAdvance;

        public Event(long wallMs, String kind, String deckId, String displayName,
                     double deltaElixir, boolean cycleAdvance) {
            this.wallMs = wallMs;
            this.kind = kind;
            this.deckId = deckId;
            this.displayName = displayName;
            this.deltaElixir = deltaElixir;
            this.cycleAdvance = cycleAdvance;
        }
    }

    public static final class DeckCardStatus {
        public final String deckId;
        public final String name;
        public final double lastCost;
        public final int cardsUntilReturn;
        public final int lastCycleIndex;

        DeckCardStatus(String deckId, String name, double lastCost,
                       int cardsUntilReturn, int lastCycleIndex) {
            this.deckId = deckId;
            this.name = name;
            this.lastCost = lastCost;
            this.cardsUntilReturn = cardsUntilReturn;
            this.lastCycleIndex = lastCycleIndex;
        }
    }

    private final ArrayList<Event> events = new ArrayList<>();
    private long matchStartWallMs;
    private Mode mode = Mode.STANDARD_AUTO;

    public synchronized void startMatch(long nowMs) {
        matchStartWallMs = nowMs;
        events.clear();
    }

    public synchronized void resetToReady() {
        matchStartWallMs = 0L;
        events.clear();
        mode = Mode.STANDARD_AUTO;
    }

    public synchronized boolean isStarted() {
        return matchStartWallMs > 0L;
    }

    public synchronized long getMatchStartWallMs() {
        return matchStartWallMs;
    }

    public synchronized void setMode(Mode mode) {
        this.mode = mode == null ? Mode.STANDARD_AUTO : mode;
    }

    public synchronized Mode getMode() {
        return mode;
    }

    public synchronized Mode cycleMode() {
        switch (mode) {
            case STANDARD_AUTO: mode = Mode.FIXED_1X; break;
            case FIXED_1X: mode = Mode.FIXED_2X; break;
            case FIXED_2X: mode = Mode.FIXED_3X; break;
            default: mode = Mode.STANDARD_AUTO; break;
        }
        return mode;
    }

    public synchronized void nudgeElapsedSeconds(int seconds) {
        if (!isStarted()) return;
        matchStartWallMs -= seconds * 1000L;
    }

    private void ensureStarted(long nowMs) {
        if (!isStarted()) startMatch(nowMs);
    }

    public synchronized Event addCard(CardCatalog.Card card, long nowMs) {
        ensureStarted(nowMs);
        double cost = card.cost;
        if (card.mirror) {
            Event previous = lastCardEvent();
            cost = previous == null ? 0.0 : Math.min(10.0, Math.max(0.0, -previous.deltaElixir + 1.0));
        }
        Event event = new Event(nowMs, "CARD", card.deckId, card.displayName,
                -cost, true);
        events.add(event);
        return event;
    }

    public synchronized Event addSpend(double amount, long nowMs) {
        ensureStarted(nowMs);
        Event event = new Event(nowMs, "ABILITY_SPEND", null,
                "Spend " + formatAmount(amount), -Math.abs(amount), false);
        events.add(event);
        return event;
    }

    public synchronized Event addGain(double amount, long nowMs) {
        ensureStarted(nowMs);
        Event event = new Event(nowMs, "RESOURCE_GAIN", null,
                "Gain " + formatAmount(amount), Math.abs(amount), false);
        events.add(event);
        return event;
    }

    public synchronized Event undoLast() {
        if (events.isEmpty()) return null;
        return events.remove(events.size() - 1);
    }

    public synchronized void clearEventsKeepClock() {
        events.clear();
    }

    public synchronized List<Event> getEvents() {
        return new ArrayList<>(events);
    }

    public synchronized void restore(long startWallMs, Mode restoredMode, List<Event> restoredEvents) {
        matchStartWallMs = startWallMs;
        mode = restoredMode == null ? Mode.STANDARD_AUTO : restoredMode;
        events.clear();
        if (restoredEvents != null) {
            events.addAll(restoredEvents);
            Collections.sort(events, Comparator.comparingLong(e -> e.wallMs));
        }
    }

    private Event lastCardEvent() {
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (e.cycleAdvance) return e;
        }
        return null;
    }

    public synchronized double getElixir(long nowMs) {
        if (!isStarted()) return STARTING_ELIXIR;
        double value = STARTING_ELIXIR;
        long cursor = matchStartWallMs;
        for (Event event : events) {
            long t = Math.max(cursor, event.wallMs);
            value = Math.min(MAX_ELIXIR, value + regen(cursor, t));
            value = clamp(value + event.deltaElixir, 0.0, MAX_ELIXIR);
            cursor = t;
        }
        long end = Math.max(cursor, nowMs);
        return Math.min(MAX_ELIXIR, value + regen(cursor, end));
    }

    private double regen(long fromMs, long toMs) {
        if (!isStarted() || toMs <= fromMs) return 0.0;
        if (mode != Mode.STANDARD_AUTO) {
            double multiplier = mode == Mode.FIXED_1X ? 1.0 : mode == Mode.FIXED_2X ? 2.0 : 3.0;
            return ((toMs - fromMs) / 1000.0) * multiplier / BASE_SECONDS_PER_ELIXIR;
        }

        double from = Math.max(0.0, (fromMs - matchStartWallMs) / 1000.0);
        double to = Math.max(from, (toMs - matchStartWallMs) / 1000.0);
        double total = 0.0;
        total += segment(from, to, 0.0, 120.0, 1.0);
        total += segment(from, to, 120.0, 240.0, 2.0);
        total += segment(from, to, 240.0, 300.0, 3.0);
        return total;
    }

    private double segment(double from, double to, double start, double end, double multiplier) {
        double a = Math.max(from, start);
        double b = Math.min(to, end);
        if (b <= a) return 0.0;
        return (b - a) * multiplier / BASE_SECONDS_PER_ELIXIR;
    }

    public synchronized double getCurrentMultiplier(long nowMs) {
        if (mode == Mode.FIXED_1X) return 1.0;
        if (mode == Mode.FIXED_2X) return 2.0;
        if (mode == Mode.FIXED_3X) return 3.0;
        double elapsed = getElapsedSeconds(nowMs);
        if (elapsed < 120.0) return 1.0;
        if (elapsed < 240.0) return 2.0;
        return 3.0;
    }

    public synchronized double getElapsedSeconds(long nowMs) {
        if (!isStarted()) return 0.0;
        return Math.max(0.0, (nowMs - matchStartWallMs) / 1000.0);
    }

    public synchronized String getClockLabel(long nowMs) {
        if (!isStarted()) return "READY";
        int elapsed = (int)Math.floor(getElapsedSeconds(nowMs));
        if (elapsed < 180) {
            int remaining = Math.max(0, 180 - elapsed);
            return String.format(Locale.US, "%d:%02d", remaining / 60, remaining % 60);
        }
        int overtime = elapsed - 180;
        int remainingOt = Math.max(0, 120 - overtime);
        if (overtime <= 120) {
            return String.format(Locale.US, "OT %d:%02d", remainingOt / 60, remainingOt % 60);
        }
        return "ENDED?";
    }

    public synchronized int getCycleCommitCount() {
        int count = 0;
        for (Event e : events) if (e.cycleAdvance) count++;
        return count;
    }

    public synchronized List<DeckCardStatus> getDeckStatuses() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        LinkedHashMap<String, Double> costs = new LinkedHashMap<>();
        Map<String, Integer> lastIndex = new LinkedHashMap<>();
        int cycleIndex = 0;
        for (Event e : events) {
            if (!e.cycleAdvance || e.deckId == null) continue;
            if (!names.containsKey(e.deckId)) names.put(e.deckId, e.displayName);
            costs.put(e.deckId, -e.deltaElixir);
            lastIndex.put(e.deckId, cycleIndex);
            cycleIndex++;
        }
        ArrayList<DeckCardStatus> result = new ArrayList<>();
        int total = cycleIndex;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            int idx = lastIndex.get(entry.getKey());
            int since = total - 1 - idx;
            int until = Math.max(0, 4 - since);
            result.add(new DeckCardStatus(entry.getKey(), entry.getValue(),
                    costs.get(entry.getKey()), until, idx));
        }
        return result;
    }

    public synchronized boolean hasDeckConflict() {
        return getDeckStatuses().size() > 8;
    }

    public synchronized List<String> getRecentCardNames(int max) {
        ArrayList<String> result = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && result.size() < max; i--) {
            Event e = events.get(i);
            if (e.cycleAdvance) result.add(e.displayName);
        }
        return result;
    }

    public synchronized String getNextReturnSummary() {
        List<DeckCardStatus> deck = getDeckStatuses();
        if (deck.size() < 2) return "NEXT: —";
        ArrayList<DeckCardStatus> candidates = new ArrayList<>();
        for (DeckCardStatus s : deck) if (s.cardsUntilReturn > 0) candidates.add(s);
        candidates.sort((a, b) -> {
            int c = Integer.compare(a.cardsUntilReturn, b.cardsUntilReturn);
            if (c != 0) return c;
            return Integer.compare(a.lastCycleIndex, b.lastCycleIndex);
        });
        if (candidates.isEmpty()) return "NEXT: all seen cards available";
        StringBuilder sb = new StringBuilder("NEXT: ");
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            if (i > 0) sb.append("  •  ");
            DeckCardStatus s = candidates.get(i);
            sb.append(CardCatalog.shortName(s.name)).append(" ").append(s.cardsUntilReturn);
        }
        return sb.toString();
    }

    public synchronized String getLastEventSummary() {
        if (events.isEmpty()) return "LAST: —";
        Event e = events.get(events.size() - 1);
        if (e.cycleAdvance) {
            return "LAST: " + e.displayName + " (" + formatAmount(-e.deltaElixir) + ")";
        }
        return "LAST: " + e.displayName;
    }

    private static String formatAmount(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.valueOf((int)Math.rint(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
