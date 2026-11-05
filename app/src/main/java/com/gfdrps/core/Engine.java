package com.gfdrps.core;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Mixes the 6 slots, master FX, metronome, transport clock, gesture playback and resampling. */
public final class Engine {
    public static final int SLOTS = 6, BLOCK = 256;
    public static final int REC_OFF = 0, REC_INPUT = 1, REC_MIX = 2;

    public final int rate;
    public final Slot[] slots = new Slot[SLOTS];
    public final Fx.Effect[] master = new Fx.Effect[Fx.COUNT];

    public volatile double bpm = 120.0;
    public volatile float masterVolume = 0.8f;
    public volatile boolean playing = true, metronome = false;
    public volatile float peakL, peakR;
    public volatile double beat;   // current beat position (UI display)

    private long frame;
    private double beatAcc;
    private double clickPhase; private int clickLeft;
    private double lastBeatInt = -1;

    private static final class Ev { int slot, type, id; float x, y; }
    private final ConcurrentLinkedQueue<Ev> events = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    // recording
    private final Object recLock = new Object();
    private volatile int recMode = REC_OFF;
    private volatile int recSlot = 0;
    private float[] recBuf = new float[0];
    private int recLen;
    public volatile int recSeconds;

    private final float[] mixL = new float[Slot.MAX_BLOCK], mixR = new float[Slot.MAX_BLOCK], recTmp = new float[Slot.MAX_BLOCK];

    public Engine(int rate) {
        this.rate = rate;
        for (int i = 0; i < SLOTS; i++) slots[i] = new Slot(rate);
        for (int i = 0; i < Fx.COUNT; i++) master[i] = Fx.create(i, rate);
        for (int i = 0; i < SLOTS; i++) slots[i].setSample(DemoSynth.make(i, rate, bpm), bpm);
    }

    // ---------------------------------------------------------------- control (any thread)

    public void touch(int slot, int type, int id, float x, float y) {
        Ev e = new Ev();
        e.slot = slot; e.type = type; e.id = id; e.x = x; e.y = y;
        events.add(e);
    }

    public void post(Runnable r) { commands.add(r); }

    public void loadSample(int slot, Sample s) {
        post(() -> slots[slot].setSample(s, bpm));
    }

    /** Sets manual slice markers (positions 0..1). Pass null/empty to go back to the even grid. */
    public void setMarkers(int slot, float[] m) { slots[slot].markers = Slot.cleanMarkers(m); }

    /** Detects hits in the slot's sample and uses them as slice markers. Returns how many were found. */
    public int autoSlice(int slot, int max, float sensitivity) {
        Sample s = slots[slot].sample;
        if (s == null) return 0;
        float[] m = Slicing.detect(s, max, sensitivity);
        slots[slot].markers = m.length == 0 ? null : m;
        return m.length;
    }

    public void reverseSample(int slot) { post(() -> slots[slot].reverseSample()); }

    public void setMode(int slot, int mode) { slots[slot].mode = mode; }

    public void armGestures(int slot, int bars) {
        post(() -> slots[slot].gestures.arm(beatAcc, bars));
    }

    public void clearGestures(int slot) {
        post(() -> slots[slot].gestures.clear((t, id, x, y) -> slots[slot].handle(t, id, x, y, frame, beatAcc)));
    }

    /** Starts capturing the external input or the final mix into a growing buffer. */
    public void startRecording(int slot, int mode) {
        synchronized (recLock) {
            recBuf = new float[rate * 60];
            recLen = 0;
            recSlot = slot;
            recMode = mode;
        }
    }

    /** Called by the microphone thread. */
    public void pushInput(float[] data, int n) {
        if (recMode != REC_INPUT) return;
        append(data, 0, n);
    }

    private void append(float[] d, int off, int n) {
        synchronized (recLock) {
            int room = recBuf.length - recLen;
            int c = Math.min(room, n);
            System.arraycopy(d, off, recBuf, recLen, c);
            recLen += c;
            recSeconds = recLen / rate;
        }
    }

