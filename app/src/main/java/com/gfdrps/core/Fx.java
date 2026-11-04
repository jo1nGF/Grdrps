package com.gfdrps.core;

/** The five per-slot effects (also reused for the master bus). All take x,y in 0..1 from an XY pad. */
public final class Fx {
    private Fx() {}

    public static final int DIST = 0, FILTER = 1, AMP = 2, DELAY = 3, REVERB = 4, COUNT = 5;
    public static final String[] NAMES = {"Distortion", "Filter", "Amp Mod", "Delay", "Reverb"};

    public abstract static class Effect {
        /** Pad position, 0..1. */
        public volatile float x = 0.5f, y = 0.5f;
        /** On while pad is touched or latched. */
        public volatile boolean active = false;
        protected final int rate;
        protected Effect(int rate) { this.rate = rate; }
        public abstract void process(float[] l, float[] r, int n, double bpm);
        public void setXY(float nx, float ny) {
            x = Math.max(0f, Math.min(1f, nx));
            y = Math.max(0f, Math.min(1f, ny));
        }
    }

    public static Effect create(int type, int rate) {
        switch (type) {
            case DIST: return new Distortion(rate);
            case FILTER: return new Filter(rate);
            case AMP: return new AmpMod(rate);
            case DELAY: return new Delay(rate);
            default: return new Reverb(rate);
        }
    }

    /** x = drive, y = tone/mix. */
    public static final class Distortion extends Effect {
        private float lpL, lpR;
        public Distortion(int rate) { super(rate); }
        @Override public void process(float[] l, float[] r, int n, double bpm) {
            if (!active) return;
            float drive = 1f + x * 24f;
            float comp = 1f / (float) Math.tanh(drive * 0.5f + 0.5f);
            float a = 0.08f + y * 0.9f; // tone: low-pass coefficient
            for (int i = 0; i < n; i++) {
                float sl = (float) Math.tanh(l[i] * drive) * comp * 0.7f;
                float sr = (float) Math.tanh(r[i] * drive) * comp * 0.7f;
                lpL += a * (sl - lpL);
                lpR += a * (sr - lpR);
                l[i] = lpL; r[i] = lpR;
            }
        }
    }

    /** x = cutoff (log 40 Hz..16 kHz), y = resonance. Low-pass; low half of y... see lowpass flag. */
    public static final class Filter extends Effect {
        public volatile boolean highPass = false;
        private float s1L, s2L, s1R, s2R;
        public Filter(int rate) { super(rate); }
        @Override public void process(float[] l, float[] r, int n, double bpm) {
            if (!active) return;
            double fc = 40.0 * Math.pow(400.0, x);
            fc = Math.min(fc, rate * 0.45);
            float g = (float) Math.tan(Math.PI * fc / rate);
            float k = 2f - 1.92f * y; // damping, y up = more resonance
            float a1 = 1f / (1f + g * (g + k));
            float a2 = g * a1;
            float a3 = g * a2;
            for (int i = 0; i < n; i++) {
                // Andrew Simper state-variable filter
                float v0 = l[i];
                float v3 = v0 - s2L;
                float v1 = a1 * s1L + a2 * v3;
                float v2 = s2L + a2 * s1L + a3 * v3;
                s1L = 2 * v1 - s1L; s2L = 2 * v2 - s2L;
                l[i] = highPass ? v0 - k * v1 - v2 : v2;
                v0 = r[i];
                v3 = v0 - s2R;
                v1 = a1 * s1R + a2 * v3;
                v2 = s2R + a2 * s1R + a3 * v3;
                s1R = 2 * v1 - s1R; s2R = 2 * v2 - s2R;
                r[i] = highPass ? v0 - k * v1 - v2 : v2;
            }
            if (Float.isNaN(s1L) || Float.isNaN(s2L) || Float.isNaN(s1R) || Float.isNaN(s2R)
                    || Math.abs(s1L) > 1e6f || Math.abs(s1R) > 1e6f) { s1L = s2L = s1R = s2R = 0; }
        }
    }

