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
    public volatile float volume = 0.8f, attack = 0f, release = 0.15f, pan = 0.5f;
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
    // arp
    private double nextStepFrame = -1;
    private int arpIndex;
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

    public void stopAll() {
        for (Voice v : voices) v.kill();
        tCount = 0;
        for (int i = 0; i < tVoice.length; i++) tVoice[i] = null;
    }

    private double pitchRatio() { return Math.pow(2.0, transpose / 12.0); }
    private int sliceCount() { int d = div; return d <= 0 ? 8 : d; }

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
        v.setEnv(attack, release);
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
                float[] mk = markers;
                double a0, a1;
                if (mk != null) {
                    int idx = 0;
                    while (idx < mk.length && x >= mk[idx]) idx++;
                    a0 = idx == 0 ? 0.0 : mk[idx - 1];
                    a1 = idx == mk.length ? 1.0 : mk[idx];
                } else {
                    int n = sliceCount();
                    int idx = Math.min(n - 1, (int) (x * n));
                    a0 = (double) idx / n;
                    a1 = (double) (idx + 1) / n;
                }
                Voice v = prepare(Voice.PLAY, frame);
                v.start = a0 * len;
                v.end = a1 * len;
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
                if (tCount == 1) nextStepFrame = frame;   // first step right away
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

    private void arpStep(long frame, double bpm) {
        Sample s = sample;
        if (s == null || tCount == 0) return;
        int len = s.length();
        int n = tCount;
        int idx = arpRandom ? Math.min(n - 1, (int) (Math.random() * n)) : (arpIndex++ % n);
        float x = tX[idx], y = tY[idx];
        double stepFrames = rate * 60.0 / bpm * 4.0 / sliceCountArp();
        Voice v = prepare(Voice.PLAY, frame);
        double srcLen = stepFrames * v.ratio * pitchRatio();
        v.start = x * len;
        v.end = Math.min(len, v.start + srcLen);
        if (v.end - v.start < 64) v.start = Math.max(0, v.end - 64);
        v.pos = v.start;
        v.vel = 0.25f + 0.75f * (1f - y);
        v.setEnv(Math.min(attack, 0.005f), Math.max(0.01f, (float) (stepFrames / rate * 0.4)));
        v.trigger(-4, frame);
    }

    private int sliceCountArp() { int d = div; return d <= 0 ? 8 : d; }

    // ---------------------------------------------------------------- audio

    /** Adds this slot's stereo output into outL/outR (after its own effects). n <= MAX_BLOCK. */
    public void render(float[] outL, float[] outR, int n, long frame, double bpm, boolean playing) {
        Sample s = sample;
        java.util.Arrays.fill(mono, 0, n, 0f);
        java.util.Arrays.fill(bl, 0, n, 0f);
        java.util.Arrays.fill(br, 0, n, 0f);
        if (s != null) {
            int off = 0;
            // sample-accurate arp steps: split the block at step boundaries
            while (off < n) {
                int chunk = n - off;
                if (mode == ARP && tCount > 0 && nextStepFrame >= 0) {
                    double stepFrames = rate * 60.0 / bpm * 4.0 / sliceCountArp();
                    while (nextStepFrame < frame + off) nextStepFrame += stepFrames;
                    int until = (int) (nextStepFrame - frame);
                    if (until <= off) {
                        arpStep(frame + off, bpm);
                        nextStepFrame += stepFrames;
                        continue;
                    }
                    chunk = Math.min(chunk, until - off);
                } else if (mode != ARP || tCount == 0) {
                    nextStepFrame = -1;
                } else if (nextStepFrame < 0) {
                    nextStepFrame = frame + off;   // switched into Arp mode with fingers already down
                }
                renderVoices(off, chunk, bpm, playing);
                off += chunk;
            }
        }
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
