package com.gfdrps.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * In-app reference overlay: the real screen stays visible and every control gets a numbered badge;
 * the legend (over the waveform area) says what each number does. Tap anywhere to close.
 */
final class HelpOverlay extends View {
    private static final class Item { View v; String text; Item(View v, String t) { this.v = v; text = t; } }

    private final List<Item> items = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private View legendArea;
    private String heading = "";
    private Runnable onClose;

    HelpOverlay(Context c) { super(c); setClickable(true); }

    HelpOverlay add(View v, String text) { if (v != null) items.add(new Item(v, text)); return this; }
    HelpOverlay legendIn(View v) { legendArea = v; return this; }
    HelpOverlay heading(String h) { heading = h; return this; }
    HelpOverlay onClose(Runnable r) { onClose = r; return this; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && onClose != null) onClose.run();
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int[] me = new int[2]; getLocationInWindow(me);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xB8070A0E);
        c.drawRect(0, 0, w, h, p);
        float badge = Math.min(w, h) * 0.022f;
        p.setTextAlign(Paint.Align.CENTER);
        int[] loc = new int[2];
        for (int i = 0; i < items.size(); i++) {
            View v = items.get(i).v;
            if (v.getWidth() == 0) continue;
            v.getLocationInWindow(loc);
            float l = loc[0] - me[0], t = loc[1] - me[1], r = l + v.getWidth(), b = t + v.getHeight();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setColor(0xFFFFD23F);
            c.drawRoundRect(l + 2, t + 2, r - 2, b - 2, 8, 8, p);
            float cx = l + badge + 3, cy = t + badge + 3;
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFD23F);
            c.drawCircle(cx, cy, badge, p);
            p.setColor(0xFF111111); p.setTextSize(badge * 1.25f); p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(String.valueOf(i + 1), cx, cy + badge * 0.42f, p);
        }
        // legend
        float lx = 0, ly = h * 0.42f, lw = w, lh = h * 0.5f;
        if (legendArea != null && legendArea.getWidth() > 0) {
            legendArea.getLocationInWindow(loc);
            lx = loc[0] - me[0]; ly = loc[1] - me[1]; lw = legendArea.getWidth(); lh = legendArea.getHeight();
        }
        p.setStyle(Paint.Style.FILL); p.setColor(0xE60E1319);
        c.drawRoundRect(lx + 6, ly + 6, lx + lw - 6, ly + lh - 6, 14, 14, p);
        p.setTextAlign(Paint.Align.LEFT);
        float ts = Math.min(lh / 11.5f, w / 62f);
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(ts * 1.1f); p.setColor(0xFFFFD23F);
        float x0 = lx + 20, y = ly + 14 + ts * 1.1f;
        c.drawText(heading, x0, y, p);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(ts); p.setColor(0xFFE9EDF2);
        int cols = 3, perCol = (items.size() + cols - 1) / cols;
        float colW = (lw - 40) / cols;
        float top = y + ts * 0.6f;
        for (int i = 0; i < items.size(); i++) {
            int col = i / perCol, row = i % perCol;
            float xx = x0 + col * colW, yy = top + (row + 1) * ts * 1.18f;
            if (yy > ly + lh - 10) continue;
            p.setColor(0xFFFFD23F);
            c.drawText(String.valueOf(i + 1), xx, yy, p);
            p.setColor(0xFFE9EDF2);
            String t = items.get(i).text;
            float maxw = colW - ts * 2.2f;
            while (p.measureText(t) > maxw && t.length() > 4) t = t.substring(0, t.length() - 2);
            c.drawText(t, xx + ts * 1.7f, yy, p);
        }
        p.setColor(0x99FFFFFF); p.setTextSize(ts * 0.85f); p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("toque para fechar", lx + lw - 16, ly + lh - 14, p);
    }
}
