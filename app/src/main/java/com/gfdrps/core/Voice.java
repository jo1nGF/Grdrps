package com.gfdrps.core;

/** One playing voice of a slot. Rendering is mode-specific; all positions are in sample frames. */
final class Voice {
    static final int PLAY = 0, BOW = 1, TAPE = 2, SCRATCH = 3;
    private static final float DIP = 1f / 96f;   // 2 ms declick when a voice has to jump

    volatile boolean active;
    boolean held;          // finger still down
    boolean releasing;
    boolean latched;       // finger gone but the latch button keeps the note sounding
    int touchId = -1;
    int kind = PLAY;
    long startedAt;        // engine frame counter, for voice stealing

    Sample sm;
    double ratio = 1.0;    // sample rate / engine rate
    int engineRate = 48000;

    double pos, speed = 1.0;
    double speedTgt = 1.0; float glide = 0f;      // glide > 0: speed slews to speedTgt (no zipper/clicks)
    double start, end;
    boolean loop;
    float vel = 1f, env = 0f;
    // ADSR: stage 0 attack, 1 decay, 2 sustain, 3 release
    int stage;
    float attInc = 0.01f, decMul = 0.999f, sus = 1f, relStep = 0.001f;
    float relFrames = 7200f;
    float tapeMul = 0.9998f;

    // declick when jumping position
    private int dip;
    private float dipG = 1f;
    private double pendPos;

    // bow
    double center;
    float grainLen = 2000f;
    final double[] gPos = new double[2];
    final float[] gPh = {0f, 0.5f};
    int rnd = 12345;

    // tape / scratch
    double scrTarget, scrBase, scrSpan, scrVel;
    double scrTv; int scrAge, scrDt;      // finger velocity (samples of audio per frame) and frames since the last touch update
    float scratchAmp, tapeAmp;
    double baseSpeed = 1.0;
    boolean decayOnRelease;

    /** Sets the ADSR times (seconds) and sustain level (0..1). Safe to call while the voice is playing. */
    void setEnv(float attackSec, float decaySec, float sustain, float releaseSec) {
        attInc = 1f / Math.max(64f, attackSec * engineRate);                      // >= 1.3 ms
        float df = Math.max(64f, decaySec * engineRate);
        decMul = (float) Math.exp(-4.6 / df);                                     // ~99% of the way in decaySec
        sus = sustain < 0f ? 0f : (sustain > 1f ? 1f : sustain);
        relFrames = Math.max(96f, releaseSec * engineRate);                       // >= 2 ms
    }

    void trigger(int id, long frame) {
        active = true;
        held = true;
        releasing = false;
        latched = false;
        scrAge = 0; scrTv = 0.0; scrDt = 0;
        stage = 0;
        decayOnRelease = false;
        scratchAmp = 0f;
        tapeAmp = 0f;
        scrVel = 0.0;
        dip = 0; dipG = 1f;
        speedTgt = speed;
        touchId = id;
        startedAt = frame;
        env = 0f;
    }

    /** Scratch: the finger now points at audio position t (called when a touch update arrives). */
    void scratchTo(double t) {
        if (scrAge > 0 && scrAge < 6000) {
            double v = (t - scrTarget) / scrAge;                    // speed since the previous update
            scrTv = scrTv * 0.5 + v * 0.5;
            scrDt = Math.min(scrAge, 2400);
        } else { scrTv = 0.0; scrDt = 0; }
        scrTarget = t;
        scrAge = 0;
    }

    /** Moves the playhead without a click (short fade out, jump, fade in). */
    void jumpTo(double p) { pendPos = p; dip = 1; }

    /**
     * Moves the loop markers while playing. If the playhead would suddenly sit inside the new
     * crossfade zone or outside the region, it restarts through a short declick instead of jumping.
     */
    void setRegion(double st, double en) {
        start = st; end = en;
        if (dip != 0) return;
        double xf = Math.min((en - st) * 0.25, engineRate * 0.004);
        if (speedTgt >= 0) { if (pos < st || pos > en - xf) jumpTo(st); }
        else if (pos >= en || pos < st + xf) jumpTo(en - 1);
    }

