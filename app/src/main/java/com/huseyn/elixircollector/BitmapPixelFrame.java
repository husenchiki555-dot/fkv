package com.huseyn.elixircollector;

import android.graphics.Bitmap;

/** Bitmap adapter used for bundled card-art templates. */
public final class BitmapPixelFrame implements PixelFrame {
    private final Bitmap bitmap;
    public BitmapPixelFrame(Bitmap bitmap) { this.bitmap = bitmap; }
    @Override public int width() { return bitmap == null ? 0 : bitmap.getWidth(); }
    @Override public int height() { return bitmap == null ? 0 : bitmap.getHeight(); }
    @Override public int rgb(int x, int y) {
        if (bitmap == null || bitmap.isRecycled()) return 0;
        x = ColorMath.clamp(x, 0, bitmap.getWidth() - 1);
        y = ColorMath.clamp(y, 0, bitmap.getHeight() - 1);
        return bitmap.getPixel(x, y) & 0x00ffffff;
    }
}
