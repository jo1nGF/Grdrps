package com.gfdrps.core;

/** One of the 6 sample slots: sample, 8 play modes, 8 voices, 5 effects, gesture recorder. Audio-thread only. */
public final class Slot {
    public static final int SLICER = 0, LOOPER = 1, BOW = 2, TAPE = 3, SCRATCH = 4, ARP = 5, KEYBOARD = 6, LOOPPLAYER = 7;
    public static final String[] MODE_NAMES = {"Slicer", "Looper", "Bow", "Tape", "Scratch", "Arpeggiator", "Keyboard", "Loop Player"};
    public static final int VOICES = 12, POLY = 8, KEYS = 24, MAX_BLOCK = 512;
    public static final int[] DIVS = {0, 4, 8, 16, 32};

    final int rate;
    public volatile Sample sample;

    // user parameters (written from UI thread, read on audio thread: plain volatiles)
    public volatile int mode = SLICER;
    public volatile float volume = 0.8f, attack = 0f, decay = 0.25f, sustain = 1f, release = 0.15f, pan = 0.5f;
    public volatile int div = 0;            // slices / arp steps per bar (0 = default 8); looper marker quantize (0 = free)
    public volatile int transpose = 0;      // semitones
    public volatile boolean infinite = false, gate = false, legato = false, arpRandom = false, muted = false;
    public volatile double sampleBeats = 8;  // length of the sample in beats (loop player sync)
    public volatile boolean reversed = false;
    /** Manual slice boundaries (0..1, sorted, interior only). null = even grid from {@link #div}. */
    public volatile float[] markers = null;

    public final Fx.Effect[] fx = new Fx.Effect[Fx.COUNT];
    public final GestureRecorder gestures = new GestureRecorder();
    final Voice[] voices = new Voice[VOICES];
    private final float[] mono = new float[MAX_BLOCK], bl = new float[MAX_BLOCK], br = new float[MAX_BLOCK];

    // touch bookkeeping
    private final int[] tIds = new int[16];
    private final float[] tX = new float[16], tY = new float[16];
    private final Voice[] tVoice = new Voice[16];
    private int tCount;
    // arpeggiator
    /** 0 up (left to right), 1 down, 2 up-down, 3 random, 4 order the fingers were put down. */
    public volatile int arpDir = 0;
    /** Notes per bar: 4, 8, 16 or 32 (1/4 .. 1/32 notes). */
    public volatile int arpRate = 16;
    /** Slice played by the last arp step and a 1..0 flash for the UI. */
    public volatile int arpSlice = -1;
    public volatile float arpFlash;
    private double nextStepBeat = -1;
    private boolean arpImmediate;
    private int arpCounter, arpLast = -1;
    private final int[] arpTmp = new int[16], arpOrd = new int[16];
    private final float[] arpVel = new float[16];
    private final double[] bounds = new double[2];
    private Voice looperV;
    private float curGL, curGR;
    // visual feedback (read by UI)
    public final float[] playheads = new float[VOICES];
    public volatile int activeVoices;
    public volatile float level;

    public Slot(int rate) {
        this.rate = rate;
        for (int i = 0; i < VOICES; i++) { voices[i] = new Voice(); voices[i].engineRate = rate; }
        for (int i = 0; i < Fx.COUNT; i++) fx[i] = Fx.create(i, rate);
    }

    public void setSample(Sample s, double bpm) {
        stopAll();
        sample = s;
        markers = null;
        reversed = false;
        if (s != null) {
            double dur = s.length() / (double) s.sampleRate;
            sampleBeats = Math.max(1, Math.round(dur * bpm / 60.0));
        }
    }

    /** Plays the sample backwards from now on (toggle: calling it again restores it). */
    public void reverseSample() {
        Sample s = sample;
        if (s == null) return;
        int n = s.length();
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = s.data[n - 1 - i];
        stopAll();
        sample = new Sample(d, s.sampleRate, s.name);
        reversed = !reversed;
        float[] m = markers;
        if (m != null) {
            float[] nm = new float[m.length];
            for (int i = 0; i < m.length; i++) nm[i] = 1f - m[m.length - 1 - i];
            markers = nm;
        }
    }

