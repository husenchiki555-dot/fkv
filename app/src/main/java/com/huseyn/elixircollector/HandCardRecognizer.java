package com.huseyn.elixircollector;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eight-way hand classifier using richer spatial colour, histogram, and HOG-like
 * features. A global unique assignment prevents one template from occupying two
 * live hand slots. Low-margin matches remain unknown.
 */
public final class HandCardRecognizer {
    public static final class Result {
        public final String[] slotIds;
        public final double[] confidence;
        public final CardVisualFeatures.Feature[] appearance;
        public final int strongSlots;

        public Result(String[] ids, double[] confidence,
                      CardVisualFeatures.Feature[] appearance, int strongSlots) {
            this.slotIds = ids;
            this.confidence = confidence;
            this.appearance = appearance;
            this.strongSlots = strongSlots;
        }
    }

    private final Context context;
    private final Map<String, List<CardVisualFeatures.Feature>> references = new HashMap<>();
    private List<String> deck = new ArrayList<>();

    public HandCardRecognizer(Context context) {
        this.context = context.getApplicationContext();
        reloadDeck();
    }

    public void reloadDeck() {
        deck = DeckCalibrationActivity.loadDeck(context);
        references.clear();
        for (String id : deck) {
            ArrayList<CardVisualFeatures.Feature> refs = new ArrayList<>();
            addArtworkVariants(refs, CardIconLoader.load(context, id));
            SpecialFormCalibration.Form form = SpecialFormCalibration.get(context, id);
            if (form != SpecialFormCalibration.Form.NORMAL) {
                Bitmap special = CardIconLoader.loadForm(context, id, form);
                addArtworkVariants(refs, special);
            }
            if (!refs.isEmpty()) references.put(id, refs);
        }
    }

    public Result recognize(PixelFrame frame, HudLayoutTracker.Layout layout) {
        String[] unknown = new String[]{null, null, null, null};
        double[] emptyConfidence = new double[4];
        CardVisualFeatures.Feature[] live = new CardVisualFeatures.Feature[4];
        if (frame == null || layout == null || deck.size() != 8 || references.size() < 5) {
            return new Result(unknown, emptyConfidence, live, 0);
        }

        for (int slot = 0; slot < 4; slot++) {
            FrameRect r = layout.slotRect(slot);
            FrameRect art = new FrameRect(r.left + r.width() * 0.09, r.top + r.height() * 0.05,
                    r.right - r.width() * 0.09, r.bottom - r.height() * 0.13);
            live[slot] = CardVisualFeatures.extract(frame, art);
        }

        double[][] similarity = new double[4][deck.size()];
        for (int slot = 0; slot < 4; slot++) for (int d = 0; d < deck.size(); d++) {
            double best = 0.0;
            List<CardVisualFeatures.Feature> refs = references.get(deck.get(d));
            if (refs != null) for (CardVisualFeatures.Feature ref : refs) {
                best = Math.max(best, CardVisualFeatures.similarity(live[slot], ref));
            }
            similarity[slot][d] = best;
        }

        Assignment assignment = new Assignment();
        searchAssignment(similarity, 0, new int[]{-1,-1,-1,-1}, new HashSet<>(), 0.0, assignment);
        String[] ids = new String[4];
        double[] confidence = new double[4];
        int strong = 0;
        for (int slot = 0; slot < 4; slot++) {
            int selected = assignment.indices[slot];
            if (selected < 0) continue;
            double best = similarity[slot][selected];
            double second = 0.0;
            for (int d = 0; d < deck.size(); d++) if (d != selected) second = Math.max(second, similarity[slot][d]);
            double margin = Math.max(0.0, best - second);
            double c = best * 0.72 + ColorMath.clamp(margin / 0.13, 0.0, 1.0) * 0.28;
            if (best >= 0.56 && c >= 0.50) {
                ids[slot] = deck.get(selected);
                confidence[slot] = c;
                if (c >= 0.62) strong++;
            }
        }
        return new Result(ids, confidence, live, strong);
    }

    private void addArtworkVariants(List<CardVisualFeatures.Feature> out, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        BitmapPixelFrame f = new BitmapPixelFrame(bitmap);
        FrameRect[] crops = {
                new FrameRect(0.04, 0.03, 0.96, 0.96),
                new FrameRect(0.10, 0.06, 0.90, 0.88),
                new FrameRect(0.16, 0.10, 0.84, 0.78)
        };
        for (FrameRect crop : crops) {
            CardVisualFeatures.Feature feature = CardVisualFeatures.extract(f, crop);
            if (feature != null) out.add(feature);
        }
    }

    private void searchAssignment(double[][] sim, int slot, int[] current, Set<Integer> used,
                                  double score, Assignment best) {
        if (slot == 4) {
            if (score > best.score) {
                best.score = score;
                System.arraycopy(current, 0, best.indices, 0, 4);
            }
            return;
        }
        // Unknown is a real output, not a failure. Its score is intentionally
        // competitive with weak visual matches.
        current[slot] = -1;
        searchAssignment(sim, slot + 1, current, used, score + Math.log(0.55), best);
        for (int d = 0; d < deck.size(); d++) {
            if (used.contains(d)) continue;
            double s = sim[slot][d];
            if (s < 0.47) continue;
            used.add(d);
            current[slot] = d;
            searchAssignment(sim, slot + 1, current, used,
                    score + Math.log(Math.max(0.05, s)), best);
            used.remove(d);
        }
        current[slot] = -1;
    }

    private static final class Assignment {
        final int[] indices = {-1,-1,-1,-1};
        double score = -Double.MAX_VALUE;
    }
}
