package com.huseyn.elixircollector;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative detector for a moving purple deployment-cost badge. It is an
 * observation source only; event fusion decides whether it belongs to a card.
 */
public final class CostBadgeDetector {
    public static final class Detection {
        public final int cost;
        public final double confidence;
        public final int x;
        public final int y;
        public final double normalizedX;
        public final double normalizedY;
        public final FrameRect region;
        public final long timeMs;

        Detection(int cost, double confidence, int x, int y, double normalizedX,
                  double normalizedY, FrameRect region, long timeMs) {
            this.cost = cost;
            this.confidence = confidence;
            this.x = x;
            this.y = y;
            this.normalizedX = normalizedX;
            this.normalizedY = normalizedY;
            this.region = region;
            this.timeMs = timeMs;
        }
    }

    private static final int NORM_W = 16, NORM_H = 24;
    private final List<DigitTemplate> templates = new ArrayList<>();
    private byte[] previousGray;
    private int previousW, previousH;
    private Detection lastAccepted;

    public CostBadgeDetector() { buildTemplates(); }

    public void reset() {
        previousGray = null;
        previousW = previousH = 0;
        lastAccepted = null;
    }

    public Detection detect(PixelFrame f, long nowMs) {
        if (f == null || f.width() < 200 || f.height() < 300) return null;
        int w = f.width(), h = f.height();
        int step = Math.max(5, Math.min(10, w / 180));
        int left = (int)(w * 0.025), right = (int)(w * 0.975);
        int top = (int)(h * 0.070), bottom = (int)(h * 0.765);
        int mw = Math.max(1, (right - left + step - 1) / step);
        int mh = Math.max(1, (bottom - top + step - 1) / step);
        int total = mw * mh;
        boolean[] purple = new boolean[total];
        byte[] gray = new byte[total];
        for (int my = 0; my < mh; my++) {
            int y = Math.min(bottom - 1, top + my * step);
            for (int mx = 0; mx < mw; mx++) {
                int x = Math.min(right - 1, left + mx * step), idx = my * mw + mx;
                int rgb = f.rgb(x, y);
                purple[idx] = isBadgePurple(rgb);
                gray[idx] = (byte)Math.round(ColorMath.luminance(rgb) * 255.0);
            }
        }
        byte[] prev = previousGray;
        boolean comparable = prev != null && previousW == mw && previousH == mh;
        previousGray = gray;
        previousW = mw;
        previousH = mh;
        if (!comparable) return null;

        boolean[] visited = new boolean[total];
        int[] queue = new int[total];
        Detection best = null;
        for (int start = 0; start < total; start++) {
            if (!purple[start] || visited[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            int area = 0, minX = mw, maxX = 0, minY = mh, maxY = 0;
            long motionSum = 0;
            while (head < tail) {
                int idx = queue[head++], x = idx % mw, y = idx / mw;
                area++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                motionSum += Math.abs((gray[idx] & 255) - (prev[idx] & 255));
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= mw || ny < 0 || ny >= mh) continue;
                    int ni = ny * mw + nx;
                    if (purple[ni] && !visited[ni]) { visited[ni] = true; queue[tail++] = ni; }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (area < 5 || area > 360 || bw < 2 || bh < 2 || bw > 30 || bh > 34) continue;
            double aspect = bw / (double)bh;
            double fill = area / (double)(bw * bh);
            double motion = motionSum / (double)area;
            double roundness = 1.0 - ColorMath.clamp(Math.abs(Math.log(Math.max(0.01, aspect))) / 1.25, 0.0, 1.0);
            if (aspect < 0.38 || aspect > 1.90 || fill < 0.16 || fill > 0.94 || motion < 7.0) continue;

            int padX = Math.max(step * 2, bw * step / 2);
            int padY = Math.max(step * 2, bh * step / 2);
            Rect box = new Rect(
                    ColorMath.clamp(left + minX * step - padX, 0, w - 1),
                    ColorMath.clamp(top + minY * step - padY, 0, h - 1),
                    ColorMath.clamp(left + (maxX + 1) * step + padX, 1, w),
                    ColorMath.clamp(top + (maxY + 1) * step + padY, 1, h));
            DigitResult digit = recognize(f, box);
            if (digit == null || digit.confidence < 0.55) continue;
            double motionScore = ColorMath.clamp((motion - 7.0) / 34.0, 0.0, 1.0);
            double confidence = digit.confidence * 0.72 + roundness * 0.12
                    + motionScore * 0.10 + ColorMath.clamp(fill / 0.55, 0.0, 1.0) * 0.06;
            FrameRect nr = new FrameRect(box.left / (double)w, box.top / (double)h,
                    box.right / (double)w, box.bottom / (double)h);
            int centerX = (box.left + box.right) / 2;
            int centerY = (box.top + box.bottom) / 2;
            Detection d = new Detection(digit.digit, ColorMath.clamp(confidence, 0.0, 1.0),
                    centerX, centerY, centerX / (double)w, centerY / (double)h, nr, nowMs);
            if (best == null || d.confidence > best.confidence) best = d;
        }
        if (best == null) return null;
        if (lastAccepted != null && best.cost == lastAccepted.cost && nowMs - lastAccepted.timeMs < 650) {
            double dx = best.x - lastAccepted.x, dy = best.y - lastAccepted.y;
            if (Math.hypot(dx, dy) < Math.min(w, h) * 0.075) return null;
        }
        lastAccepted = best;
        return best;
    }

    private DigitResult recognize(PixelFrame f, Rect box) {
        int minX = box.right, minY = box.bottom, maxX = box.left - 1, maxY = box.top - 1, bright = 0;
        int scan = Math.max(1, Math.min(box.width(), box.height()) / 42);
        for (int y = box.top; y < box.bottom; y += scan) for (int x = box.left; x < box.right; x += scan) {
            if (isDigitWhite(f.rgb(x, y))) {
                bright++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (bright < 7 || maxX <= minX || maxY <= minY) return null;
        int gw = maxX - minX + 1, gh = maxY - minY + 1;
        double aspect = gw / (double)gh;
        if (aspect > 1.02 || aspect < 0.07) return null;
        boolean[] glyph = new boolean[NORM_W * NORM_H];
        int on = 0;
        for (int oy = 0; oy < NORM_H; oy++) for (int ox = 0; ox < NORM_W; ox++) {
            int x0 = minX + ox * gw / NORM_W;
            int x1 = minX + Math.max(1, (ox + 1) * gw / NORM_W);
            int y0 = minY + oy * gh / NORM_H;
            int y1 = minY + Math.max(1, (oy + 1) * gh / NORM_H);
            boolean found = false;
            for (int y = y0; y <= Math.min(maxY, y1) && !found; y++)
                for (int x = x0; x <= Math.min(maxX, x1); x++) if (isDigitWhite(f.rgb(x, y))) {
                    found = true; break;
                }
            glyph[oy * NORM_W + ox] = found;
            if (found) on++;
        }
        if (on < 12 || on > glyph.length * 0.72) return null;
        int bestDigit = -1;
        double best = 0.0, second = 0.0;
        for (DigitTemplate template : templates) {
            double score = shiftedF1(glyph, template.mask);
            if (score > best) { second = best; best = score; bestDigit = template.digit; }
            else if (score > second) second = score;
        }
        double margin = Math.max(0.0, best - second);
        double confidence = Math.min(1.0, best * 0.86 + margin * 1.55);
        return bestDigit < 0 ? null : new DigitResult(bestDigit, confidence);
    }

    private void buildTemplates() {
        Typeface[] faces = {Typeface.DEFAULT_BOLD, Typeface.MONOSPACE, Typeface.create(Typeface.SERIF, Typeface.BOLD)};
        for (int d = 1; d <= 9; d++) for (Typeface face : faces) {
            templates.add(new DigitTemplate(d, renderDigit(d, face)));
        }
    }

    private boolean[] renderDigit(int digit, Typeface face) {
        Bitmap bitmap = Bitmap.createBitmap(56, 76, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(62f);
        paint.setTypeface(face);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = 38f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(String.valueOf(digit), 28f, baseline, paint);
        int minX = bitmap.getWidth(), minY = bitmap.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 70) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        boolean[] out = new boolean[NORM_W * NORM_H];
        if (maxX > minX && maxY > minY) {
            int w = maxX - minX + 1, h = maxY - minY + 1;
            for (int oy = 0; oy < NORM_H; oy++) for (int ox = 0; ox < NORM_W; ox++) {
                int sx = minX + ox * w / NORM_W, sy = minY + oy * h / NORM_H;
                out[oy * NORM_W + ox] = Color.alpha(bitmap.getPixel(sx, sy)) > 70;
            }
        }
        bitmap.recycle();
        return out;
    }

    private static double shiftedF1(boolean[] a, boolean[] template) {
        double best = 0.0;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int inter = 0, ac = 0, tc = 0;
            for (int y = 0; y < NORM_H; y++) for (int x = 0; x < NORM_W; x++) {
                boolean av = a[y * NORM_W + x];
                int tx = x - dx, ty = y - dy;
                boolean tv = tx >= 0 && tx < NORM_W && ty >= 0 && ty < NORM_H
                        && template[ty * NORM_W + tx];
                if (av) ac++;
                if (tv) tc++;
                if (av && tv) inter++;
            }
            double score = ac + tc == 0 ? 0.0 : 2.0 * inter / (ac + tc);
            best = Math.max(best, score);
        }
        return best;
    }

    private static boolean isBadgePurple(int rgb) {
        double h = ColorMath.hue(rgb), s = ColorMath.saturation(rgb), v = ColorMath.value(rgb);
        int r = ColorMath.red(rgb), g = ColorMath.green(rgb), b = ColorMath.blue(rgb);
        return s >= 0.31 && v >= 0.37 && h >= 0.70 && h <= 0.94
                && r + b >= g * 1.90 + 36;
    }

    private static boolean isDigitWhite(int rgb) {
        int r = ColorMath.red(rgb), g = ColorMath.green(rgb), b = ColorMath.blue(rgb);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return ColorMath.luminance(rgb) >= 0.64 && max - min <= 96;
    }

    private static final class DigitResult {
        final int digit; final double confidence;
        DigitResult(int digit, double confidence) { this.digit = digit; this.confidence = confidence; }
    }
    private static final class DigitTemplate {
        final int digit; final boolean[] mask;
        DigitTemplate(int digit, boolean[] mask) { this.digit = digit; this.mask = mask; }
    }
}