    /** Sorts, clamps and de-duplicates marker positions; returns null when there are none. */
    public static float[] cleanMarkers(float[] in) {
        if (in == null || in.length == 0) return null;
        float[] c = in.clone();
        java.util.Arrays.sort(c);
        float[] out = new float[c.length];
        int n = 0;
        for (float v : c) {
            if (!(v > 0.002f && v < 0.998f)) continue;
            if (n > 0 && v - out[n - 1] < 0.004f) continue;
            out[n++] = v;
            if (n >= 63) break;
        }
        return n == 0 ? null : java.util.Arrays.copyOf(out, n);
    }

    /** Interior slice boundaries currently in use (manual markers or the even grid), for drawing. */
    public float[] sliceBoundaries() {
        float[] m = markers;
        if (m != null) return m;
        int n = sliceCount();
        float[] g = new float[n - 1];
        for (int i = 1; i < n; i++) g[i - 1] = i / (float) n;
        return g;
    }

    /** Fades every voice out quickly (no click) and forgets all fingers, arp steps and the looper. */
    public void fadeAll() {
        for (Voice v : voices) if (v.active) v.fastRelease();
        tCount = 0;
        for (int i = 0; i < tVoice.length; i++) tVoice[i] = null;
        nextStepBeat = -1;
        looperV = null;
    }

    public void stopAll() {
        for (Voice v : voices) v.kill();
        tCount = 0;
        for (int i = 0; i < tVoice.length; i++) tVoice[i] = null;
    }

    private double pitchRatio() { return Math.pow(2.0, transpose / 12.0); }
    private int sliceCount() { int d = div; return d <= 0 ? 8 : d; }

    /** Index of the slice under horizontal position x (manual markers or even grid). */
    public int sliceIndex(float x) {
        float[] mk = markers;
        if (mk != null) {
            int i = 0;
            while (i < mk.length && x >= mk[i]) i++;
            return i;
        }
        int n = sliceCount();
        return Math.min(n - 1, (int) (x * n));
    }

    /** Start/end (0..1) of slice idx. */
    private void sliceBounds(int idx, double[] out) {
        float[] mk = markers;
        if (mk != null) {
            out[0] = idx == 0 ? 0.0 : mk[idx - 1];
            out[1] = idx >= mk.length ? 1.0 : mk[idx];
        } else {
            int n = sliceCount();
            out[0] = (double) idx / n;
            out[1] = (double) (idx + 1) / n;
        }
    }

    private int find(int id) {
        for (int i = 0; i < tCount; i++) if (tIds[i] == id) return i;
        return -1;
    }

    /** Gives a free voice. Beyond POLY live voices the oldest one is faded out (no hard cut = no click). */
    private Voice alloc(long frame) {
        int live = 0;
        Voice free = null, oldestLive = null;
        for (Voice v : voices) {
            if (!v.active) { if (free == null) free = v; continue; }
            if (!v.releasing) {
                live++;
                if (oldestLive == null || v.startedAt < oldestLive.startedAt) oldestLive = v;
            }
        }
        if (live >= POLY && oldestLive != null) oldestLive.fastRelease();
        if (free != null) return free;
        Voice pick = null;
        for (Voice v : voices) {
            if (pick == null || (v.releasing && !pick.releasing) || (v.releasing == pick.releasing && v.startedAt < pick.startedAt)) pick = v;
        }
        pick.kill();
        return pick;
    }

    private Voice prepare(int kind, long frame) {
        Sample s = sample;
        Voice v = alloc(frame);
        v.sm = s;
        v.kind = kind;
        v.ratio = s.sampleRate / (double) rate;
        v.engineRate = rate;
        v.setEnv(attack, decay, sustain, release);
        v.speed = pitchRatio();
        v.loop = false;
        v.vel = 1f;
        return v;
    }

