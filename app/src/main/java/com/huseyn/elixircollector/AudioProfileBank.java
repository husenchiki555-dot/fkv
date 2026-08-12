package com.huseyn.elixircollector;

import java.util.HashMap;
import java.util.Map;

/** Session-local prototypes learned only from confidently identified local plays. */
public final class AudioProfileBank {
    public static final class Match {
        public final String cardId;
        public final double confidence;
        Match(String cardId, double confidence) { this.cardId = cardId; this.confidence = confidence; }
    }

    private static final class Profile {
        double strength, zcr, brightness;
        double[] bands = new double[0];
        int n;
        void add(AudioEvidenceEngine.Fingerprint f) {
            double alpha = n <= 0 ? 1.0 : Math.min(0.40, 1.0 / (n + 1.0));
            if (n == 0) {
                strength = f.strength; zcr = f.zcr; brightness = f.brightness;
                bands = f.bands.clone();
            } else {
                strength = strength * (1 - alpha) + f.strength * alpha;
                zcr = zcr * (1 - alpha) + f.zcr * alpha;
                brightness = brightness * (1 - alpha) + f.brightness * alpha;
                if (bands.length == f.bands.length) for (int i = 0; i < bands.length; i++) {
                    bands[i] = bands[i] * (1 - alpha) + f.bands[i] * alpha;
                }
            }
            n++;
        }
        AudioEvidenceEngine.Fingerprint fingerprint() {
            return new AudioEvidenceEngine.Fingerprint(0L, strength, zcr, brightness, bands);
        }
    }

    private final Map<String, Profile> profiles = new HashMap<>();

    public void clear() { profiles.clear(); }

    public void learn(String cardId, AudioEvidenceEngine.Fingerprint fingerprint) {
        if (cardId == null || fingerprint == null) return;
        Profile profile = profiles.get(cardId);
        if (profile == null) { profile = new Profile(); profiles.put(cardId, profile); }
        profile.add(fingerprint);
    }

    public Match match(AudioEvidenceEngine.Fingerprint fingerprint, int cost) {
        if (fingerprint == null) return null;
        String bestId = null;
        double best = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            CardCatalog.Card card = CardCatalog.find(entry.getKey());
            if (card == null || (!card.mirror && cost > 0 && card.cost != cost)) continue;
            double distance = AudioEvidenceEngine.distance(fingerprint, entry.getValue().fingerprint());
            if (distance < best) { second = best; best = distance; bestId = entry.getKey(); }
            else if (distance < second) second = distance;
        }
        if (bestId == null) return null;
        double margin = Double.isInfinite(second) ? 0.55 : second - best;
        if (best > 1.02 || (!Double.isInfinite(second) && margin < 0.22)) return null;
        double confidence = ColorMath.clamp(1.0 - best / 1.18, 0.0, 1.0) * 0.72
                + ColorMath.clamp(margin / 0.62, 0.0, 1.0) * 0.28;
        return new Match(bestId, confidence);
    }
}