    /** Ends the capture and drops the result into the chosen slot. Returns false if nothing was recorded. */
    public boolean stopRecording(String name, int inputRate) {
        float[] copy;
        int target;
        synchronized (recLock) {
            if (recMode == REC_OFF || recLen < 256) { recMode = REC_OFF; return false; }
            copy = java.util.Arrays.copyOf(recBuf, recLen);
            target = recSlot;
            recMode = REC_OFF;
        }
        float mx = 0f;
        for (float f : copy) mx = Math.max(mx, Math.abs(f));
        if (mx > 0.0001f && mx < 0.5f) { float g = 0.7f / mx; for (int i = 0; i < copy.length; i++) copy[i] *= g; }
        loadSample(target, new Sample(copy, inputRate, name));
        return true;
    }

    public int recordingMode() { return recMode; }

    // ---------------------------------------------------------------- audio thread

    /** Renders interleaved stereo 16-bit PCM. frames can be any size. */
    public void process(short[] pcm, int frames) {
        int done = 0;
        while (done < frames) {
            int n = Math.min(BLOCK, frames - done);
            renderBlock(n);
            for (int i = 0; i < n; i++) {
                pcm[(done + i) * 2] = (short) (limit(mixL[i]) * 32767f);
                pcm[(done + i) * 2 + 1] = (short) (limit(mixR[i]) * 32767f);
            }
            done += n;
        }
    }

    private static float limit(float v) {
        if (v != v) return 0f;
        float a = Math.abs(v);
        if (a <= 0.85f) return v;
        float o = 0.85f + 0.15f * (float) Math.tanh((a - 0.85f) / 0.15f);
        return v < 0 ? -o : o;
    }

    private void renderBlock(int n) {
        Runnable c;
        while ((c = commands.poll()) != null) c.run();
        double b = bpm;
        final double beatNow = beatAcc;
        // gesture playback
        for (int i = 0; i < SLOTS; i++) {
            final Slot s = slots[i];
            s.gestures.update(beatNow, playing, (t, id, x, y) -> s.handle(t, id, x, y, frame, beatNow));
        }
        // live touches
        Ev e;
        while ((e = events.poll()) != null) {
            Slot s = slots[e.slot];
            s.handle(e.type, e.id, e.x, e.y, frame, beatNow);
            s.gestures.add(e.type, e.id, e.x, e.y, beatNow);
        }
        java.util.Arrays.fill(mixL, 0, n, 0f);
        java.util.Arrays.fill(mixR, 0, n, 0f);
        boolean anySolo = false;
        for (int i = 0; i < SLOTS; i++) slots[i].render(mixL, mixR, n, frame, b, playing);
        for (int i = 0; i < Fx.COUNT; i++) master[i].process(mixL, mixR, n, b);
        float mv = masterVolume;
        float pl = 0f, pr = 0f;
        for (int i = 0; i < n; i++) {
            mixL[i] *= mv; mixR[i] *= mv;
        }
        if (metronome && playing) clicks(n);
        for (int i = 0; i < n; i++) {
            pl = Math.max(pl, Math.abs(mixL[i])); pr = Math.max(pr, Math.abs(mixR[i]));
        }
        peakL = Math.max(pl, peakL * 0.9f);
        peakR = Math.max(pr, peakR * 0.9f);
        if (recMode == REC_MIX) {
            for (int i = 0; i < n; i++) recTmp[i] = (mixL[i] + mixR[i]) * 0.5f;
            append(recTmp, 0, n);
        }
        frame += n;
        if (playing) beatAcc += n * b / (60.0 * rate);
        beat = beatAcc;
    }

    private void clicks(int n) {
        double beatStart = beatAcc;
        double perFrame = bpm / (60.0 * rate);
        for (int i = 0; i < n; i++) {
            double bt = beatStart + i * perFrame;
            double bi = Math.floor(bt);
            if (bi != lastBeatInt) {
                lastBeatInt = bi;
                clickLeft = (int) (rate * 0.03);
                clickPhase = 0;
                clickAccent = (((long) bi) % 4 == 0);
            }
            if (clickLeft > 0) {
                float env = clickLeft / (rate * 0.03f);
                float v = (float) Math.sin(clickPhase) * env * 0.25f;
                clickPhase += 2 * Math.PI * (clickAccent ? 1600 : 1000) / rate;
                mixL[i] += v; mixR[i] += v;
                clickLeft--;
            }
        }
    }
    private boolean clickAccent;
}
