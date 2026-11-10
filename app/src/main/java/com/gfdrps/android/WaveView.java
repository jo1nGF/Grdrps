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
    private final float[] loopXs = new float[MAXP];
    private final boolean[] down = new boolean[MAXP];
    private final float[] fx = new float[MAXP], fy = new float[MAXP], sx = new float[MAXP];

    public WaveView(Context c, Engine e) {
        super(c);
        engine = e;
        setBackgroundColor(Theme.WAVE_BG);
    }

    // ---- zoom (Slicer / Looper / Bow / Arp positions, and region editing in Keyboard)
    private float zoom = 1f, viewStart = 0f;
    private final float[] vMin = new float[Sample.PEAKS], vMax = new float[Sample.PEAKS];
    private int panPointer = -1;
    private float panGrabX, panGrabStart;

    private boolean zoomable(int mode) {
        return mode == Slot.SLICER || mode == Slot.LOOPER || mode == Slot.BOW || mode == Slot.ARP || (mode == Slot.KEYBOARD && editSlices);
    }
    private float zEff() { return zoomable(engine.slots[slotIndex].mode) ? zoom : 1f; }
    private float vsEff() { return zEff() > 1f ? viewStart : 0f; }
    /** Screen x (0..1) to position in the sample (0..1). */
    private float toSample(float xn) { return vsEff() + xn / zEff(); }
    /** Sample position (0..1) to screen x in pixels. */
    private float vx(float pos, float w) { return (pos - vsEff()) * zEff() * w; }

    public float zoom() { return zoom; }
    public void cycleZoom() {
        float centre = viewStart + 0.5f / zoom;
        zoom = zoom >= 16f ? 1f : zoom * 2f;
        viewStart = zoom == 1f ? 0f : Math.max(0f, Math.min(1f - 1f / zoom, centre - 0.5f / zoom));
        invalidate();
    }
    /** Sets the zoom continuously (1..32), keeping the centre of the view where it is. */
    public void setZoom(float z) {
        z = Math.max(1f, Math.min(32f, z));
        float centre = viewStart + 0.5f / zoom;
        zoom = z;
        viewStart = z <= 1.001f ? 0f : Math.max(0f, Math.min(1f - 1f / zoom, centre - 0.5f / zoom));
        if (z <= 1.001f) zoom = 1f;
        invalidate();
    }
    public void resetZoom() { zoom = 1f; viewStart = 0f; invalidate(); }

    // ---- manual slice editing (Slicer mode)
    private boolean editSlices;
    private float[] work;
    private int grab = -1;
    private boolean grabbedExisting, moved;
    private float downPx;

    public void setEditSlices(boolean e) { editSlices = e; if (!e) grab = -1; invalidate(); }
    public boolean isEditSlices() { return editSlices; }

    private float[] baseMarkers() {
        Slot s = engine.slots[slotIndex];
        float[] m = s.markers;
        return m != null ? m.clone() : s.sliceBoundaries();   // first edit starts from the current even grid
    }

    private boolean editTouch(MotionEvent e) {
        float w = Math.max(1, getWidth());
        Slot sl = engine.slots[slotIndex];
        int a = e.getActionMasked();
        float x = clamp(toSample(clamp(e.getX() / w)));
        if (a == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            engine.pushUndo(slotIndex);
            float[] cur = baseMarkers();
            int near = -1; float best = 0.03f / zEff();
            for (int i = 0; i < cur.length; i++) if (Math.abs(cur[i] - x) < best) { best = Math.abs(cur[i] - x); near = i; }
            if (near >= 0) { work = cur; grab = near; grabbedExisting = true; }
            else {
                float[] n = new float[cur.length + 1];
                int at = 0;
                while (at < cur.length && cur[at] < x) at++;
                System.arraycopy(cur, 0, n, 0, at);
                n[at] = snap(sl, x);
                System.arraycopy(cur, at, n, at + 1, cur.length - at);
                work = n; grab = at; grabbedExisting = false;
                engine.setMarkers(slotIndex, work.clone());
            }
            downPx = e.getX(); moved = false;
        } else if (a == MotionEvent.ACTION_MOVE && grab >= 0 && work != null) {
            if (Math.abs(e.getX() - downPx) > 14) moved = true;
            // (positions above are already mapped through the zoom)
            if (moved || !grabbedExisting) {
                float lo = grab > 0 ? work[grab - 1] + 0.006f : 0.004f;
                float hi = grab < work.length - 1 ? work[grab + 1] - 0.006f : 0.996f;
                work[grab] = Math.max(lo, Math.min(hi, snap(sl, x)));
                engine.setMarkers(slotIndex, work.clone());
            }
        } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && grab >= 0 && work != null) {
            if (a == MotionEvent.ACTION_UP && grabbedExisting && !moved) {   // a plain tap on a marker removes it
                float[] n = new float[work.length - 1];
                System.arraycopy(work, 0, n, 0, grab);
                System.arraycopy(work, grab + 1, n, grab, work.length - grab - 1);
                engine.setMarkers(slotIndex, n);
            }
            grab = -1; work = null;
        }
        invalidate();
        return true;
    }

    /** With a quantize value selected (4/8/16/32) markers snap to that grid. */
    private static float snap(Slot s, float x) {
        int q = s.div;
        return q > 0 ? Math.round(x * q) / (float) q : x;
    }

    /** While zoomed, a thin bar along the top edge scrolls the view (the rest of the surface plays as usual). */
    private boolean panTouch(MotionEvent e) {
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            if (e.getY(idx) < h * 0.09f && panPointer < 0) {
                panPointer = e.getPointerId(idx);
                panGrabX = e.getX(idx); panGrabStart = viewStart;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return false;
        }
        if (panPointer < 0) return false;
        int pi = e.findPointerIndex(panPointer);
        if (act == MotionEvent.ACTION_MOVE && pi >= 0) {
            float dx = (e.getX(pi) - panGrabX) / w;                       // full bar width = whole sample
            viewStart = Math.max(0f, Math.min(1f - 1f / zoom, panGrabStart + dx));
            invalidate();
            return true;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL || (act == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.getActionIndex()) == panPointer)) {
            panPointer = -1;
            return true;
        }
        return false;
    }

    // ---- Keyboard / trim region: two handles for start and end of the playable part
    private int regGrab = -1;
    private boolean regionTouch(MotionEvent e) {
        float w = Math.max(1, getWidth());
        Slot sl = engine.slots[slotIndex];
        int a = e.getActionMasked();
        float x = clamp(toSample(clamp(e.getX() / w)));
        if (a == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            engine.pushUndo(slotIndex);
            regGrab = Math.abs(x - sl.regStart) <= Math.abs(x - sl.regEnd) ? 0 : 1;
        } else if (a == MotionEvent.ACTION_MOVE && regGrab >= 0) {
            if (regGrab == 0) engine.setRegion(slotIndex, Math.min(x, sl.regEnd - 0.004f), sl.regEnd);
            else engine.setRegion(slotIndex, sl.regStart, Math.max(x, sl.regStart + 0.004f));
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) regGrab = -1;
        invalidate();
        return true;
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
        int curMode = engine.slots[slotIndex].mode;
        if (zEff() > 1f && panTouch(e)) return true;
        if (editSlices && curMode == Slot.SLICER) return editTouch(e);
        if (editSlices && curMode == Slot.KEYBOARD) return regionTouch(e);
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        int act = e.getActionMasked();
        final boolean mapX = curMode == Slot.SLICER || curMode == Slot.LOOPER || curMode == Slot.BOW || curMode == Slot.ARP;
        if (act == MotionEvent.ACTION_DOWN && android.os.Build.VERSION.SDK_INT >= 26) requestUnbufferedDispatch(e);   // touch samples delivered immediately, not batched to the frame
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                if (id < MAXP) {
                    down[id] = true;
                    fx[id] = clamp(e.getX(idx) / w); fy[id] = clamp(e.getY(idx) / h); sx[id] = fx[id];
                    engine.touch(slotIndex, 0, id, mapX ? clamp(toSample(fx[id])) : fx[id], fy[id]);
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
                        engine.touch(slotIndex, 1, id, mapX ? clamp(toSample(nx)) : nx, ny);
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

        // waveform
        if (sm != null) {
            if (shader == null || shaderMode != mode) {
                int light = lighten(accent);
                shader = new LinearGradient(0, 0, w, 0, new int[]{accent, light, accent}, null, Shader.TileMode.CLAMP);
                shaderMode = mode;
            }
            float mid = h / 2f, amp = h * 0.46f;
            int n = Sample.PEAKS;
            float[] pmax = sm.peakMax, pmin = sm.peakMin;
            float z = zEff();
            if (z > 1f) {                                         // zoomed: peaks of the visible part only
                float v0 = vsEff(), v1 = v0 + 1f / z;
                int len = sm.length();
                for (int i = 0; i < n; i++) {
                    int a0 = (int) ((v0 + (v1 - v0) * i / n) * len), a1 = (int) ((v0 + (v1 - v0) * (i + 1) / n) * len);
                    a0 = Math.max(0, Math.min(len - 1, a0)); a1 = Math.max(a0 + 1, Math.min(len, a1));
                    int stp = Math.max(1, (a1 - a0) / 24);
                    float lo = 0f, hi = 0f;
                    for (int k = a0; k < a1; k += stp) { float d = sm.data[k]; if (d > hi) hi = d; if (d < lo) lo = d; }
                    vMax[i] = hi; vMin[i] = lo;
                }
                pmax = vMax; pmin = vMin;
            }
            for (int i = 0; i < n; i++) {
                float x = (i + 0.5f) * w / n;
                pts[i * 4] = x; pts[i * 4 + 1] = mid - pmax[i] * amp;
                pts[i * 4 + 2] = x; pts[i * 4 + 3] = mid - pmin[i] * amp - 1f;
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

        if (mode == Slot.SLICER) drawSlices(c, w, h, s);
        if (mode == Slot.ARP) drawSlices(c, w, h, s);
        if (mode == Slot.ARP || mode == Slot.SLICER) {
            if (s.arpFlash > 0.02f && s.arpSlice >= 0) {
                float[] b = s.sliceBoundaries();
                int i = Math.max(0, Math.min(b.length, s.arpSlice));       // the slice count may have just changed
                float x0 = vx(i == 0 ? 0f : b[i - 1], w), x1 = vx(i >= b.length ? 1f : b[i], w);
                p.setStyle(Paint.Style.FILL);
                p.setColor(((int) (Math.min(1f, s.arpFlash) * 150) << 24) | 0xFFFFFF);       // the played slice lights up white
                c.drawRect(x0, 0, x1, h, p);
            }
        }

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
        if (mode == Slot.LOOPER && s.uiN >= 1) {
            // fingers pair up in the order they went down (1+2, 3+4 ...): one loop region per pair
            p.setStyle(Paint.Style.FILL);
            float[] ord = loopXs; int k = 0;
            int un = Math.min(s.uiN, Math.min(ord.length, s.uiX.length));
            for (int i = 0; i < un; i++) ord[k++] = vx(s.uiX[i], w) / w;     // same order the engine pairs them in
            for (int pr = 0; pr * 2 < k; pr++) {
                float a = ord[pr * 2], b = pr * 2 + 1 < k ? ord[pr * 2 + 1] : Math.min(1f, a + 1f / 32f);
                p.setColor((accent & 0x00FFFFFF) | (pr % 2 == 0 ? 0x44000000 : 0x2E000000));
                c.drawRect(Math.min(a, b) * w, 0, Math.max(a, b) * w, h, p);
            }
        }
        // finger rings
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < MAXP; i++) if (down[i]) c.drawCircle(fx[i] * w, fy[i] * h, Math.min(w, h) * 0.07f, p);

        // replayed gestures: the fingers the recorder is "holding" show up as rings too (live fingers are drawn above)
        {
            int un = Math.min(s.uiN, s.uiId.length);
            float rad = Math.min(w, h) * 0.07f;
            for (int i = 0; i < un; i++) {
                if (s.uiId[i] < com.gfdrps.core.GestureRecorder.ID_OFFSET) continue;
                float gx = vx(s.uiX[i], w), gy = s.uiY[i] * h;
                if (gx < -rad || gx > w + rad) continue;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f); p.setColor(0xFFFF4D5E);
                c.drawCircle(gx, gy, rad, p);
                p.setStrokeWidth(2f); p.setColor(0x99FF4D5E);
                c.drawLine(gx, 0, gx, h, p);
                p.setStyle(Paint.Style.FILL); p.setColor(0x55FF4D5E);
                c.drawCircle(gx, gy, rad * 0.35f, p);
            }
        }
        // playheads of live voices
        p.setStrokeWidth(2f);
        p.setColor(0xDDFFFFFF);
        for (int i = 0; i < Slot.VOICES; i++) {
            float ph = s.playheads[i];
            if (ph >= 0f && ph <= 1f && mode != Slot.TAPE) { float px = vx(ph, w); if (px >= 0 && px <= w) c.drawLine(px, 0, px, h, p); }
        }

        // Keyboard region handles (start / end) and the zoom scroll bar
        if (mode == Slot.KEYBOARD && (editSlices || s.regStart > 0f || s.regEnd < 1f)) {
            float xa = vx(s.regStart, w), xb = vx(s.regEnd, w);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x88000000);
            if (xa > 0) c.drawRect(0, 0, Math.min(w, xa), h, p);
            if (xb < w) c.drawRect(Math.max(0, xb), 0, w, h, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(editSlices ? 4f : 2f);
            p.setColor(0xFFFFD23F);
            c.drawLine(xa, 0, xa, h, p);
            c.drawLine(xb, 0, xb, h, p);
            if (editSlices) {
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(Math.min(w, h) * 0.06f);
                c.drawText("REGIÃO: arraste início e fim · use o zoom para precisão", w * 0.02f, h * 0.96f, p);
            }
        }
        if (zEff() > 1f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x44FFFFFF);
            c.drawRect(0, 0, w, h * 0.04f, p);
            p.setColor(0xCCFFD23F);
            c.drawRect(vsEff() * w, 0, (vsEff() + 1f / zEff()) * w, h * 0.04f, p);
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

    private void drawSlices(Canvas c, float w, float h, Slot s) {
        float[] b = s.sliceBoundaries();
        boolean manual = s.markers != null;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(manual || editSlices ? 3f : 1.5f);
        p.setColor(editSlices ? 0xFFFFD23F : (manual ? 0xAAFFFFFF : 0x33FFFFFF));
        for (float v : b) { float px = vx(v, w); if (px >= 0 && px <= w) c.drawLine(px, 0, px, h, p); }
        if (editSlices) {
            p.setStyle(Paint.Style.FILL);
            float t = Math.min(w, h) * 0.035f;
            for (float v : b) {
                float px = vx(v, w);
                if (px < 0 || px > w) continue;
                path.reset();
                path.moveTo(px - t, 0); path.lineTo(px + t, 0); path.lineTo(px, t * 1.6f); path.close();
                c.drawPath(path, p);
            }
            p.setColor(0xCCFFD23F);
            p.setTextSize(Math.min(w, h) * 0.06f);
            c.drawText("EDITAR FATIAS: toque = novo marcador · arraste = mover · toque no marcador = apagar", w * 0.02f, h * 0.96f, p);
        }
    }

    private final android.graphics.Path path = new android.graphics.Path();

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