    /** x = rate (0.5..20 Hz exponential), y = depth. */
    public static final class AmpMod extends Effect {
        private double ph;
        public AmpMod(int rate) { super(rate); }
        @Override public void process(float[] l, float[] r, int n, double bpm) {
            if (!active) return;
            double f = 0.5 * Math.pow(40.0, x);
            double inc = 2 * Math.PI * f / rate;
            for (int i = 0; i < n; i++) {
                ph += inc;
                if (ph > 2 * Math.PI) ph -= 2 * Math.PI;
                float m = 1f - y * (0.5f + 0.5f * (float) Math.sin(ph));
                l[i] *= m; r[i] *= m;
            }
        }
    }

    /** x = time (note division synced to tempo), y = feedback. Wet mix is fixed. */
    public static final class Delay extends Effect {
        private static final double[] DIV = {0.125, 0.1875, 0.25, 0.375, 0.5, 0.75, 1.0}; // in beats
        private final float[] bl, br;
        private int w;
        private float cur;
        public Delay(int rate) {
            super(rate);
            bl = new float[rate * 3];
            br = new float[rate * 3];
        }
        @Override public void process(float[] l, float[] r, int n, double bpm) {
            int idx = Math.min(DIV.length - 1, (int) (x * DIV.length));
            double secs = DIV[idx] * 60.0 / Math.max(30.0, bpm);
            float target = (float) Math.min(secs * rate, bl.length - 2);
            float fb = 0.1f + 0.82f * y;
            for (int i = 0; i < n; i++) {
                cur += (target - cur) * 0.0005f; // glide to avoid clicks when time changes
                float rp = w - cur;
                while (rp < 0) rp += bl.length;
                int i0 = (int) rp;
                int i1 = i0 + 1 >= bl.length ? 0 : i0 + 1;
                float f = rp - i0;
                float dl = bl[i0] + (bl[i1] - bl[i0]) * f;
                float dr = br[i0] + (br[i1] - br[i0]) * f;
                float inL = active ? l[i] : 0f;
                float inR = active ? r[i] : 0f;
                bl[w] = softclip(inL + dr * fb);   // ping-pong
                br[w] = softclip(inR + dl * fb);
                w++; if (w >= bl.length) w = 0;
                if (active || Math.abs(dl) + Math.abs(dr) > 1e-5f) {
                    l[i] += dl * 0.8f;
                    r[i] += dr * 0.8f;
                }
            }
        }
        private static float softclip(float v) { return v > 1f ? 1f : (v < -1f ? -1f : v); }
    }

    /** Small Freeverb-style reverb. x = size, y = wet mix. */
    public static final class Reverb extends Effect {
        private static final int[] CT = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        private static final int[] AT = {556, 441, 341, 225};
        private final float[][] cb = new float[8][];
        private final int[] ci = new int[8];
        private final float[] cs = new float[8];
        private final float[][] ab = new float[4][];
        private final int[] ai = new int[4];
        public Reverb(int rate) {
            super(rate);
            float k = rate / 44100f;
            for (int i = 0; i < 8; i++) cb[i] = new float[Math.max(8, (int) (CT[i] * k))];
            for (int i = 0; i < 4; i++) ab[i] = new float[Math.max(8, (int) (AT[i] * k))];
        }
        @Override public void process(float[] l, float[] r, int n, double bpm) {
            float room = 0.7f + 0.28f * x;
            float damp = 0.25f;
            float wet = active ? 0.15f + 0.6f * y : 0f;
            for (int i = 0; i < n; i++) {
                float in = (l[i] + r[i]) * 0.5f * 0.03f;
                float acc = 0;
                for (int c = 0; c < 8; c++) {
                    float[] b = cb[c];
                    float o = b[ci[c]];
                    cs[c] = o * (1 - damp) + cs[c] * damp;
                    b[ci[c]] = in + cs[c] * room;
                    if (++ci[c] >= b.length) ci[c] = 0;
                    acc += o;
                }
                for (int a = 0; a < 4; a++) {
                    float[] b = ab[a];
                    float o = b[ai[a]];
                    float t = acc;
                    b[ai[a]] = t + o * 0.5f;
                    acc = o - t;
                    if (++ai[a] >= b.length) ai[a] = 0;
                }
                if (wet > 0 || Math.abs(acc) > 1e-6f) {
                    float w = wet > 0 ? wet : 0.3f;
                    l[i] += acc * w * 3f;
                    r[i] += acc * w * 3f;
                }
            }
        }
    }
}
