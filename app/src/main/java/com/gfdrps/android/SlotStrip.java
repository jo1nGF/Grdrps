package com.gfdrps.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.gfdrps.core.Engine;
import com.gfdrps.core.Sample;
import com.gfdrps.core.Slot;

/** One thumbnail of a slot: mini waveform, level glow, selection underline. Tap selects, long-press opens its menu. */
public final class SlotStrip extends View {
    public interface Callbacks { void onSelect(int slot); void onMenu(int slot); }

    private final Engine engine;
    private final int index;
    private final Callbacks cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean selected;
    private long downAt;

    public SlotStrip(Context c, Engine e, int index, Callbacks cb) {
        super(c);
        engine = e;
        this.index = index;
        this.cb = cb;
        setClickable(true);
    }

    public void setSelected(boolean s) { selected = s; invalidate(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downAt = e.getEventTime(); return true; }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (e.getEventTime() - downAt > 450) cb.onMenu(index); else cb.onSelect(index);
            return true;
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        Slot s = engine.slots[index];
        p.setStyle(Paint.Style.FILL);
        p.setColor(selected ? 0xFF2B3038 : Theme.TILE);
        c.drawRect(1, 1, w - 1, h - 1, p);
        Sample sm = s.sample;
        int col = Theme.MODE[s.mode];
        if (sm != null) {
            p.setColor(s.muted ? 0xFF4A515B : col);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            int cols = Math.max(8, (int) (w / 4));
            float mid = h / 2f;
            for (int i = 0; i < cols; i++) {
                int pk = i * Sample.PEAKS / cols;
                float up = Math.max(sm.peakMax[pk], -sm.peakMin[pk]) * h * 0.4f;
                float x = 4 + i * (w - 8) / cols;
                c.drawLine(x, mid - up, x, mid + up, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        float lv = Math.min(1f, s.level * 1.5f);
        if (lv > 0.02f) {
            p.setColor((col & 0x00FFFFFF) | ((int) (lv * 120) << 24));
            c.drawRect(1, 1, w - 1, h - 1, p);
        }
        p.setColor(selected ? col : 0x00000000);
        c.drawRect(1, h - 4, w - 1, h - 1, p);
        p.setColor(0xCCFFFFFF);
        p.setTextSize(h * 0.22f);
        c.drawText(String.valueOf(index + 1), 6, h * 0.26f, p);
        if (s.gestures.state != 0) {
            p.setColor(s.gestures.state == 3 ? 0xFF52D273 : 0xFFFF3B4E);
            c.drawCircle(w - h * 0.16f, h * 0.2f, h * 0.07f, p);
        }
        postInvalidateOnAnimation();
    }
}
