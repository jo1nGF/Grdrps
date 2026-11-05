package com.gfdrps.core;

/** One playing voice of a slot. Rendering is mode-specific; all positions are in sample frames. */
final class Voice {
    static final int PLAY = 0, BOW = 1, TAPE = 2, SCRATCH = 3;
    private static final float DIP = 1f / 96f;   // 2 ms declick when a voice has to jump

    volatile boolean active;
    boolean held;          // finger still down
    boolean releasing;
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
    float attInc = 1f, relInc = 0.001f;

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
    float scratchAmp, tapeAmp;
    double baseSpeed = 1.0;
    boolean decayOnRelease;

    void setEnv(float attackSec, float releaseSec) {
        attInc = 1f / Math.max(96f, attackSec * engineRate);      // never shorter than 2 ms
        relInc = 1f / Math.max(240f, releaseSec * engineRate);    // never shorter than 5 ms
    }

    void trigger(int id, long frame) {
        active = true;
        held = true;
        releasing = false;
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

    void release() {
        held = false;
        glide = 0f;
        if (kind == TAPE) { decayOnRelease = true; }
        releasing = !decayOnRelease;
    }

    /** Fast fade-out, used when a voice is stolen. */
    void fastRelease() {
        held = false;
        glide = 0f;
        decayOnRelease = false;
        releasing = true;
        relInc = Math.max(relInc, 1f / 240f);
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
            if (releasing) {
                env -= relInc;
                if (env <= 0f) { active = false; return; }
            } else if (env < 1f) {
                env += attInc;
                if (env > 1f) env = 1f;
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
        if (!releasing) {
            releasing = true;
            relInc = Math.max(relInc, 1f / 240f);
        }
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
            speed *= 0.99985; // tape stop: exponential slow-down
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
        final double w = 150.0 / engineRate;                 // spring frequency, rad per frame
        double acc = w * w * (scrTarget - pos) - 2.0 * w * scrVel;
        scrVel += acc;
        pos += scrVel;
        if (pos < 0) { pos = 0; scrVel = 0; }
        if (pos > len - 1) { pos = len - 1; scrVel = 0; }
        float speedRel = (float) (Math.abs(scrVel) / ratio);   // 1.0 = normal playback speed
        float gTarget = Math.min(1f, speedRel / 0.12f);
        scratchAmp += (gTarget - scratchAmp) * 0.004f;
        return sm.at(pos) * scratchAmp;
    }
}