    // ---------------------------------------------------------------- touch handling

    public void handle(int type, int id, float x, float y, long frame, double beat) {
        Sample s = sample;
        if (s == null || s.length() < 2) return;
        x = clamp01(x); y = clamp01(y);
        if (type == 0) down(id, x, y, frame, beat, s);
        else if (type == 1) move(id, x, y, s);
        else up(id);
    }

    private void down(int id, float x, float y, long frame, double beat, Sample s) {
        int k = find(id);
        if (k >= 0) { remove(k); }
        if (tCount >= tIds.length) return;
        k = tCount++;
        tIds[k] = id; tX[k] = x; tY[k] = y; tVoice[k] = null;
        int len = s.length();
        float vel = 0.25f + 0.75f * (1f - y);
        switch (mode) {
            case SLICER: {
                sliceBounds(sliceIndex(x), bounds);
                Voice v = prepare(Voice.PLAY, frame);
                v.start = bounds[0] * len;
                v.end = bounds[1] * len;
                v.pos = v.start;
                v.loop = infinite;
                v.vel = vel;
                v.trigger(id, frame);
                tVoice[k] = v;
                break;
            }
            case LOOPER:
                updateLooper(frame, s);
                break;
            case BOW: {
                Voice v = prepare(Voice.BOW, frame);
                v.center = x * (len - 1);
                v.grainLen = (float) ((0.03 + (1.0 - y) * 0.17) * rate);
                v.gPos[0] = v.gPos[1] = v.center;
                v.gPh[0] = 0f; v.gPh[1] = 0.5f;
                v.vel = 0.7f;
                v.trigger(id, frame);
                tVoice[k] = v;
                break;
            }
            case TAPE: {
                Voice v = prepare(Voice.TAPE, frame);
                v.pos = x * (len - 1);
                v.center = x;           // x where the finger went down
                v.speed = pitchRatio();
                v.baseSpeed = v.speed;
                v.vel = 0.9f;
                v.trigger(id, frame);
                tVoice[k] = v;
                break;
            }
            case SCRATCH: {
                Voice v = prepare(Voice.SCRATCH, frame);
                v.pos = x * (len - 1);
                v.center = x;                                   // x where the finger went down
                v.scrBase = v.pos;
                v.scrTarget = v.pos;
                // full screen width = 0.25 s (finger low) .. 2 s (finger high) of audio
                v.scrSpan = (0.25 + 1.75 * (1.0 - y)) * s.sampleRate;
                v.vel = 0.9f;
                v.trigger(id, frame);
                tVoice[k] = v;
                break;
            }
            case ARP:
                if (tCount == 1) { arpCounter = 0; arpLast = -1; arpImmediate = true; }
                break;
            case KEYBOARD: {
                int key = Math.min(KEYS - 1, (int) (x * KEYS));
                Voice v = prepare(Voice.PLAY, frame);
                v.speed = Math.pow(2.0, (key - KEYS / 2 + transpose) / 12.0);
                v.start = 0; v.end = len; v.pos = 0;
                v.loop = infinite;
                v.vel = vel;
                v.trigger(id, frame);
                tVoice[k] = v;
                break;
            }
            case LOOPPLAYER: {
                Voice running = null;
                for (Voice v : voices) if (v.active && v.kind == Voice.PLAY && v.loop && !v.releasing && v.start == 0 && v.end == len && v.touchId == -2) running = v;
                if (running != null && !gate) {
                    running.release();
                } else {
                    Voice v = prepare(Voice.PLAY, frame);
                    v.start = 0; v.end = len; v.loop = true;
                    v.vel = 0.9f;
                    double phase = (beat / sampleBeats) % 1.0;
                    v.pos = phase * len;
                    v.speed = pitchRatio();
                    v.trigger(-2, frame);   // -2 marks a loop-player voice (tempo-synced in render)
                    tVoice[k] = v;
                }
                break;
            }
            default: break;
        }
    }

