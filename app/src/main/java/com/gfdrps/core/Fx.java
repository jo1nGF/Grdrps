package com.gfdrps.core;

/** The five per-slot effects (also reused for the master bus). All take x,y in 0..1 from an XY pad. */
public final class Fx {
    private Fx() {}

    public static final int DIST = 0, FILTER = 1, AMP = 2, DELAY = 3, REVERB = 4, COUNT = 5;
    public static final String[] NAMES = {"Distortion", "Filter", "Amp Mod", "Delay", "Reverb"};
    static final int MAXN = 512;

    /**
     * Insert effects implement apply(); the base class cross-fades them in and out in ~10 ms
     * so switching a pad on or off never clicks. Delay and Reverb override process() because
     * their tails must keep ringing after the pad is released.
     */
    public abstract static class Effect {
        public volatile float x = 0.5f, y = 0.5f;
        public volatile boolean active = false;
        protected final int rate;
        protected float mix;
        private final float[] dl = new float[MAXN], dr = new float[MAXN];

        protected Effect(int rate) { this.rate = rate; }

        protected void apply(float[] l, float[] r, int n, double bpm) {}
        protected void reset() {}

        public void process(float[] l, float[] r, int n, double bpm) {
            if (!active && mix <= 0.0005f) {
                if (mix != 0f) { mix = 0f; reset(); }
                return;
            }
            System.arraycopy(l, 0, dl, 0, n);
            System.arraycopy(r, 0, dr, 0, n);
            apply(l, r, n, bpm);
            final float t = active ? 1f : 0f;
            for (int i = 0; i < n; i++) {
                mix += (t - mix) * 0.003f;
                l[i] = dl[i] + (l[i] - dl[i]) * mix;
                r[i] = dr[i] + (r[i] - dr[i]) * mix;
            }
        }

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

    /** x = drive, y = tone. */
    public static final class Distortion extends Effect {
        private float lpL, lpR;
        public Distortion(int rate) { super(rate); }
        @Override protected void reset() { lpL = lpR = 0f; }
        @Override protected void apply(float[] l, float[] r, int n, double bpm) {
            float drive = 1f + x * 24f;
            float comp = 1f / (float) Math.tanh(drive * 0.5f + 0.5f);
            float a = 0.08f + y * 0.9f;
            for (int i = 0; i < n; i++) {
                float sl = (float) Math.tanh(l[i] * drive) * comp * 0.7f;
                float sr = (float) Math.tanh(r[i] * drive) * comp * 0.7f;
                lpL += a * (sl - lpL);
                lpR += a * (sr - lpR);
                l[i] = lpL; r[i] = lpR;
            }
        }
    }

    /** Resonant state-variable filter. x = cutoff (40 Hz..16 kHz, log), y = resonance. Low-pass or high-pass. */
    public static final class Filter extends Effect {
        public volatile boolean highPass = false;
        private float s1L, s2L, s1R, s2R;
        public Filter(int rate) { super(rate); }
        /** Damping used by the filter (2 = no resonance, 0.08 = strong); the pad uses it to draw the response. */
        public float damping() { return 2f - 1.92f * y; }
        public double cutoffHz() { return Math.min(40.0 * Math.pow(400.0, x), rate * 0.45); }
        @Override protected void reset() { s1L = s2L = s1R = s2R = 0f; }
        @Override protected void apply(float[] l, float[] r, int n, double bpm) {
            float g = (float) Math.tan(Math.PI * cutoffHz() / rate);
            float k = damping();
            float a1 = 1f / (1f + g * (g + k));
            float a2 = g * a1;
            float a3 = g * a2;
            final boolean hp = highPass;
            for (int i = 0; i < n; i++) {
                float v0 = l[i];
                float v3 = v0 - s2L;
                float v1 = a1 * s1L + a2 * v3;
                float v2 = s2L + a2 * s1L + a3 * v3;
                s1L = 2 * v1 - s1L; s2L = 2 * v2 - s2L;
                l[i] = hp ? v0 - k * v1 - v2 : v2;
                v0 = r[i];
                v3 = v0 - s2R;
                v1 = a1 * s1R + a2 * v3;
                v2 = s2R + a2 * s1R + a3 * v3;
                s1R = 2 * v1 - s1R; s2R = 2 * v2 - s2R;
                r[i] = hp ? v0 - k * v1 - v2 : v2;
            }
            if (Float.isNaN(s1L) || Float.isNaN(s2L) || Float.isNaN(s1R) || Float.isNaN(s2R)
                    || Math.abs(s1L) > 1e6f || Math.abs(s1R) > 1e6f) { s1L = s2L = s1R = s2R = 0f; }
        }
    }

