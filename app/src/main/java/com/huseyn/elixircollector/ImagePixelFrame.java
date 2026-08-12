package com.huseyn.elixircollector;

import android.media.Image;

import java.nio.ByteBuffer;

/** Zero-copy RGBA_8888 MediaProjection image adapter. */
public final class ImagePixelFrame implements PixelFrame {
    private final int width;
    private final int height;
    private final ByteBuffer buffer;
    private final int rowStride;
    private final int pixelStride;
    private final int limit;

    public ImagePixelFrame(Image image) {
        if (image == null || image.getPlanes().length == 0) {
            width = height = rowStride = pixelStride = limit = 0;
            buffer = null;
            return;
        }
        Image.Plane plane = image.getPlanes()[0];
        width = image.getWidth();
        height = image.getHeight();
        buffer = plane.getBuffer();
        rowStride = plane.getRowStride();
        pixelStride = plane.getPixelStride();
        limit = buffer == null ? 0 : buffer.limit();
    }

    public boolean valid() {
        return buffer != null && width >= 200 && height >= 300 && rowStride > 0 && pixelStride >= 4;
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override public int rgb(int x, int y) {
        if (!valid()) return 0;
        x = ColorMath.clamp(x, 0, width - 1);
        y = ColorMath.clamp(y, 0, height - 1);
        int offset = y * rowStride + x * pixelStride;
        if (offset < 0 || offset + 2 >= limit) return 0;
        int r = buffer.get(offset) & 255;
        int g = buffer.get(offset + 1) & 255;
        int b = buffer.get(offset + 2) & 255;
        return (r << 16) | (g << 8) | b;
    }
}