    private void move(int id, float x, float y, Sample s) {
        int k = find(id);
        if (k < 0) return;
        tX[k] = x; tY[k] = y;
        Voice v = tVoice[k];
        int len = s.length();
        switch (mode) {
            case LOOPER: updateLooper(0, s); break;
            case BOW:
                if (v != null && v.active) {
                    v.center = x * (len - 1);
                    v.grainLen = (float) ((0.03 + (1.0 - y) * 0.17) * rate);
                }
                break;
            case TAPE:
                if (v != null && v.active) { v.speedTgt = v.baseSpeed + (x - v.center) * 6.0; v.glide = 0.003f; }
                break;
            case SCRATCH:
                if (v != null && v.active) {
                    double t = v.scrBase + (x - v.center) * v.scrSpan;
                    v.scrTarget = Math.max(0, Math.min(len - 1, t));
                }
                break;
            case KEYBOARD:
                if (v != null && v.active) {
                    int key = Math.min(KEYS - 1, (int) (x * KEYS));
                    double sp = Math.pow(2.0, (key - KEYS / 2 + transpose) / 12.0);
                    if (legato) { v.speedTgt = sp; v.glide = 0.0015f; }
                }
                break;
            default: break;
        }
    }

    private void up(int id) {
        int k = find(id);
        if (k < 0) return;
        Voice v = tVoice[k];
        remove(k);
        if (mode == LOOPER) { updateLooper(0, sample); return; }
        if (v == null || !v.active || v.touchId != id) return;
        boolean rel = v.kind != Voice.PLAY || mode == KEYBOARD || v.loop && mode != LOOPPLAYER || gate && mode != LOOPPLAYER;
        if (mode == LOOPPLAYER) rel = gate;
        if (rel) v.release(); else v.held = false;
    }

    private void remove(int k) {
        for (int i = k; i < tCount - 1; i++) {
            tIds[i] = tIds[i + 1]; tX[i] = tX[i + 1]; tY[i] = tY[i + 1]; tVoice[i] = tVoice[i + 1];
        }
        tCount--;
        tVoice[tCount] = null;
    }

    /**
     * Looper: the first two fingers set the loop region. Crossing them (second finger left of the first)
     * plays it in reverse. With a quantize value (4/8/16/32) the markers snap to that grid, 0 = free.
     */
    private void updateLooper(long frame, Sample s) {
        if (s == null) return;
        int len = s.length();
        Voice lv = looperV;
        if (tCount == 0) {
            if (lv.active && lv.kind == Voice.PLAY && lv.touchId == -3) lv.release();
            return;
        }
        double a, b;
        boolean reverse = false;
        int q = div;
        if (tCount >= 2) {
            a = Math.min(tX[0], tX[1]);
            b = Math.max(tX[0], tX[1]);
            reverse = tX[1] < tX[0];
        } else {
            a = tX[0];
            b = a + (q > 0 ? 1.0 / q : 1.0 / 32.0);
        }
        if (q > 0) {
            a = Math.round(a * q) / (double) q;
            b = Math.round(b * q) / (double) q;
            if (b - a < 0.5 / q) b = a + 1.0 / q;
        }
        if (b > 1.0) { double w = b - a; b = 1.0; a = Math.max(0.0, b - w); }
        double st = a * len, en = b * len;
        if (en - st < 64) { en = Math.min(len, st + 64); st = Math.max(0, en - 64); }
        double sp = reverse ? -pitchRatio() : pitchRatio();
        if (!(lv != null && lv.active && lv.touchId == -3 && !lv.releasing)) {
            lv = prepare(Voice.PLAY, frame);
            lv.loop = true; lv.vel = 0.9f;
            lv.start = st; lv.end = en; lv.speed = sp;
            lv.pos = reverse ? en - 1 : st;
            lv.trigger(-3, frame);
            looperV = lv;
        } else {
            lv.speedTgt = sp; lv.glide = 0.02f;       // direction change glides through zero, no click
            lv.setRegion(st, en);
        }
        for (int i = 0; i < tCount; i++) tVoice[i] = lv;
    }

