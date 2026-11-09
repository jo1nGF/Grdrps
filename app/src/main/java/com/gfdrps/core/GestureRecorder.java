package com.gfdrps.core;

/**
 * Records finger events (not audio) for up to 128 bars and replays them in a loop,
 * locked to the project beat clock. Replayed events go through whatever play mode the slot has *now*.
 */
public final class GestureRecorder {
    public static final int IDLE = 0, ARMED = 1, RECORDING = 2, PLAYING = 3;
    public static final int MAX_BARS = 128, MAX_EVENTS = 40000, ID_OFFSET = 1000;

    public interface Emitter { void emit(int type, int id, float x, float y); }

    public volatile int state = IDLE;
    public volatile int bars = 4;
    /** 0..1 progress through the loop, for the UI. */
    public volatile float progress;

    private double[] phase = new double[1024];
    private int[] type = new int[1024], id = new int[1024];
    private float[] x = new float[1024], y = new float[1024];
    private int count;
    private double startBeat, lastPhase;
    private final boolean[] held = new boolean[16], fxHeld = new boolean[32];
    private boolean waitTouch;

    public int eventCount() { return count; }
    private double loopBeats() { return bars * 4.0; }

    /** Arms recording: it starts by itself on the first touch (waveform or effect pad). */
    public void arm(double beatNow, int nBars) {
        bars = Math.max(1, Math.min(MAX_BARS, nBars));
        startBeat = beatNow;
        waitTouch = true;
        count = 0;
        java.util.Arrays.fill(held, false);
        java.util.Arrays.fill(fxHeld, false);
        state = ARMED;
    }

    /** Loads a saved take and starts replaying it (locked to the bar clock). */
    public void load(int nBars, double[] ph, int[] ty, int[] ids, float[] px, float[] py) {
        int n = Math.min(ph.length, Math.min(ty.length, Math.min(ids.length, Math.min(px.length, py.length))));
        n = Math.min(n, MAX_EVENTS);
        bars = Math.max(1, Math.min(MAX_BARS, nBars));
        int cap = Math.max(1024, n);
        phase = java.util.Arrays.copyOf(ph, cap); type = java.util.Arrays.copyOf(ty, cap); id = java.util.Arrays.copyOf(ids, cap);
        x = java.util.Arrays.copyOf(px, cap); y = java.util.Arrays.copyOf(py, cap);
        count = n;
        startBeat = 0; lastPhase = 0; waitTouch = false;
        java.util.Arrays.fill(held, false); java.util.Arrays.fill(fxHeld, false);
        state = n > 0 ? PLAYING : IDLE;
    }

    /** Undo support: a copy of the recorded take. */
    public static final class Snap {
        final int bars, count, state; final double[] ph; final int[] ty, ids; final float[] px, py;
        Snap(int b, int c, int st, double[] a, int[] t, int[] i, float[] x, float[] y) { bars = b; count = c; state = st; ph = a; ty = t; ids = i; px = x; py = y; }
    }
    public Snap snap() {
        int st = state == PLAYING ? PLAYING : IDLE;
        return new Snap(bars, count, st, java.util.Arrays.copyOf(phase, count), java.util.Arrays.copyOf(type, count),
                java.util.Arrays.copyOf(id, count), java.util.Arrays.copyOf(x, count), java.util.Arrays.copyOf(y, count));
    }
    public void restore(Snap s, Emitter em) {
        releaseHeld(em);
        if (s.state == PLAYING && s.count > 0) load(s.bars, s.ph, s.ty, s.ids, s.px, s.py);
        else { count = 0; state = IDLE; }
    }

    // ---- editing a recorded take (all phases are in beats from the start of the loop)
    private void sortEvents() {
        Integer[] o = new Integer[count];
        for (int i = 0; i < count; i++) o[i] = i;
        java.util.Arrays.sort(o, (a, b) -> Double.compare(phase[a], phase[b]));
        double[] p2 = new double[phase.length]; int[] t2 = new int[type.length], i2 = new int[id.length]; float[] x2 = new float[x.length], y2 = new float[y.length];
        for (int i = 0; i < count; i++) { int k = o[i]; p2[i] = phase[k]; t2[i] = type[k]; i2[i] = id[k]; x2[i] = x[k]; y2[i] = y[k]; }
        phase = p2; type = t2; id = i2; x = x2; y = y2;
    }

    /** Snaps every event to a grid of `gridBeats` (0.25 = 1/16 note). Moves keep their order relative to their finger's down/up. */
    public void quantize(double gridBeats, Emitter em) {
        if (state != PLAYING || count == 0) return;
        releaseHeld(em);
        double len = bars * 4.0;
        for (int i = 0; i < count; i++) {
            double q = Math.round(phase[i] / gridBeats) * gridBeats;
            phase[i] = Math.max(0.0, Math.min(len - 1e-6, q));
        }
        sortEvents();
    }

