package dev.spundone.dialcode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * M3 Expressive countdown around the screen edge: a wavy indicator for the time left, a gap, then a
 * flat track. Redrawn once a second by the activity, never animated.
 */
final class RingView extends View {
    private static final int WAVES = 22;
    private static final float GAP_DEG = 7f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();
    private final float amp;
    private final int primary, error;
    private float fraction = -1f;
    private boolean urgent;

    RingView(Context c, Palette p) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        amp = 1.6f * d;
        primary = p.primary;
        error = p.error;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(5f * d);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(p.surfaceContainerHigh);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(5f * d);
        wave.setStrokeCap(Paint.Cap.ROUND);
        wave.setStrokeJoin(Paint.Join.ROUND);
    }

    /** @param fraction remaining part of the window, 0..1, or negative to hide. */
    void set(float fraction, boolean urgent) {
        if (fraction == this.fraction && urgent == this.urgent) return;
        this.fraction = fraction;
        this.urgent = urgent;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (fraction < 0) return;
        float inset = track.getStrokeWidth() / 2f + amp + 2f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - inset;
        float sweep = 360f * fraction;

        // Flat track for the elapsed part, separated from the wave by a gap at both ends.
        float trackSweep = 360f - sweep - 2 * GAP_DEG;
        if (trackSweep > 0) {
            box.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(box, -90f + sweep + GAP_DEG, trackSweep, false, track);
        }
        if (sweep <= 0.5f) return;

        path.rewind();
        int steps = Math.max(8, (int) (sweep * 2));
        for (int i = 0; i <= steps; i++) {
            double deg = -90.0 + sweep * i / steps;
            double rad = Math.toRadians(deg);
            // Phase is anchored to absolute angle so the wave stays still while the arc shrinks.
            float rr = r + amp * (float) Math.sin(Math.toRadians((deg + 90) * WAVES));
            float x = cx + rr * (float) Math.cos(rad);
            float y = cy + rr * (float) Math.sin(rad);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        wave.setColor(urgent ? error : primary);
        c.drawPath(path, wave);
    }
}