    // ---------------------------------------------------------------- arpeggiator step

    /**
     * One arpeggiator step. The slices under the held fingers form the pattern; direction decides the
     * order, the rate decides the speed (locked to the project beat grid). Each step plays the start of the
     * slice for one step's length (or the whole slice when the infinity button is on).
     */
    private void arpStep(long frame, double stepFrames) {
        Sample s = sample;
        if (s == null || tCount == 0) return;
        int len = s.length();
        // distinct slices under the fingers, in touch order
        int n = 0;
        for (int k = 0; k < tCount; k++) {
            int sl = sliceIndex(tX[k]);
            boolean dup = false;
            for (int q = 0; q < n; q++) if (arpOrd[q] == sl) { dup = true; break; }
            if (!dup) { arpOrd[n] = sl; arpVel[n] = 0.25f + 0.75f * (1f - tY[k]); n++; }
        }
        if (n == 0) return;
        int[] sorted = arpTmp;
        System.arraycopy(arpOrd, 0, sorted, 0, n);
        // keep velocities attached: sort indices by slice number
        float[] vel = new float[n];
        for (int a = 0; a < n; a++) vel[a] = arpVel[a];
        if (arpDir != 4) {
            for (int a = 1; a < n; a++) {                       // insertion sort, n <= 16
                int key = sorted[a]; float kv = vel[a]; int b = a - 1;
                while (b >= 0 && sorted[b] > key) { sorted[b + 1] = sorted[b]; vel[b + 1] = vel[b]; b--; }
                sorted[b + 1] = key; vel[b + 1] = kv;
            }
        }
        int pick;
        int dir = arpRandom ? 3 : arpDir;
        switch (dir) {
            case 1: pick = n - 1 - (arpCounter % n); break;
            case 2: {
                int period = Math.max(1, 2 * n - 2);
                int j = arpCounter % period;
                pick = j < n ? j : period - j;
                break;
            }
            case 3:
                pick = (int) (Math.random() * n);
                if (n > 1 && sorted[pick] == arpLast) pick = (pick + 1) % n;     // no immediate repeats
                break;
            default: pick = arpCounter % n; break;
        }
        arpCounter++;
        int slice = sorted[pick];
        arpLast = slice;
        sliceBounds(slice, bounds);
        Voice v = prepare(Voice.PLAY, frame);
        v.start = bounds[0] * len;
        double sliceEnd = bounds[1] * len;
        double play = infinite ? (sliceEnd - v.start) : stepFrames * v.ratio * pitchRatio() * 0.98;
        v.end = Math.min(sliceEnd, v.start + play);
        if (v.end - v.start < 64) v.end = Math.min(len, v.start + 64);
        v.pos = v.start;
        v.vel = vel[pick];
        v.setEnv(attack, decay, sustain, infinite ? release : Math.min(release, (float) (stepFrames / rate * 0.9)));
        v.trigger(-4, frame);
        arpSlice = slice;
        arpFlash = 1f;
    }

    // ---------------------------------------------------------------- audio

    /** Adds this slot's stereo output into outL/outR (after its own effects). n <= MAX_BLOCK. */
    public void render(float[] outL, float[] outR, int n, long frame, double bpm, boolean playing) {
        render(outL, outR, n, frame, bpm, playing, frame * bpm / (60.0 * rate));
    }