    /** Finger lifted. Tape: the tape winds down over ~2x the release time. Everything else: ADSR release. */
    void release() {
        held = false;
        glide = 0f;
        if (kind == TAPE) {
            decayOnRelease = true;
            tapeMul = (float) Math.exp(-4.6 / Math.max(0.35 * engineRate, relFrames * 2.0));
            return;
        }
        beginRelease(relFrames);
    }

    /** Starts the release stage so the level reaches zero in `frames` samples from wherever it is now. */
    private void beginRelease(double frames) {
        if (releasing) return;
        releasing = true;
        stage = 3;
        relStep = (float) (Math.max(env, 1e-4f) / Math.max(48.0, frames));
    }

    /** Fast fade-out, used when a voice is stolen. */
    void fastRelease() {
        held = false;
        glide = 0f;
        decayOnRelease = false;
        if (releasing) { relStep = Math.max(relStep, env / 240f); return; }
        releasing = true;
        stage = 3;
        relStep = Math.max(env, 1e-4f) / 240f;     // 5 ms
    }

    void kill() { active = false; releasing = false; held = false; }

    private float nextRand() {
        rnd = rnd * 1664525 + 1013904223;
        return ((rnd >>> 8) & 0xFFFF) / 65536f;
    }

    private float readLoop(double p) {
        // outside the region (just moved by a finger) or mid-declick: read plainly, the dip hides the jump
        if (dip != 0 || p < start || p >= end) return sm.at(p);
        double len = end - start;
        double xf = Math.min(len * 0.25, engineRate * 0.004);
        if (speed >= 0) {
            if (xf > 1 && p > end - xf) {
                float t = (float) Math.min(1.0, (p - (end - xf)) / xf);   // clamped: the region may have just shrunk
                return sm.at(p) * (1f - t) + sm.at(p - len) * t;
            }
        } else if (xf > 1 && p < start + xf) {
            float t = (float) Math.min(1.0, (start + xf - p) / xf);
            return sm.at(p) * (1f - t) + sm.at(p + len) * t;
        }
        return sm.at(p);
    }

    void render(float[] out, int n) {
        if (!active || sm == null) return;
        final int len = sm.length();
        if (len < 2) { active = false; return; }
        for (int i = 0; i < n; i++) {
            switch (stage) {
                case 0:
                    env += attInc;
                    if (env >= 1f) { env = 1f; stage = 1; }
                    break;
                case 1:
                    env = sus + (env - sus) * decMul;
                    if (Math.abs(env - sus) < 1e-4f) { env = sus; stage = 2; }
                    break;
                case 3:
                    env -= relStep;
                    if (env <= 0f) { active = false; return; }
                    break;
                default:
                    env += (sus - env) * 0.002f;      // moving the sustain slider glides, no steps
                    break;
            }
            if (glide > 0f) speed += (speedTgt - speed) * glide;
            if (dip == 1) {
                dipG -= DIP;
                if (dipG <= 0f) { dipG = 0f; pos = pendPos; dip = 2; }
            } else if (dip == 2) {
                dipG += DIP;
                if (dipG >= 1f) { dipG = 1f; dip = 0; }
            }
            float s;
            switch (kind) {
                case BOW: s = bowSample(); break;
                case TAPE: s = tapeSample(len); if (!active) return; break;
                case SCRATCH: s = scratchSample(len); break;
                default: s = playSample(); break;
            }
            out[i] += s * env * vel * dipG;
        }
    }

