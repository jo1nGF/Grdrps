package com.gfdrps.core;

/** One playing voice of a slot. Rendering is mode-specific; all positions are in sample frames. */
final class Voice {
    static final int PLAY = 0, BOW = 1, TAPE = 2, SCRATCH = 3;

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
    double start, end;
    boolean loop;
    float vel = 1f, env = 0f;
    float attInc = 1f, relInc = 0.001f;

    // bow
    double center;
    float grainLen = 2000f;
    final double[] gStart = new double[2];
    final float[] gPh = {0f, 0.5f};
    int rnd = 12345;

    // tape / scratch
    double target;
    float scratchAmp;
    double baseSpeed = 1.0;
    boolean decayOnRelease;

    void setEnv(float attackSec, float releaseSec) {
        attInc = 1f / Math.max(24f, attackSec * engineRate);
        relInc = 1f / Math.max(96f, releaseSec * engineRate);
    }

    void trigger(int id, long frame) {
        active = true;
        held = true;
        releasing = false;
        decayOnRelease = false;
        scratchAmp = 0f;
        touchId = id;
        startedAt = frame;
        env = attInc >= 1f ? 1f : 0f;
    }

    void release() {
        held = false;
        if (kind == TAPE) { decayOnRelease = true; }
        releasing = !decayOnRelease;
        if (releasing && relInc > 0.5f) relInc = 0.5f;
    }

    void kill() { active = false; releasing = false; held = false; }

    private float nextRand() {
        rnd = rnd * 1664525 + 1013904223;
        return ((rnd >>> 8) & 0xFFFF) / 65536f;
    }

    private float readLoop(double p) {
        double len = end - start;
        double xf = Math.min(len * 0.25, engineRate * 0.004);
        if (xf > 1 && p > end - xf) {
            float t = (float) ((p - (end - xf)) / xf);
            return sm.at(p) * (1f - t) + sm.at(p - len) * t;
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
            float s;
            switch (kind) {
                case BOW: s = bowSample(); break;
                case TAPE: s = tapeSample(len); if (!active) return; break;
                case SCRATCH: s = scratchSample(len); break;
                default: s = playSample(); break;
            }
            out[i] += s * env * vel;
        }
    }

    private float playSample() {
        float s = loop ? readLoop(pos) : sm.at(pos);
        pos += speed * ratio;
        if (speed >= 0) {
            if (pos >= end) {
                if (loop) pos = start + (pos - end);
                else { pos = end; startRelease(); }
            }
        } else if (pos < start) {
            if (loop) pos = end - (start - pos);
            else { pos = start; startRelease(); }
        }
        return s;
    }

    private void startRelease() {
        if (!releasing) {
            releasing = true;
            relInc = Math.max(relInc, 1f / (engineRate * 0.004f));
        }
    }

    private float bowSample() {
        float acc = 0f;
        for (int g = 0; g < 2; g++) {
            float ph = gPh[g];
            float w = (float) Math.sin(Math.PI * ph);
            w *= w;
            acc += sm.at(gStart[g] + ph * grainLen * ratio) * w;
            ph += 1f / grainLen;
            if (ph >= 1f) {
                ph -= 1f;
                double jitter = (nextRand() - 0.5) * grainLen * ratio * 0.3;
                gStart[g] = center + jitter;
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
        if (pos < 0) { pos = 0; speed = -speed * 0.3; }
        if (pos > len - 1) { pos = len - 1; if (!held) { active = false; } else speed = -speed * 0.3; }
        return s;
    }

    private float scratchSample(int len) {
        double tp = target * (len - 1);
        double d = tp - pos;
        double step = d * 0.004; // ~5ms smoothing at 48k
        pos += step;
        float sp = (float) Math.min(1.0, Math.abs(step) * 90.0 / ratio / 8.0);
        scratchAmp += (sp - scratchAmp) * 0.01f;
        return sm.at(pos) * Math.min(1f, scratchAmp * 4f);
    }
}
