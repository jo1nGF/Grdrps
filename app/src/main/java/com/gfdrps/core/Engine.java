package com.gfdrps.core;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Mixes the 6 slots, master FX, metronome, transport clock, gesture playback and resampling. */
public final class Engine {
    /** SLOTS = most tracks the app can hold; {@link #slotCount} of them are in use (starts at 6, "+" adds more). */
    public static final int SLOTS = 12, START_SLOTS = 6, BLOCK = 256;
    public static final int REC_OFF = 0, REC_INPUT = 1, REC_MIX = 2;

    public final int rate;
    public final Slot[] slots = new Slot[SLOTS];
    public final Fx.Effect[] master = new Fx.Effect[Fx.COUNT];

    public volatile int slotCount = START_SLOTS;
    public volatile double bpm = 120.0;
    public volatile float masterVolume = 0.8f;
    public volatile boolean playing = true, metronome = false;
    /** FX pad hold: when set, letting go of the pad leaves the effect running. */
    public volatile boolean fxHold = false;
    public volatile float peakL, peakR;
    public volatile double beat;   // current beat position (UI display)

    private long frame;
    private double beatAcc, freeBeat;
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
    /** Live level (0..1) and a 50 per second peak history of the running recording, for the recording screen. */
    public volatile float recLevel;
    private float[] recPk = new float[64];
    private int recPkN, recPkAcc; private float recPkMax;

    private final float[] mixL = new float[Slot.MAX_BLOCK], mixR = new float[Slot.MAX_BLOCK], recTmp = new float[Slot.MAX_BLOCK];

    public Engine(int rate) {
        this.rate = rate;
        for (int i = 0; i < SLOTS; i++) slots[i] = new Slot(rate);
        for (int i = 0; i < Fx.COUNT; i++) master[i] = Fx.create(i, rate);
        for (int i = 0; i < START_SLOTS; i++) slots[i].setSample(DemoSynth.make(i, rate, bpm), bpm);
    }

    /** Adds one more track (up to {@link #SLOTS}). Returns its index or -1 when full. The new track starts with a demo loop. */
    public synchronized int addSlot() {
        int n = slotCount;
        if (n >= SLOTS) return -1;
        if (slots[n].sample == null) slots[n].setSample(DemoSynth.make(n, rate, bpm), bpm);
        slotCount = n + 1;
        return n;
    }

    /** Makes sure at least n tracks are in use (project load). */
    public synchronized void ensureSlots(int n) { while (slotCount < Math.min(SLOTS, n)) addSlot(); }

    // ---------------------------------------------------------------- control (any thread)

    public void touch(int slot, int type, int id, float x, float y) {
        Ev e = new Ev();
        e.slot = slot; e.type = type; e.id = id; e.x = x; e.y = y;
        events.add(e);
    }

    public void post(Runnable r) { commands.add(r); }

    public void loadSample(int slot, Sample s) {
        pushUndo(slot);
        post(() -> slots[slot].setSample(s, bpm));
    }

    /** Sets manual slice markers (positions 0..1). Pass null/empty to go back to the even grid. */
    public void setMarkers(int slot, float[] m) { slots[slot].markers = Slot.cleanMarkers(m); }

    /** Detects hits in the slot's sample and uses them as slice markers. Returns how many were found. */
    public int autoSlice(int slot, int max, float sensitivity) {
        Sample s = slots[slot].sample;
        if (s == null) return 0;
        pushUndo(slot);
        float[] m = Slicing.detect(s, max, sensitivity);
        slots[slot].markers = m.length == 0 ? null : m;
        return m.length;
    }

    /**
     * Real stop: pauses the transport (loop players, recorded gestures), fades out every voice of every
     * slot and clears held fingers. Playing again with the transport button resumes loops and gestures.
     */
    public void stopAll() {
        post(() -> {
            playing = false;
            for (Slot s : slots) s.fadeAll();
        });
    }

    public void reverseSample(int slot) { pushUndo(slot); post(() -> slots[slot].reverseSample()); }

    // ---------------------------------------------------------------- undo
    private static final class UndoItem { Slot.Snapshot sn; GestureRecorder.Snap gs; }
    @SuppressWarnings("unchecked")
    private final java.util.ArrayDeque<UndoItem>[] undo = new java.util.ArrayDeque[SLOTS];

    /** Remembers the slot's sample, markers, region and recorded gestures so {@link #undo} can bring them back. */
    public synchronized void pushUndo(int slot) {
        UndoItem u = new UndoItem();
        u.sn = slots[slot].snapshot();
        u.gs = slots[slot].gestures.snap();
        if (undo[slot] == null) undo[slot] = new java.util.ArrayDeque<>();
        undo[slot].push(u);
        if (redo[slot] != null) redo[slot].clear();          // a new edit ends the redo chain
        while (undo[slot].size() > 8) undo[slot].removeLast();
        // memory budget: undo keeps old samples alive, so drop the oldest steps if they add up to too much
        for (;;) {
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            long bytes = 0; int slotMost = -1, most = 0;
            for (int i = 0; i < SLOTS; i++) {
                if (undo[i] == null) continue;
                if (undo[i].size() > most) { most = undo[i].size(); slotMost = i; }
                for (UndoItem it : undo[i]) if (it.sn.sample != null && seen.put(it.sn.sample, true) == null) bytes += 4L * it.sn.sample.data.length;
            }
            if (bytes <= 160L * 1024 * 1024 || slotMost < 0 || undo[slotMost].size() <= 1) break;
            undo[slotMost].removeLast();
        }
    }

