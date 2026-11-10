package com.gfdrps.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import com.gfdrps.core.Engine;

/**
 * Recording screen: the waveform grows while it is being recorded, with a level meter, the elapsed time,
 * and SAVE / CANCEL buttons. Full screen for the microphone; a compact bar (the rest of the app stays playable)
 * for resampling the mix.
 */
final class RecordView extends View {
    interface Listener { void onSave(); void onCancel(); }

    private final Engine engine;
    private final boolean full;
    private final String title;
    private final Listener cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF save = new RectF(), cancel = new RectF();
    private float[] pk = new float[4096];
    private float peakHold;
    private long t0;

    RecordView(Context c, Engine e, boolean full, String title, Listener l) {
        super(c);
        engine = e; this.full = full; this.title = title; cb = l;
        t0 = System.currentTimeMillis();
        setClickable(full);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); t0 = System.currentTimeMillis(); postInvalidateOnAnimation(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        boolean onSave = save.contains(x, y), onCancel = cancel.contains(x, y);
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (onSave) cb.onSave(); else if (onCancel) cb.onCancel();
        }
        return full || onSave || onCancel;        // compact bar: everything outside the two buttons falls through
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int secs = engine.recSeconds;
        long ms = System.currentTimeMillis() - t0;
        p.setStyle(Paint.Style.FILL);
        p.setColor(full ? 0xF2090C10 : 0xF0141A21);
        c.drawRect(0, 0, w, h, p);

        float pad = Math.min(w, h) * 0.04f;
        float bh = full ? h * 0.16f : h * 0.62f;
        float bw = full ? w * 0.2f : w * 0.12f;
        float by = full ? h - pad - bh : (h - bh) / 2f;
        cancel.set(w - pad - bw, by, w - pad, by + bh);
        save.set(cancel.left - pad - bw * 1.25f, by, cancel.left - pad, by + bh);

        // title + blinking record dot + clock
        boolean blink = (ms / 500) % 2 == 0;
        float ts = full ? Math.min(w, h) * 0.07f : h * 0.3f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(ts);
        float ty = full ? pad + ts : h * 0.34f;
        p.setColor(blink ? 0xFFFF3B4E : 0x55FF3B4E);
        c.drawCircle(pad + ts * 0.4f, ty - ts * 0.33f, ts * 0.32f, p);
        p.setColor(0xFFE9EDF2);
        c.drawText(title, pad + ts * 1.1f, ty, p);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        float clockTs = full ? ts * 1.5f : ts * 1.1f;
        p.setTextSize(clockTs);
        String clock = String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60);
        if (full) c.drawText(clock, w - pad, ty, p);
        else c.drawText(clock, save.left - pad, ty, p);

        // waveform: the whole take so far, compressed to fit; newest on the right edge of what has been recorded
        float wx0 = pad, wx1 = full ? w - pad - w * 0.05f : save.left - pad * 3f - clockTs * 2.4f;
        float wy0 = full ? ty + ts * 0.8f : h * 0.42f, wy1 = full ? by - pad : h - h * 0.1f;
        if (!full) { wx1 = Math.max(wx0 + 10, wx1); }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0D1117);
        c.drawRect(wx0, wy0, wx1, wy1, p);
        int n = engine.recordingPeaks(pk);
        if (n == pk.length) pk = java.util.Arrays.copyOf(pk, pk.length * 2);
        float mid = (wy0 + wy1) / 2f, amp = (wy1 - wy0) / 2f * 0.92f;
        float ww = wx1 - wx0;
        int cols = Math.max(1, (int) (ww / 3f));                // 3 px per column
        float perSec = 50f;
        // while the take is short it fills from the left at a fixed speed, then it is squeezed to always show everything
        float binsPerCol = Math.max(1f, n / (float) cols);
        p.setColor(0xFFFF6B7A);
        p.setStrokeWidth(2f);
        int drawn = 0;
        for (int col = 0; col < cols; col++) {
            int a = (int) (col * binsPerCol), b = Math.min(n, (int) ((col + 1) * binsPerCol) + (binsPerCol <= 1f ? 1 : 0));
            if (a >= n) break;
            float m = 0f;
            for (int i = a; i < Math.max(b, a + 1) && i < n; i++) m = Math.max(m, pk[i]);
            float hh = Math.max(1.5f, m * amp);
            float x = wx0 + col * 3f + 1.5f;
            c.drawLine(x, mid - hh, x, mid + hh, p);
            drawn++;
        }
        p.setColor(0x55FFFFFF); p.setStrokeWidth(1f);
        c.drawLine(wx0, mid, wx1, mid, p);
        if (drawn > 0) { p.setColor(0xFFFFFFFF); p.setStrokeWidth(2f); float hx = wx0 + drawn * 3f; c.drawLine(hx, wy0, hx, wy1, p); }

        // level meter (full screen: vertical bar at the right; compact: thin bar under the waveform)
        float lv = engine.recLevel;
        peakHold = Math.max(lv, peakHold * 0.97f);
        p.setStyle(Paint.Style.FILL);
        if (full) {
            float mx0 = w - pad - w * 0.035f, mx1 = w - pad;
            p.setColor(0xFF1B222B);
            c.drawRect(mx0, wy0, mx1, wy1, p);
            float hgt = (wy1 - wy0) * lv;
            p.setColor(lv > 0.95f ? 0xFFFF3B4E : (lv > 0.7f ? 0xFFFFD23F : 0xFF52D273));
            c.drawRect(mx0, wy1 - hgt, mx1, wy1, p);
            p.setColor(0xFFFFFFFF);
            float py = wy1 - (wy1 - wy0) * Math.min(1f, peakHold);
            c.drawRect(mx0, py - 2, mx1, py + 2, p);
        } else {
            float my = wy1 + h * 0.02f;
            p.setColor(0xFF1B222B); c.drawRect(wx0, my, wx1, my + h * 0.06f, p);
            p.setColor(lv > 0.95f ? 0xFFFF3B4E : (lv > 0.7f ? 0xFFFFD23F : 0xFF52D273));
            c.drawRect(wx0, my, wx0 + ww * lv, my + h * 0.06f, p);
        }

        // buttons
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFF3B4E); c.drawRoundRect(save, 10, 10, p);
        p.setColor(0xFF2A313B); c.drawRoundRect(cancel, 10, 10, p);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(Math.min(bh * 0.4f, bw * 0.2f));
        p.setColor(0xFFFFFFFF);
        c.drawText("■ SALVAR", save.centerX(), save.centerY() + p.getTextSize() * 0.35f, p);
        p.setColor(0xFFE9EDF2);
        c.drawText("CANCELAR", cancel.centerX(), cancel.centerY() + p.getTextSize() * 0.35f, p);

        postInvalidateOnAnimation();
    }
}
