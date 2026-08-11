package com.huseyn.elixircollector;

/** Immutable normalized rectangle. */
public final class FrameRect {
    public final double left;
    public final double top;
    public final double right;
    public final double bottom;

    public FrameRect(double left, double top, double right, double bottom) {
        this.left = ColorMath.clamp(left, 0.0, 1.0);
        this.top = ColorMath.clamp(top, 0.0, 1.0);
        this.right = ColorMath.clamp(Math.max(this.left, right), 0.0, 1.0);
        this.bottom = ColorMath.clamp(Math.max(this.top, bottom), 0.0, 1.0);
    }

    public double width() { return right - left; }
    public double height() { return bottom - top; }
    public double centerX() { return (left + right) * 0.5; }
    public double centerY() { return (top + bottom) * 0.5; }

    public FrameRect inset(double x, double y) {
        return new FrameRect(left + x, top + y, right - x, bottom - y);
    }
}
