package com.gfdrps.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import com.gfdrps.core.Engine;
import com.gfdrps.core.Fx;

/**
 * Kaoss-style XY pad for the selected effect of the selected slot (or the master bus).
 * Touching switches the effect on; letting go switches it off unless it is latched.
 */
public final class XYPadView extends View {
    private final Engine engine;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int slot, fxIndex;
    private boolean master, latch, touching;
    private float tx = 0.5f, ty = 0.5f;

    public XYPadView(Context c, Engine e) {
        super(c);
        engine = e;
        setBackgroundColor(Theme.WAVE_BG);
    }

    public void select(int slot, int fx, boolean master) {
        this.slot = slot; this.fxIndex = fx; this.master = master;
        Fx.Effect ef = effect();
        tx = ef.x; ty = ef.y;
        invalidate();
    }

    public void setLatch(boolean l) {
        latch = l;
        if (!l && !touching) effect().active = false;
        invalidate();
    }

    public boolean isLatch() { return latch; }

    private Fx.Effect effect() { return master ? engine.master[fxIndex] : engine.slots[slot].fx[fxIndex]; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            touching = true;
            tx = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
            ty = Math.max(0f, Math.min(1f, 1f - e.getY() / Math.max(1, getHeight())));
            Fx.Effect ef = effect();
            ef.setXY(tx, ty);
            ef.active = true;
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            touching = false;
            if (!latch) effect().active = false;
        }
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        Fx.Effect ef = effect();
        int col = Theme.FX[fxIndex];
        boolean on = ef.active;
        // response hint curve
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0x33FFFFFF);
        for (int i = 1; i < 4; i++) {
            c.drawLine(i * w / 4, 0, i * w / 4, h, p);
            c.drawLine(0, i * h / 4, w, i * h / 4, p);
        }
        path.reset();
        for (int i = 0; i <= 64; i++) {
            float x = i / 64f;
            float y = curve(x, ef);
            float px = x * w, py = h - (0.1f + 0.8f * y) * h;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        p.setStrokeWidth(3f);
        p.setColor(on ? col : 0x66FFFFFF);
        c.drawPath(path, p);
        float cx = tx * w, cy = (1f - ty) * h;
        if (on) {
            p.setStyle(Paint.Style.FILL);
            p.setColor((col & 0x00FFFFFF) | 0x33000000);
            c.drawCircle(cx, cy, Math.min(w, h) * 0.28f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(col);
            p.setStrokeWidth(2f);
            c.drawLine(cx, 0, cx, h, p);
            c.drawLine(0, cy, w, cy, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, Math.min(w, h) * 0.07f, p);
        // label
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99FFFFFF);
        p.setTextSize(Math.min(w, h) * 0.075f);
        c.drawText((master ? "MASTER · " : "") + Fx.NAMES[fxIndex] + (latch ? "  (hold)" : ""), w * 0.03f, h * 0.1f, p);
    }

    /** A small sketch of what the effect does along x, just for orientation. */
    private float curve(float x, Fx.Effect ef) {
        switch (fxIndex) {
            case Fx.DIST: return (float) Math.tanh((x * 6f) - 2f) * 0.5f + 0.5f;
            case Fx.FILTER: {
                float fc = tx;
                float res = 0.15f + 0.6f * ty;
                float d = (x - fc) * 6f;
                return (float) (1f / (1f + Math.exp(d * 2)) * 0.6f + res * Math.exp(-d * d));
            }
            case Fx.AMP: return 0.5f + 0.4f * (float) Math.sin(x * (3f + tx * 20f) * Math.PI) * ty;
            case Fx.DELAY: return (float) Math.pow(0.5f + 0.4f * ty, x * 6f);
            default: return (float) Math.exp(-x * (5f - tx * 4f)) * (0.3f + 0.7f * ty);
        }
    }
}