    /** x = rate (0.5..20 Hz), y = depth. */
    public static final class AmpMod extends Effect {
        private double ph;
        public AmpMod(int rate) { super(rate); }
        @Override protected void apply(float[] l, float[] r, int n, double bpm) {
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

    /** Ping-pong feedback delay. x = time (note division synced to tempo), y = feedback. */
    public static final class Delay extends Effect {
        private static final double[] DIV = {0.125, 0.1875, 0.25, 0.375, 0.5, 0.75, 1.0};
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
            if (cur == 0f) cur = target;
            float fb = (0.1f + 0.82f * y) * (active ? 1f : 0.55f);
            for (int i = 0; i < n; i++) {
                cur += (target - cur) * 0.0005f;
                float rp = w - cur;
                while (rp < 0) rp += bl.length;
                int i0 = (int) rp;
                int i1 = i0 + 1 >= bl.length ? 0 : i0 + 1;
                float f = rp - i0;
                float dl = bl[i0] + (bl[i1] - bl[i0]) * f;
                float dr = br[i0] + (br[i1] - br[i0]) * f;
                float inL = active ? l[i] : 0f;
                float inR = active ? r[i] : 0f;
                bl[w] = clip(inL + dr * fb);
                br[w] = clip(inR + dl * fb);
                if (++w >= bl.length) w = 0;
                l[i] += dl * 0.8f;
                r[i] += dr * 0.8f;
            }
        }
        private static float clip(float v) { return v > 1f ? 1f : (v < -1f ? -1f : v); }
    }

    /**
     * Hall/plate reverb: 4 input diffusers into an 8-line feedback delay network with a Householder
     * matrix, slowly modulated delay lines (no metallic ringing), frequency-dependent damping and a
     * decorrelated stereo output. x = decay time (0.4 s .. 8 s), y = wet level.
     * Only listens while the pad is active; when released the tail is shortened and fades to
     * absolute silence (no CPU use, no residual reverb).
     */
    public static final class Reverb extends Effect {
        private static final int N = 8;
        private static final int[] BASE = {1087, 1283, 1429, 1597, 1783, 1993, 2237, 2459};   // at 48 kHz
        private static final int[] DIFF = {142, 107, 379, 277};
        private static final float[] SL = {1, 1, -1, -1, 1, 1, -1, -1};
        private static final float[] SR = {1, -1, 1, -1, -1, 1, -1, 1};

        private final float[][] line = new float[N][];
        private final int[] len = new int[N];
        private final int[] wp = new int[N];
        private final float[] lp = new float[N], gain = new float[N];
        private final double[] lfoPh = new double[N], lfoInc = new double[N];
        private final float[][] dif = new float[4][];
        private final int[] difW = new int[4];
        private final int[] difLen = new int[4];
        private final float[] pre;
        private int preW, preLen;
        private final float modDepth;
        private float rt60 = 1.5f, wet = 0f;
        private boolean clean = true;
        private float dcIn, dcOutL, dcOutR;

        public Reverb(int rate) {
            super(rate);
            float k = rate / 48000f;
            modDepth = Math.max(2f, 9f * k);
            for (int i = 0; i < N; i++) {
                len[i] = Math.max(16, (int) (BASE[i] * k));
                line[i] = new float[len[i] + (int) modDepth + 8];
                lfoInc[i] = 2 * Math.PI * (0.11 + 0.037 * i) / rate;
                lfoPh[i] = i * 0.9;
            }
            for (int i = 0; i < 4; i++) {
                difLen[i] = Math.max(8, (int) (DIFF[i] * k));
                dif[i] = new float[difLen[i]];
            }
            preLen = Math.max(8, (int) (0.012 * rate));
            pre = new float[preLen];
        }

