package com.gfdrps.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import com.gfdrps.core.Engine;
import com.gfdrps.core.GestureRecorder;
import com.gfdrps.core.Sample;
import com.gfdrps.core.Slot;

/** The main instrument surface: shows the waveform and turns multi-touch into engine touch events. */
public final class WaveView extends View {
    private final Engine engine;
    private int slotIndex;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] pts = new float[Sample.PEAKS * 4];
    private Shader shader;
    private int shaderMode = -1;

    private static final int MAXP = 10;
    private final boolean[] down = new boolean[MAXP];
    private final float[] fx = new float[MAXP], fy = new float[MAXP], sx = new float[MAXP];

    public WaveView(Context c, Engine e) {
        super(c);
        engine = e;
        setBackgroundColor(Theme.WAVE_BG);
    }

    public void setSlot(int s) {
        if (s == slotIndex) return;
        releaseAll();
        slotIndex = s;
    }

    public int slot() { return slotIndex; }

    public void releaseAll() {
        for (int i = 0; i < MAXP; i++) if (down[i]) { engine.touch(slotIndex, 2, i, 0f, 0f); down[i] = false; }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { shader = null; }

    @Override protected void onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        int act = e.getActionMasked();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                if (id < MAXP) {
                    down[id] = true;
                    fx[id] = clamp(e.getX(idx) / w); fy[id] = clamp(e.getY(idx) / h); sx[id] = fx[id];
                    engine.touch(slotIndex, 0, id, fx[id], fy[id]);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= MAXP || !down[id]) continue;
                    float nx = clamp(e.getX(i) / w), ny = clamp(e.getY(i) / h);
                    if (nx != fx[id] || ny != fy[id]) {
                        fx[id] = nx; fy[id] = ny;
                        engine.touch(slotIndex, 1, id, nx, ny);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                if (id < MAXP && down[id]) { down[id] = false; engine.touch(slotIndex, 2, id, 0f, 0f); }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default: break;
        }
        return true;
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        Slot s = engine.slots[slotIndex];
        int mode = s.mode;
        int accent = Theme.MODE[mode];
        Sample sm = s.sample;

        if (mode == Slot.KEYBOARD) drawKeys(c, w, h);
        if (mode == Slot.ARP) drawGrid(c, w, h, s.div <= 0 ? 8 : s.div, 0x22FFFFFF);

        // waveform
        if (sm != null) {
            if (shader == null || shaderMode != mode) {
                int light = lighten(accent);
                shader = new LinearGradient(0, 0, w, 0, new int[]{accent, light, accent}, null, Shader.TileMode.CLAMP);
                shaderMode = mode;
            }
            float mid = h / 2f, amp = h * 0.46f;
            int n = Sample.PEAKS;
            for (int i = 0; i < n; i++) {
                float x = (i + 0.5f) * w / n;
                pts[i * 4] = x; pts[i * 4 + 1] = mid - sm.peakMax[i] * amp;
                pts[i * 4 + 2] = x; pts[i * 4 + 3] = mid - sm.peakMin[i] * amp - 1f;
            }
            p.setShader(shader);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, w / n * 0.8f));
            c.drawLines(pts, p);
            p.setShader(null);
            p.setColor(0x55FFFFFF);
            p.setStrokeWidth(1f);
            c.drawLine(0, mid, w, mid, p);
        }

        if (mode == Slot.SLICER) drawGrid(c, w, h, s.div <= 0 ? 8 : s.div, 0x33FFFFFF);

        // per-mode touch overlays
        p.setStyle(Paint.Style.STROKE);
        int count = 0;
        float minX = 1f, maxX = 0f;
        for (int i = 0; i < MAXP; i++) {
            if (!down[i]) continue;
            count++;
            float x = fx[i] * w, y = fy[i] * h;
            minX = Math.min(minX, fx[i]); maxX = Math.max(maxX, fx[i]);
            p.setColor(0xCCFFFFFF);
            switch (mode) {
                case Slot.TAPE:
                    p.setStrokeWidth(3f);
                    c.drawLine(sx[i] * w, y, x, y, p);
                    break;
                case Slot.BOW:
                    p.setStrokeWidth(2f);
                    float half = w * 0.012f * (1f + (1f - fy[i]) * 3f);
                    c.drawLine(x - half, 0, x - half, h, p);
                    c.drawLine(x + half, 0, x + half, h, p);
                    break;
                case Slot.KEYBOARD:
                    break;
                default:
                    p.setStrokeWidth(2f);
                    c.drawLine(x, 0, x, h, p);
                    break;
            }
        }
        if (mode == Slot.LOOPER && count >= 1) {
            p.setStyle(Paint.Style.FILL);
            p.setColor((accent & 0x00FFFFFF) | 0x33000000);
            float a = count >= 2 ? minX : minX, b = count >= 2 ? maxX : Math.min(1f, minX + 1f / 32f);
            c.drawRect(a * w, 0, b * w, h, p);
        }
        // finger rings
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < MAXP; i++) if (down[i]) c.drawCircle(fx[i] * w, fy[i] * h, Math.min(w, h) * 0.07f, p);

        // playheads of live voices
        p.setStrokeWidth(2f);
        p.setColor(0xDDFFFFFF);
        for (int i = 0; i < Slot.VOICES; i++) {
            float ph = s.playheads[i];
            if (ph >= 0f && ph <= 1f && mode != Slot.TAPE) c.drawLine(ph * w, 0, ph * w, h, p);
        }

        // gesture recorder progress bar along the top edge
        GestureRecorder g = s.gestures;
        int st = g.state;
        if (st != GestureRecorder.IDLE) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(st == GestureRecorder.PLAYING ? 0xFF52D273 : (st == GestureRecorder.RECORDING ? 0xFFFF3B4E : 0xFFFFC107));
            float prog = st == GestureRecorder.ARMED ? 1f : g.progress;
            c.drawRect(0, 0, w * prog, 6f, p);
        }
        postInvalidateOnAnimation();
    }

    private void drawGrid(Canvas c, float w, float h, int n, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(color);
        for (int i = 1; i < n; i++) c.drawLine(i * w / n, 0, i * w / n, h, p);
    }

    private void drawKeys(Canvas c, float w, float h) {
        int n = Slot.KEYS;
        float kw = w / n;
        boolean[] black = new boolean[12];
        black[1] = black[3] = black[6] = black[8] = black[10] = true;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            int semi = ((i - n / 2) % 12 + 12) % 12;
            p.setColor(black[semi] ? 0x22000000 : 0x14FFFFFF);
            c.drawRect(i * kw + 1, 0, (i + 1) * kw - 1, h, p);
        }
        for (int i = 0; i < MAXP; i++) {
            if (!down[i]) continue;
            int key = Math.min(n - 1, (int) (fx[i] * n));
            p.setColor(0x558A7CFF);
            c.drawRect(key * kw, 0, (key + 1) * kw, h, p);
        }
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 70), g = Math.min(255, ((c >> 8) & 255) + 70), b = Math.min(255, (c & 255) + 70);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
