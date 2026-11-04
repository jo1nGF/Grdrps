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
    private final boolean[] held = new boolean[16];

    public int eventCount() { return count; }
    private double loopBeats() { return bars * 4.0; }

    /** Arms recording to start on the next bar line. */
    public void arm(double beatNow, int nBars) {
        bars = Math.max(1, Math.min(MAX_BARS, nBars));
        startBeat = Math.ceil(beatNow / 4.0) * 4.0;
        count = 0;
        java.util.Arrays.fill(held, false);
        state = ARMED;
    }

    public void clear(Emitter em) {
        if (state == PLAYING) releaseHeld(em);
        state = IDLE;
        count = 0;
        progress = 0f;
    }

    public void add(int t, int touchId, float px, float py, double beat) {
        if (state != RECORDING || count >= MAX_EVENTS) return;
        if (count == phase.length) grow();
        phase[count] = Math.max(0, beat - startBeat);
        type[count] = t; id[count] = touchId; x[count] = px; y[count] = py;
        count++;
        if (touchId >= 0 && touchId < held.length) held[touchId] = t != 2;
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
    }

    /** Called once per audio block with the beat at block start. */
    public void update(double beatNow, boolean transportPlaying, Emitter em) {
        int st = state;
        if (st == IDLE) return;
        double len = loopBeats();
        if (st == ARMED) {
            if (beatNow >= startBeat) state = RECORDING; else return;
            st = RECORDING;
        }
        if (st == RECORDING) {
            progress = (float) Math.min(1.0, (beatNow - startBeat) / len);
            if (beatNow - startBeat >= len) {
                // close any finger still down so the loop has no stuck notes
                for (int i = 0; i < held.length; i++) if (held[i]) add(2, i, 0f, 0f, startBeat + len - 1e-6);
                java.util.Arrays.fill(held, false);
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
                if (tid >= 0 && tid < held.length) held[tid] = type[i] != 2;
                em.emit(type[i], tid + ID_OFFSET, x[i], y[i]);
            }
        }
    }
}