    public synchronized void clearUndo() { for (int i = 0; i < SLOTS; i++) { if (undo[i] != null) undo[i].clear(); if (redo[i] != null) redo[i].clear(); } }

    public synchronized boolean canUndo(int slot) { return undo[slot] != null && !undo[slot].isEmpty(); }

    @SuppressWarnings("unchecked")
    private final java.util.ArrayDeque<UndoItem>[] redo = new java.util.ArrayDeque[SLOTS];

    public synchronized boolean canRedo(int slot) { return redo[slot] != null && !redo[slot].isEmpty(); }

    private UndoItem current(int slot) {
        UndoItem u = new UndoItem();
        u.sn = slots[slot].snapshot();
        u.gs = slots[slot].gestures.snap();
        return u;
    }

    private void apply(int slot, UndoItem u) {
        post(() -> {
            slots[slot].restore(u.sn);
            slots[slot].gestures.restore(u.gs, (t, id, x, y) -> emitGesture(slot, t, id, x, y));
        });
    }

    public synchronized boolean undo(int slot) {
        if (!canUndo(slot)) return false;
        final UndoItem u = undo[slot].pop();
        if (redo[slot] == null) redo[slot] = new java.util.ArrayDeque<>();
        redo[slot].push(current(slot));
        while (redo[slot].size() > 8) redo[slot].removeLast();
        apply(slot, u);
        return true;
    }

    public synchronized boolean redo(int slot) {
        if (!canRedo(slot)) return false;
        final UndoItem u = redo[slot].pop();
        if (undo[slot] == null) undo[slot] = new java.util.ArrayDeque<>();
        undo[slot].push(current(slot));
        apply(slot, u);
        return true;
    }

    /** Cuts the slot's sample to its region (start/end handles). Returns immediately; undoable. */
    public void trim(int slot) {
        pushUndo(slot);
        post(() -> slots[slot].trimToRegion());
    }

    /** Region handles (Keyboard start/end, also what trim uses). */
    public void setRegion(int slot, float a, float b) {
        a = Math.max(0f, Math.min(1f, a)); b = Math.max(0f, Math.min(1f, b));
        slots[slot].regStart = Math.min(a, b - 0.002f < 0 ? 0 : b - 0.002f);
        slots[slot].regEnd = Math.max(b, a + 0.002f > 1 ? 1 : a + 0.002f);
    }

    // gesture take editing (audio thread)
    public void quantizeGestures(int slot, double grid) { pushUndo(slot); post(() -> slots[slot].gestures.quantize(grid, (t, id, x, y) -> emitGesture(slot, t, id, x, y))); }
    public void nudgeGestures(int slot, double beats) { pushUndo(slot); post(() -> slots[slot].gestures.nudge(beats, (t, id, x, y) -> emitGesture(slot, t, id, x, y))); }
    public void removeGestureKind(int slot, boolean fx) { pushUndo(slot); post(() -> slots[slot].gestures.removeKind(fx, (t, id, x, y) -> emitGesture(slot, t, id, x, y))); }

    // ---------------------------------------------------------------- MIDI clock in
    private final MidiClock midiClock = new MidiClock();
    /** When true the project tempo follows incoming MIDI clock, and start/stop messages drive the transport. */
    public volatile boolean midiSync = false;

    public void midiTick(long nanos) {
        double b = midiClock.tick(nanos);
        if (midiSync && b > 0) bpm = Math.max(30.0, Math.min(300.0, b));
    }
    public void midiStart() { if (!midiSync) return; midiClock.reset(); post(() -> { beatAcc = 0; playing = true; }); }
    public void midiContinue() { if (midiSync) playing = true; }
    public void midiStop() { if (!midiSync) return; post(() -> { playing = false; for (Slot s : slots) s.fadeAll(); }); }

    // ---------------------------------------------------------------- export of what you hear (WAV)
    private volatile boolean exporting;
    private final java.util.concurrent.ConcurrentLinkedQueue<short[]> exportChunks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile int exportFrames;
    public static final int EXPORT_MAX_SECONDS = 300;

    public void startExport() { exportChunks.clear(); exportFrames = 0; exporting = true; }
    public boolean isExporting() { return exporting; }
    public int exportSeconds() { return exportFrames / rate; }

    /** Stops the capture and returns interleaved stereo 16-bit PCM of everything that was played (null if nothing). */
    public short[] stopExport() {
        exporting = false;
        int total = 0;
        for (short[] c : exportChunks) total += c.length;
        if (total == 0) return null;
        short[] out = new short[total];
        int o = 0;
        for (short[] c : exportChunks) { System.arraycopy(c, 0, out, o, c.length); o += c.length; }
        exportChunks.clear();
        return out;
    }

