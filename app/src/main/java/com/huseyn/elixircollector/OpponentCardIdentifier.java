package com.huseyn.elixircollector;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Conservative identity fusion. It returns null unless evidence clears a high threshold. */
public final class OpponentCardIdentifier {
    public static final class Identification {
        public final String cardId;
        public final double confidence;
        public final String source;
        Identification(String id, double confidence, String source) {
            cardId = id; this.confidence = confidence; this.source = source;
        }
    }

    private final Context context;
    private final AudioProfileBank audioProfiles;
    private final Map<String, List<CardVisualFeatures.Feature>> art = new HashMap<>();

    public OpponentCardIdentifier(Context context, AudioProfileBank audioProfiles) {
        this.context = context.getApplicationContext();
        this.audioProfiles = audioProfiles;
    }

    public Identification identify(PixelFrame frame, FrameRect eventRegion, int cost,
                                   AudioEvidenceEngine.Fingerprint audio,
                                   OpponentStateTracker state) {
        AudioProfileBank.Match audioMatch = audioProfiles.match(audio, cost);
        if (audioMatch != null && !state.canPlay(audioMatch.cardId)) audioMatch = null;
        VisualMatch visual = visualMatch(frame, eventRegion, cost, state);

        if (audioMatch != null && visual != null && audioMatch.cardId.equals(visual.cardId)) {
            double confidence = ColorMath.clamp(0.48 + audioMatch.confidence * 0.26
                    + visual.confidence * 0.26, 0.0, 0.99);
            if (confidence >= 0.82) return new Identification(visual.cardId, confidence, "AUDIO+VISUAL");
        }
        if (visual != null && visual.confidence >= 0.94) {
            return new Identification(visual.cardId, visual.confidence, "VISUAL");
        }
        if (audioMatch != null && audioMatch.confidence >= 0.88) {
            return new Identification(audioMatch.cardId, audioMatch.confidence, "LEARNED_AUDIO");
        }
        return null;
    }

    public void clearVisualCache() { art.clear(); }

    private VisualMatch visualMatch(PixelFrame frame, FrameRect region, int cost,
                                    OpponentStateTracker state) {
        if (frame == null || region == null || cost <= 0) return null;
        double cx = region.centerX(), cy = region.centerY();
        double width = Math.max(0.095, Math.min(0.19, region.width() * 2.2));
        double height = Math.max(0.075, Math.min(0.18, region.height() * 2.0));
        FrameRect crop = new FrameRect(cx - width * 0.5, cy - height * 0.55,
                cx + width * 0.5, cy + height * 0.45);
        CardVisualFeatures.Feature live = CardVisualFeatures.extract(frame, crop);
        if (live == null) return null;
        String bestId = null;
        double best = 0.0, second = 0.0;
        for (CardCatalog.Card card : CardCatalog.choicesForCost(cost)) {
            if (!state.canPlay(card.deckId)) continue;
            double score = 0.0;
            for (CardVisualFeatures.Feature ref : references(card.id)) {
                score = Math.max(score, CardVisualFeatures.similarity(live, ref));
            }
            if (score > best) { second = best; best = score; bestId = card.id; }
            else if (score > second) second = score;
        }
        double margin = best - second;
        if (bestId == null || best < 0.89 || margin < 0.065) return null;
        double confidence = best * 0.78 + ColorMath.clamp(margin / 0.16, 0.0, 1.0) * 0.22;
        return new VisualMatch(bestId, confidence);
    }

    private List<CardVisualFeatures.Feature> references(String id) {
        List<CardVisualFeatures.Feature> cached = art.get(id);
        if (cached != null) return cached;
        ArrayList<CardVisualFeatures.Feature> out = new ArrayList<>();
        Bitmap bitmap = CardIconLoader.load(context, id);
        if (bitmap != null) {
            BitmapPixelFrame frame = new BitmapPixelFrame(bitmap);
            for (FrameRect crop : new FrameRect[]{
                    new FrameRect(0.04, 0.03, 0.96, 0.96),
                    new FrameRect(0.12, 0.07, 0.88, 0.86),
                    new FrameRect(0.17, 0.11, 0.83, 0.78)}) {
                CardVisualFeatures.Feature feature = CardVisualFeatures.extract(frame, crop);
                if (feature != null) out.add(feature);
            }
        }
        art.put(id, out);
        return out;
    }

    private static final class VisualMatch {
        final String cardId; final double confidence;
        VisualMatch(String cardId, double confidence) { this.cardId = cardId; this.confidence = confidence; }
    }
}