    private float playSample() {
        float s = loop ? readLoop(pos) : sm.at(pos);
        double inc = speed * ratio;
        if (!loop && !releasing && inc != 0) {
            double ai = Math.abs(inc);
            double rem = (inc > 0 ? end - pos : pos - start) / ai;           // frames left in the region
            double fade = Math.min(relFrames, Math.max(96.0, 0.5 * (end - start) / ai));
            if (rem <= fade) beginRelease(Math.max(48.0, rem));
        }
        pos += inc;
        if (dip != 0) return s;                    // a declick jump is already in progress
        double step = Math.abs(inc) * 2 + 2;       // a normal wrap overshoots by less than this
        double len = end - start;
        if (inc >= 0) {
            if (pos >= end) {
                if (loop) {
                    double over = pos - end;
                    if (over <= step || len <= 1) pos = start + over;
                    else jumpTo(start + (over % len));       // loop was shrunk past the playhead
                } else { pos = end; startRelease(); }
            }
        } else if (pos < start) {
            if (loop) {
                double under = start - pos;
                if (under <= step || len <= 1) pos = end - under;
                else jumpTo(end - (under % len));
            } else { pos = start; startRelease(); }
        }
        return s;
    }

    private void startRelease() {
        beginRelease(96.0);       // safety net: region ended before the timed fade finished
    }

    /** Granular "bow": two overlapping Hann grains. Grain size can change while playing without any jump. */
    private float bowSample() {
        float acc = 0f;
        for (int g = 0; g < 2; g++) {
            float ph = gPh[g];
            float w = (float) Math.sin(Math.PI * ph);
            w *= w;
            acc += sm.at(gPos[g]) * w;
            gPos[g] += ratio;                  // the read position advances by itself; only the window length changes
            ph += 1f / grainLen;
            if (ph >= 1f) {
                ph -= 1f;
                double jitter = (nextRand() - 0.5) * grainLen * ratio * 0.3;
                gPos[g] = center + jitter;     // new grain starts while its window is at zero
            }
            gPh[g] = ph;
        }
        return acc;
    }

    private float tapeSample(int len) {
        if (!held) {
            speed *= tapeMul; // tape stop: exponential slow-down, length follows the release time
            if (Math.abs(speed) < 0.01) { active = false; return 0f; }
        }
        float s = sm.at(pos);
        pos += speed * ratio;
        boolean edge = false;
        if (pos < 0) { pos = 0; edge = true; }
        if (pos > len - 1) { pos = len - 1; edge = true; }
        if (edge) { speed = 0; speedTgt = 0; if (!held) { active = false; return 0f; } }
        // fade out when the tape is (nearly) stopped so a frozen playhead never leaves a DC step
        float tgt = Math.min(1f, (float) (Math.abs(speed) / 0.05));
        tapeAmp += (tgt - tapeAmp) * 0.004f;
        return s * tapeAmp;
    }

    /**
     * Scratch: the playhead follows the finger through a critically damped spring, so the speed
     * is always smooth (no zipper noise from 60 Hz touch updates). Sound only while it moves.
     */
    private float scratchSample(int len) {
        final double w = 100.0 / engineRate;                 // spring frequency, rad per frame (~16 Hz: smooth, still tight)
        // touch updates arrive at 60-120 Hz; between them the target keeps moving at the finger's speed (for at
        // most one update interval), so the spring sees continuous motion instead of steps = no buzzing/modulation
        int ext = scrAge < scrDt ? scrAge : scrDt;
        scrAge++;
        double tgt = scrTarget + scrTv * ext;
        if (tgt < 0) tgt = 0; else if (tgt > len - 1) tgt = len - 1;
        double acc = w * w * (tgt - pos) - 2.0 * w * scrVel;
        scrVel += acc;
        pos += scrVel;
        if (pos < 0) { pos = 0; scrVel = 0; }
        if (pos > len - 1) { pos = len - 1; scrVel = 0; }
        float speedRel = (float) (Math.abs(scrVel) / ratio);   // 1.0 = normal playback speed
        float gTarget = Math.min(1f, speedRel / 0.08f);
        scratchAmp += (gTarget - scratchAmp) * (gTarget > scratchAmp ? 0.01f : 0.003f);   // quick open, slower close
        return sm.at(pos) * scratchAmp;
    }
}