    public void setMode(int slot, int mode) { slots[slot].mode = mode; }

    /** Latch on/off for one slot (applied on the audio thread). */
    public void setLatch(int slot, boolean on) { post(() -> slots[slot].applyLatch(on)); }

    /**
     * XY pad touch (also recorded by the gesture recorder). type 3 = down, 4 = move, 5 = up.
     * fxId = effect index, +16 when the pad is editing the master bus.
     */
    public void fxTouch(int slot, int type, int fxId, float x, float y) { touch(slot, type, fxId, x, y); }

    private void applyFx(int slot, int type, int fxId, float x, float y) {
        int idx = fxId & 15;
        if (idx >= Fx.COUNT) return;
        Fx.Effect ef = (fxId & 16) != 0 ? master[idx] : slots[slot].fx[idx];
        if (type == 5) { if (!fxHold) ef.active = false; return; }
        ef.setXY(x, y);
        ef.active = true;
    }

    /** Arms the recorder: it starts by itself at the first touch (waveform or effect pad) and runs for `bars`. */
    public void armGestures(int slot, int bars) {
        post(() -> slots[slot].gestures.arm(beatAcc, bars));
    }

    public void clearGestures(int slot) {
        pushUndo(slot);
        post(() -> slots[slot].gestures.clear((t, id, x, y) -> emitGesture(slot, t, id, x, y)));
    }

    private void emitGesture(int slot, int t, int id, float x, float y) {
        if (t >= 3) applyFx(slot, t, id - GestureRecorder.ID_OFFSET, x, y);
        else slots[slot].handle(t, id, x, y, frame, beatAcc);
    }

    /** Replaces the recorded gestures of a slot (project load). */
    public void setGestures(int slot, int bars, double[] phase, int[] type, int[] id, float[] x, float[] y) {
        post(() -> slots[slot].gestures.load(bars, phase, type, id, x, y));
    }

    /** Starts capturing the external input or the final mix into a growing buffer. */
    public void startRecording(int slot, int mode) {
        synchronized (recLock) {
            recBuf = new float[rate * 60];
            recLen = 0; recPkN = 0; recPkAcc = 0; recPkMax = 0f; recLevel = 0f;
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
            final int per = Math.max(1, rate / 50);
            float lv = 0f;
            for (int i = 0; i < c; i++) {
                float a = Math.abs(d[off + i]);
                if (a > lv) lv = a;
                if (a > recPkMax) recPkMax = a;
                if (++recPkAcc >= per) {
                    if (recPkN == recPk.length) recPk = java.util.Arrays.copyOf(recPk, recPk.length * 2);
                    recPk[recPkN++] = Math.min(1f, recPkMax); recPkMax = 0f; recPkAcc = 0;
                }
            }
            recLevel = Math.min(1f, lv);
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

    /** Copies the newest peaks (50 per second) into out, oldest first. Returns how many were written (the rest of out untouched). */
    public int recordingPeaks(float[] out) {
        synchronized (recLock) {
            int n = Math.min(out.length, recPkN);
            System.arraycopy(recPk, recPkN - n, out, 0, n);
            return n;
        }
    }

    /** Throws the running capture away. */
    public void cancelRecording() { synchronized (recLock) { recMode = REC_OFF; recLen = 0; recPkN = 0; recLevel = 0f; } }

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
            if (exporting) {
                if (exportFrames < rate * EXPORT_MAX_SECONDS) {
                    exportChunks.add(java.util.Arrays.copyOfRange(pcm, done * 2, (done + n) * 2));
                    exportFrames += n;
                } else exporting = false;            // length cap reached
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
        final int sc = slotCount;
        for (int i = 0; i < sc; i++) {
            final Slot s = slots[i];
            final int si = i;
            s.gestures.update(beatNow, playing, (t, id, x, y) -> emitGesture(si, t, id, x, y));
        }
        // live touches
        Ev e;
        while ((e = events.poll()) != null) {
            Slot s = slots[e.slot];
            if (s.gestures.state == GestureRecorder.ARMED && e.type != 2 && e.type != 5) playing = true;   // first touch starts the take (and the clock)
            if (e.type >= 3) applyFx(e.slot, e.type, e.id, e.x, e.y);
            else s.handle(e.type, e.id, e.x, e.y, frame, beatNow);
            s.gestures.add(e.type, e.id, e.x, e.y, beatNow);
        }
        for (int i = 0; i < sc; i++) if (slots[i].requestTransport) { slots[i].requestTransport = false; playing = true; }
        java.util.Arrays.fill(mixL, 0, n, 0f);
        java.util.Arrays.fill(mixR, 0, n, 0f);
        boolean anySolo = false;
        final double gridBeat = playing ? beatNow : freeBeat;     // arp keeps running while the transport is stopped
        for (int i = 0; i < sc; i++) slots[i].render(mixL, mixR, n, frame, b, playing, gridBeat);
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
        freeBeat += n * b / (60.0 * rate);
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
