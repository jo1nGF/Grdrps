package com.gfdrps.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Flat touch controls drawn on a Canvas: tiles and sliders. */
public final class Widgets {
    private Widgets() {}

    public interface OnValue { void onValue(float v); }

    /** A rectangular button showing an icon or text. Can be a toggle with an accent color. */
    public static class Tile extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int icon = -1;
        private String text;
        private int accent = 0xFFFFFFFF;
        private boolean on, dim;
        private float textScale = 0.42f;
        private Runnable click, longClick;
        private Drag drag;
        private float downX, lastX; private boolean dragging;

        /** Horizontal slide after pressing the tile. total = px since the press, delta = px since the last event. */
        public interface Drag { void drag(float total, float delta); void end(); }
        public Tile onDrag(Drag d) { drag = d; return this; }

        public Tile(Context c) {
            super(c);
            p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            p.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
        }

        public Tile icon(int id) { icon = id; text = null; invalidate(); return this; }
        public Tile text(String t) { text = t; icon = -1; invalidate(); return this; }
        public Tile textScale(float s) { textScale = s; return this; }
        public Tile accent(int c) { accent = c; invalidate(); return this; }
        public Tile onClick(Runnable r) { click = r; return this; }
        public Tile onLong(Runnable r) { longClick = r; return this; }
        public void setOn(boolean v) { if (on != v) { on = v; invalidate(); } }
        public boolean isOn() { return on; }
        public void setDim(boolean v) { if (dim != v) { dim = v; invalidate(); } }

        private boolean pressed;
        private long downAt;

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true; downAt = e.getEventTime(); downX = lastX = e.getX(); dragging = false;
                    if (drag != null && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate(); return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag != null) {
                        float tot = e.getX() - downX;
                        float slop = getResources().getDisplayMetrics().density * 10f;
                        if (!dragging && Math.abs(tot) > slop) dragging = true;
                        if (dragging) { drag.drag(tot, e.getX() - lastX); }
                        lastX = e.getX();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    pressed = false; invalidate();
                    if (dragging) { dragging = false; if (drag != null) drag.end(); return true; }
                    if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                        if (longClick != null && e.getEventTime() - downAt > 450) longClick.run();
                        else if (click != null) click.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false; if (dragging && drag != null) drag.end(); dragging = false; invalidate(); return true;
                default: return true;
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(pressed ? Theme.TILE_ON : (on ? 0xFF2B3038 : Theme.TILE));
            c.drawRect(1, 1, w - 1, h - 1, p);
            int col = on ? accent : (dim ? 0xFF4A515B : Theme.DIM);
            if (pressed) col = Theme.TEXT;
            p.setColor(col);
            if (icon >= 0) {
                Theme.icon(c, icon, w / 2, h / 2, Math.min(w, h) * 0.3f, p);
            } else if (text != null) {
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(Math.min(h * textScale * 1.4f, w * 0.7f / Math.max(1f, text.length() * 0.45f)));
                c.drawText(text, w / 2, h / 2 + p.getTextSize() * 0.35f, p);
            }
            if (on) {
                p.setColor(accent);
                c.drawRect(1, h - 3, w - 1, h - 1, p);
            }
        }
    }

    /** Vertical or horizontal fader. */
    public static class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean vertical;
        private float value;
        private int accent = 0xFFFFFFFF;
        private int icon = -1;
        private OnValue cb;
        private final RectF r = new RectF();

        public Slider(Context c, boolean vertical) { super(c); this.vertical = vertical; setClickable(true); }
        public Slider accent(int a) { accent = a; return this; }
        public Slider icon(int i) { icon = i; return this; }
        public Slider onValue(OnValue v) { cb = v; return this; }
        public float value() { return value; }
        public void set(float v) { value = Math.max(0f, Math.min(1f, v)); invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                float pad = Math.min(getWidth(), getHeight()) * 0.5f;
                float v = vertical ? 1f - (e.getY() - pad) / Math.max(1f, getHeight() - 2 * pad)
                        : (e.getX() - pad) / Math.max(1f, getWidth() - 2 * pad);
                set(v);
                if (cb != null) cb.onValue(value);
            }
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.TILE);
            c.drawRect(1, 1, w - 1, h - 1, p);
            float pad = Math.min(w, h) * 0.5f;
            float knob = Math.min(w, h) * 0.42f;
            float cx, cy;
            p.setColor(0xFF3A414B);
            if (vertical) {
                cx = w / 2; cy = pad + (1f - value) * (h - 2 * pad);
                c.drawRect(cx - 2, pad, cx + 2, h - pad, p);
                p.setColor(accent);
                c.drawRect(cx - 2, cy, cx + 2, h - pad, p);
            } else {
                cy = h / 2; cx = pad + value * (w - 2 * pad);
                c.drawRect(pad, cy - 2, w - pad, cy + 2, p);
                p.setColor(accent);
                c.drawRect(pad, cy - 2, cx, cy + 2, p);
            }
            p.setColor(0xFFE9EDF2);
            c.drawCircle(cx, cy, knob, p);
            if (icon >= 0) {
                p.setColor(0xFF22262D);
                Theme.icon(c, icon, cx, cy, knob * 0.55f, p);
            }
        }
    }
}
