package com.huseyn.elixircollector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Immutable process boundary snapshot consumed by the overlay. */
public final class SessionSnapshot {
    public final int sessionId;
    public final long timeMs;
    public final boolean captureActive;
    public final boolean audioAvailable;
    public final String matchState;
    public final double matchConfidence;
    public final String hudState;
    public final double handStructureScore;
    public final int recognizedHandSlots;
    public final String elixirState;
    public final double localElixir;
    public final double localElixirConfidence;
    public final OpponentStateTracker.Estimate opponent;
    public final List<OpponentStateTracker.DeckSlot> deck;
    public final String status;
    public final String lastEvent;
    public final double timerScore;
    public final double crownScore;
    public final double arenaScore;

    public SessionSnapshot(int sessionId, long timeMs, boolean captureActive,
                           boolean audioAvailable, String matchState, double matchConfidence,
                           String hudState, double handStructureScore, int recognizedHandSlots,
                           String elixirState, double localElixir, double localElixirConfidence,
                           OpponentStateTracker.Estimate opponent,
                           List<OpponentStateTracker.DeckSlot> deck, String status,
                           String lastEvent, double timerScore, double crownScore,
                           double arenaScore) {
        this.sessionId = sessionId;
        this.timeMs = timeMs;
        this.captureActive = captureActive;
        this.audioAvailable = audioAvailable;
        this.matchState = matchState;
        this.matchConfidence = matchConfidence;
        this.hudState = hudState;
        this.handStructureScore = handStructureScore;
        this.recognizedHandSlots = recognizedHandSlots;
        this.elixirState = elixirState;
        this.localElixir = localElixir;
        this.localElixirConfidence = localElixirConfidence;
        this.opponent = opponent;
        this.deck = deck == null ? new ArrayList<>() : new ArrayList<>(deck);
        this.status = status;
        this.lastEvent = lastEvent;
        this.timerScore = timerScore;
        this.crownScore = crownScore;
        this.arenaScore = arenaScore;
    }

    public boolean matchFound() {
        return "IN_BATTLE".equals(matchState) || "END_CANDIDATE".equals(matchState);
    }

    public String toJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("session", sessionId);
            j.put("time", timeMs);
            j.put("capture", captureActive);
            j.put("audio", audioAvailable);
            j.put("match", matchState);
            j.put("match_conf", matchConfidence);
            j.put("hud", hudState);
            j.put("hand_score", handStructureScore);
            j.put("hand_slots", recognizedHandSlots);
            j.put("elixir_state", elixirState);
            putNumber(j, "local", localElixir);
            j.put("local_conf", localElixirConfidence);
            if (opponent != null) {
                JSONObject e = new JSONObject();
                putNumber(e, "best", opponent.best);
                e.put("min", opponent.min);
                e.put("max", opponent.max);
                e.put("conf", opponent.confidence);
                e.put("anchored", opponent.anchored);
                j.put("opponent", e);
            }
            JSONArray cards = new JSONArray();
            for (OpponentStateTracker.DeckSlot slot : deck) {
                JSONObject c = new JSONObject();
                if (slot.cardId == null) c.put("id", JSONObject.NULL); else c.put("id", slot.cardId);
                c.put("name", slot.displayName);
                c.put("until", slot.cardsUntilReturn);
                c.put("conf", slot.identityConfidence);
                cards.put(c);
            }
            j.put("deck", cards);
            j.put("status", status == null ? "" : status);
            j.put("last", lastEvent == null ? "" : lastEvent);
            j.put("timer", timerScore);
            j.put("crown", crownScore);
            j.put("arena", arenaScore);
            return j.toString();
        } catch (JSONException error) {
            return "{}";
        }
    }

    public static SessionSnapshot fromJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject e = j.optJSONObject("opponent");
            OpponentStateTracker.Estimate estimate = e == null ? null : new OpponentStateTracker.Estimate(
                    numberOrNaN(e, "best"), e.optDouble("min", 0.0), e.optDouble("max", 10.0),
                    e.optDouble("conf", 0.0), e.optBoolean("anchored", false));
            ArrayList<OpponentStateTracker.DeckSlot> deck = new ArrayList<>();
            JSONArray cards = j.optJSONArray("deck");
            if (cards != null) for (int i = 0; i < cards.length(); i++) {
                JSONObject c = cards.optJSONObject(i);
                if (c == null) continue;
                String id = c.isNull("id") ? null : c.optString("id", null);
                deck.add(new OpponentStateTracker.DeckSlot(id, c.optString("name", "Unknown"),
                        c.optInt("until", -1), c.optDouble("conf", 0.0)));
            }
            return new SessionSnapshot(j.optInt("session", 0), j.optLong("time", 0L),
                    j.optBoolean("capture", false), j.optBoolean("audio", false),
                    j.optString("match", "OUTSIDE_BATTLE"), j.optDouble("match_conf", 0.0),
                    j.optString("hud", "SEARCHING"), j.optDouble("hand_score", 0.0),
                    j.optInt("hand_slots", 0), j.optString("elixir_state", "SEARCHING"),
                    numberOrNaN(j, "local"), j.optDouble("local_conf", 0.0), estimate, deck,
                    j.optString("status", ""), j.optString("last", ""),
                    j.optDouble("timer", 0.0), j.optDouble("crown", 0.0),
                    j.optDouble("arena", 0.0));
        } catch (JSONException error) {
            return null;
        }
    }

    private static void putNumber(JSONObject j, String key, double value) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value)) j.put(key, JSONObject.NULL);
        else j.put(key, value);
    }

    private static double numberOrNaN(JSONObject j, String key) {
        return j.isNull(key) ? Double.NaN : j.optDouble(key, Double.NaN);
    }
}