    /** beat = position on the project's beat grid at the start of the block (arp steps lock to it). */
    public void render(float[] outL, float[] outR, int n, long frame, double bpm, boolean playing, double beat) {
        Sample s = sample;
        java.util.Arrays.fill(mono, 0, n, 0f);
        java.util.Arrays.fill(bl, 0, n, 0f);
        java.util.Arrays.fill(br, 0, n, 0f);
        if (s != null) {
            int off = 0;
            // sample-accurate arp steps on the beat grid: split the block at step boundaries
            final double framesPerBeat = rate * 60.0 / bpm;
            final double stepBeats = 4.0 / Math.max(1, arpRate);
            while (off < n) {
                int chunk = n - off;
                if (mode == ARP && tCount > 0) {
                    double cur = beat + off / framesPerBeat;
                    if (nextStepBeat < 0 || nextStepBeat < cur - stepBeats * 2) nextStepBeat = Math.ceil(cur / stepBeats - 1e-9) * stepBeats;
                    if (arpImmediate) {
                        arpImmediate = false;
                        double untilNext = (nextStepBeat - cur) * framesPerBeat;
                        if (untilNext > 0.2 * stepBeats * framesPerBeat) {        // first note now, the rest on the grid
                            arpStep(frame + off, stepBeats * framesPerBeat);
                            continue;
                        }
                    }
                    double until = (nextStepBeat - cur) * framesPerBeat;
                    if (until <= 0.5) {
                        arpStep(frame + off, stepBeats * framesPerBeat);
                        nextStepBeat += stepBeats;
                        continue;
                    }
                    chunk = Math.min(chunk, (int) Math.ceil(until));
                } else {
                    nextStepBeat = -1;
                }
                renderVoices(off, chunk, bpm, playing);
                off += chunk;
            }
        }
        arpFlash *= 0.92f;
        float p = pan;
        float gl = (float) Math.cos(p * Math.PI * 0.5) * 1.414f * 0.7071f;
        float gr = (float) Math.sin(p * Math.PI * 0.5) * 1.414f * 0.7071f;
        float v = muted ? 0f : volume;
        float peak = 0f;
        final float tgtL = v * gl, tgtR = v * gr;
        final float dL = (tgtL - curGL) / n, dR = (tgtR - curGR) / n;   // ramp per block: no zipper noise on sliders
        for (int i = 0; i < n; i++) {
            curGL += dL; curGR += dR;
            bl[i] = mono[i] * curGL;
            br[i] = mono[i] * curGR;
            float a = Math.abs(mono[i]) * v;
            if (a > peak) peak = a;
        }
        for (int e = 0; e < Fx.COUNT; e++) fx[e].process(bl, br, n, bpm);
        for (int i = 0; i < n; i++) { outL[i] += bl[i]; outR[i] += br[i]; }
        level = Math.max(peak, level * 0.9f);
        int act = 0;
        for (int i = 0; i < VOICES; i++) {
            if (voices[i].active) { act++; playheads[i] = voices[i].sm == null ? 0f : (float) (voices[i].pos / Math.max(1, voices[i].sm.length())); }
            else playheads[i] = -1f;
        }
        activeVoices = act;
    }

    private final float[] chunkBuf = new float[MAX_BLOCK];

    private void renderVoices(int off, int n, double bpm, boolean playing) {
        java.util.Arrays.fill(chunkBuf, 0, n, 0f);
        Sample s = sample;
        for (Voice v : voices) {
            if (!v.active) continue;
            if (v.touchId != -4) v.setEnv(attack, decay, sustain, release);     // ADSR sliders act on held notes live
            if (v.touchId == -2 && s != null) {
                if (!playing) { v.release(); continue; }
                double dur = s.length() / (double) s.sampleRate;
                v.speed = bpm * dur / (60.0 * sampleBeats) * pitchRatio();   // lock loop to project tempo
            }
            if (v.kind == Voice.PLAY && mode == KEYBOARD && legato) { /* speed set in move() */ }
            v.render(chunkBuf, n);
        }
        for (int i = 0; i < n; i++) mono[off + i] += chunkBuf[i];
    }

    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