        @Override protected void reset() {}

        private void clear() {
            for (int i = 0; i < N; i++) { java.util.Arrays.fill(line[i], 0f); lp[i] = 0f; }
            for (int i = 0; i < 4; i++) java.util.Arrays.fill(dif[i], 0f);
            java.util.Arrays.fill(pre, 0f);
            wet = 0f; dcIn = dcOutL = dcOutR = 0f;
            clean = true;
        }

        @Override public void process(float[] l, float[] r, int n, double bpm) {
            if (!active && wet < 1e-4f) {
                if (!clean) clear();
                return;
            }
            clean = false;
            final float rtT = active ? 0.4f * (float) Math.pow(20.0, x) : 0.35f;
            rt60 += (rtT - rt60) * (active ? 0.05f : 0.25f);
            final float wetT = active ? (float) (1.1 * Math.pow(y, 1.4)) : 0f;
            // per-line feedback gain for the requested RT60 (-60 dB after rt60 seconds)
            for (int i = 0; i < N; i++) gain[i] = (float) Math.pow(10.0, -3.0 * len[i] / (rate * (double) rt60));
            // darker tail for long decays
            final float damp = 0.62f - 0.22f * (rt60 / 8f);
            final float inGain = 0.35f;
            final float mod = modDepth;
            for (int i = 0; i < n; i++) {
                if (active) wet += (wetT - wet) * 0.002f;
                else wet *= 0.99985f;
                // input: mono, high-passed a little, pre-delay, diffusion
                float in = active ? (l[i] + r[i]) * 0.5f : 0f;
                float hp = in - dcIn; dcIn += 0.002f * hp; in = hp;
                float pd = pre[preW]; pre[preW] = in; if (++preW >= preLen) preW = 0;
                float s = pd;
                for (int a = 0; a < 4; a++) {
                    float[] b = dif[a];
                    float o = b[difW[a]];
                    float v = s - 0.6f * o;
                    b[difW[a]] = v;
                    s = o + 0.6f * v;
                    if (++difW[a] >= difLen[a]) difW[a] = 0;
                }
                // read the 8 delay lines with modulated fractional delay
                float sum = 0f, oL = 0f, oR = 0f;
                final float[] y8 = tmp;
                for (int c = 0; c < N; c++) {
                    lfoPh[c] += lfoInc[c];
                    if (lfoPh[c] > 6.283185307179586) lfoPh[c] -= 6.283185307179586;
                    float d = len[c] + mod * (float) Math.sin(lfoPh[c]);
                    float rp = wp[c] - d;
                    float[] b = line[c];
                    if (rp < 0) rp += b.length;
                    int i0 = (int) rp;
                    int i1 = i0 + 1 >= b.length ? 0 : i0 + 1;
                    float fr = rp - i0;
                    float o = b[i0] + (b[i1] - b[i0]) * fr;
                    lp[c] += damp * (o - lp[c]);
                    float g = lp[c] * gain[c];
                    y8[c] = g;
                    sum += g;
                    oL += SL[c] * o;
                    oR += SR[c] * o;
                }
                float h = sum * (2f / N);
                for (int c = 0; c < N; c++) {
                    float[] b = line[c];
                    float inj = (c & 1) == 0 ? s : -s;
                    b[wp[c]] = y8[c] - h + inj * inGain;
                    if (++wp[c] >= b.length) wp[c] = 0;
                }
                float outL = oL * 0.7f, outR = oR * 0.7f;
                l[i] += outL * wet;
                r[i] += outR * wet;
            }
            for (int c = 0; c < N; c++) if (!(lp[c] == lp[c]) || Math.abs(lp[c]) > 1e4f) { clear(); break; }
        }

        private final float[] tmp = new float[N];
    }
}
