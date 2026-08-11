package com.huseyn.elixircollector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads RoyaleAPI card artwork bundled into the APK at build time. */
public final class CardIconLoader {
    private static final int MAX_CACHE = 64;
    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(MAX_CACHE, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    public static void setCard(ImageView view, String cardId) {
        if (view == null) return;
        Bitmap bitmap = load(view.getContext(), cardId);
        if (bitmap == null) {
            view.setImageDrawable(null);
            view.setContentDescription(cardId == null ? "Unknown card" : cardId);
            return;
        }
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setContentDescription(cardId);
    }

    public static void setCardForm(ImageView view, String cardId, SpecialFormCalibration.Form form) {
        if (view == null) return;
        Bitmap bitmap = loadForm(view.getContext(), cardId, form);
        if (bitmap == null) bitmap = load(view.getContext(), cardId);
        if (bitmap == null) {
            view.setImageDrawable(null);
            view.setContentDescription(cardId == null ? "Unknown card" : cardId);
            return;
        }
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setContentDescription(cardId + " " + (form == null ? "NORMAL" : form.name()));
    }

    public static synchronized Bitmap load(Context context, String cardId) {
        if (context == null || cardId == null || cardId.length() == 0) return null;
        String key = canonicalAssetName(cardId);
        return loadAsset(context, key);
    }

    /** Loads Evo/Hero art when that exact asset exists, otherwise safely falls back to base art. */
    public static synchronized Bitmap loadForm(Context context, String cardId, SpecialFormCalibration.Form form) {
        if (context == null || cardId == null || cardId.length() == 0) return null;
        String base = canonicalAssetName(cardId);
        if (form == SpecialFormCalibration.Form.EVO) {
            Bitmap b = loadAsset(context, base + "-ev1");
            if (b != null) return b;
        } else if (form == SpecialFormCalibration.Form.HERO) {
            Bitmap b = loadAsset(context, base + "-hero");
            if (b != null) return b;
            b = loadAsset(context, base + "-hero-ev1");
            if (b != null) return b;
        }
        return loadAsset(context, base);
    }

    private static Bitmap loadAsset(Context context, String key) {
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        try (InputStream in = context.getAssets().open("cards/" + key + ".png")) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) CACHE.put(key, bitmap);
            return bitmap;
        } catch (IOException ignored) {
            return null;
        }
    }

    public static String canonicalAssetName(String cardId) {
        String s = cardId.toLowerCase().replace('_', '-');
        if (s.equals("spirit-empress-ground") || s.equals("spirit-empress-flying")) {
            return "spirit-empress";
        }
        return s;
    }

    private CardIconLoader() {}
}