    /** Moves the whole take by deltaBeats (wraps around the loop). */
    public void nudge(double deltaBeats, Emitter em) {
        if (state != PLAYING || count == 0) return;
        releaseHeld(em);
        double len = bars * 4.0;
        for (int i = 0; i < count; i++) { double p = (phase[i] + deltaBeats) % len; if (p < 0) p += len; phase[i] = p; }
        sortEvents();
    }

    /** Removes the events of the effect pad (fx = true) or of the waveform fingers (fx = false). */
    public int removeKind(boolean fx, Emitter em) {
        if (state != PLAYING) return 0;
        releaseHeld(em);
        int w = 0;
        for (int i = 0; i < count; i++) {
            boolean isFx = type[i] >= 3;
            if (isFx == fx) continue;
            phase[w] = phase[i]; type[w] = type[i]; id[w] = id[i]; x[w] = x[i]; y[w] = y[i]; w++;
        }
        int removed = count - w;
        count = w;
        if (count == 0) state = IDLE;
        return removed;
    }

    // read-only view of the take, for saving
    public int bars() { return bars; }
    public double phaseAt(int i) { return phase[i]; }
    public int typeAt(int i) { return type[i]; }
    public int idAt(int i) { return id[i]; }
    public float xAt(int i) { return x[i]; }
    public float yAt(int i) { return y[i]; }

    public void clear(Emitter em) {
        if (state == PLAYING) releaseHeld(em);
        state = IDLE;
        count = 0;
        progress = 0f;
    }

    public void add(int t, int touchId, float px, float py, double beat) {
        if (state == ARMED && waitTouch && t != 2 && t != 5) {      // first touch: the take starts right now
            startBeat = beat; waitTouch = false; state = RECORDING; lastPhase = 0;
        }
        if (state != RECORDING || count >= MAX_EVENTS) return;
        if (count == phase.length) grow();
        phase[count] = Math.max(0, beat - startBeat);
        type[count] = t; id[count] = touchId; x[count] = px; y[count] = py;
        count++;
        if (t >= 3) { if (touchId >= 0 && touchId < fxHeld.length) fxHeld[touchId] = t != 5; }
        else if (touchId >= 0 && touchId < held.length) held[touchId] = t != 2;
    }

    private void grow() {
        int n = phase.length * 2;
        phase = java.util.Arrays.copyOf(phase, n);
        type = java.util.Arrays.copyOf(type, n);
        id = java.util.Arrays.copyOf(id, n);
        x = java.util.Arrays.copyOf(x, n);
        y = java.util.Arrays.copyOf(y, n);
    }

    private void releaseHeld(Emitter em) {
        for (int i = 0; i < held.length; i++) if (held[i]) { em.emit(2, i + ID_OFFSET, 0f, 0f); held[i] = false; }
        for (int i = 0; i < fxHeld.length; i++) if (fxHeld[i]) { em.emit(5, i + ID_OFFSET, 0f, 0f); fxHeld[i] = false; }
    }

    /** Called once per audio block with the beat at block start. */
    public void update(double beatNow, boolean transportPlaying, Emitter em) {
        int st = state;
        if (st == IDLE) return;
        double len = loopBeats();
        if (st == ARMED) return;           // waiting for the first touch
        if (st == RECORDING) {
            progress = (float) Math.min(1.0, (beatNow - startBeat) / len);
            if (beatNow - startBeat >= len) {
                // close any finger still down so the loop has no stuck notes
                for (int i = 0; i < held.length; i++) if (held[i]) add(2, i, 0f, 0f, startBeat + len - 1e-6);
                for (int i = 0; i < fxHeld.length; i++) if (fxHeld[i]) add(5, i, 0f, 0f, startBeat + len - 1e-6);
                java.util.Arrays.fill(held, false);
                java.util.Arrays.fill(fxHeld, false);
                state = count > 0 ? PLAYING : IDLE;
                lastPhase = 0;
            }
            return;
        }
        // PLAYING
        if (!transportPlaying) { releaseHeld(em); return; }
        double p1 = (beatNow - startBeat) % len;
        if (p1 < 0) p1 += len;
        progress = (float) (p1 / len);
        if (p1 >= lastPhase) emitRange(lastPhase, p1, em);
        else { emitRange(lastPhase, len + 1, em); releaseHeldOnWrap(em); emitRange(-1, p1, em); }
        lastPhase = p1;
    }

    private void releaseHeldOnWrap(Emitter em) {
        // events of the loop end already closed notes; this only protects against odd edge cases
    }

    private void emitRange(double a, double b, Emitter em) {
        for (int i = 0; i < count; i++) {
            double p = phase[i];
            if (p >= a && p < b) {
                int tid = id[i];
                if (type[i] >= 3) { if (tid >= 0 && tid < fxHeld.length) fxHeld[tid] = type[i] != 5; }
                else if (tid >= 0 && tid < held.length) held[tid] = type[i] != 2;
                em.emit(type[i], tid + ID_OFFSET, x[i], y[i]);
            }
        }
    }
}
