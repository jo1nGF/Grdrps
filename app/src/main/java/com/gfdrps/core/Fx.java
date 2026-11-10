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

    /** The LP/HP tone of a Delay or Reverb; null for the other effects. */
    public static Tone toneOf(Effect e) {
        if (e instanceof Delay) return ((Delay) e).tone;
        if (e instanceof Reverb) return ((Reverb) e).tone;
        return null;
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

    /** State variable filter used as the optional low-pass / high-pass on a delay's or reverb's wet signal. */
    public static final class Tone {
        /** 0 = off, 1 = low-pass, 2 = high-pass. */
        public volatile int mode = 0;
        /** 0..1 position on a 40 Hz .. 18 kHz exponential scale. */
        public volatile float cut = 0.5f;
        private float s1, s2, g = 0.1f;
        private final int rate;
        public Tone(int rate) { this.rate = rate; }
        public static double hz(float c, int rate) { return Math.min(40.0 * Math.pow(450.0, Math.max(0f, Math.min(1f, c))), rate * 0.45); }
        public double cutoffHz() { return hz(cut, rate); }
        public static final String[] NAMES = {"OFF", "LP", "HP"};
        /** Call once per block: recomputes the coefficient from the (smoothed) cutoff. */
        void block() {
            float gt = (float) Math.tan(Math.PI * cutoffHz() / rate);
            g += (gt - g) * 0.5f;
        }
        float run(float v0) {
            int m = mode;
            if (m == 0) { s1 = s2 = 0f; return v0; }
            final float k = 1.0f;
            float a1 = 1f / (1f + g * (g + k)), a2 = g * a1, a3 = g * a2;
            float v3 = v0 - s2;
            float v1 = a1 * s1 + a2 * v3;
            float v2 = s2 + a2 * s1 + a3 * v3;
            s1 = 2 * v1 - s1; s2 = 2 * v2 - s2;
            if (!(s1 == s1) || !(s2 == s2) || Math.abs(s1) > 1e6f) { s1 = s2 = 0f; return 0f; }
            return m == 1 ? v2 : v0 - k * v1 - v2;
        }
        void clear() { s1 = s2 = 0f; }
    }

    /** Feedback delay. x = time (10 ms..4 s in whole ms, or a tempo-synced note length with sync), y = feedback. Short times make comb filters; long times ping-pong. */
    public static final class Delay extends Effect {
        public static final double MIN_MS = 10.0, MAX_MS = 4000.0;
        /** Note lengths in beats for sync mode (1/32 .. 1 bar, straight and dotted). */
        public static final double[] DIV = {0.125, 0.1875, 0.25, 0.375, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0};
        public static final String[] DIV_NAMES = {"1/32", "1/32.", "1/16", "1/16.", "1/8", "1/8.", "1/4", "1/4.", "1/2", "1/2.", "1 bar"};
        /** true = x picks a note length locked to the project tempo; false = x is free time in milliseconds. */
        public volatile boolean sync = false;
        /** Delay time currently used, in ms (for the UI). */
        public volatile float shownMs = 250f;
        /** Optional low-pass / high-pass on the echoes (also inside the feedback loop, so repeats get darker or thinner). */
        public final Tone tone;
        private final Tone toneR;
        private final float[] bl, br;
        private int w;
        private float cur;
        public Delay(int rate) {
            super(rate);
            tone = new Tone(rate); toneR = new Tone(rate);
            int len = (int) (rate * (MAX_MS / 1000.0)) + 64;
            bl = new float[len];
            br = new float[len];
        }
        /** x (0..1) to milliseconds: exponential 10 ms .. 4000 ms, whole milliseconds (1 ms steps on the short side). */
        public static double msForX(double x) {
            double ms = MIN_MS * Math.pow(MAX_MS / MIN_MS, Math.max(0.0, Math.min(1.0, x)));
            return Math.round(ms);
        }
        public static int divIndex(double x) { return Math.min(DIV.length - 1, (int) (Math.max(0.0, x) * DIV.length)); }

        @Override public void process(float[] l, float[] r, int n, double bpm) {
            double ms;
            if (sync) ms = DIV[divIndex(x)] * 60000.0 / Math.max(30.0, bpm);
            else ms = msForX(x);
            ms = Math.max(MIN_MS, Math.min(MAX_MS, ms));
            shownMs = (float) ms;
            float target = (float) Math.min(ms * 0.001 * rate, bl.length - 3);
            if (cur == 0f) cur = target;
            final float slew = 0.0012f;                         // time changes glide (tape-style), never click
            // short times: straight feedback = a comb filter / metallic echo; longer times: ping-pong between L and R
            final float pp = (float) Math.max(0.0, Math.min(1.0, (ms - 60.0) / 90.0));
            float fb = (0.1f + 0.85f * y) * (active ? 1f : 0.55f);
            tone.block(); toneR.mode = tone.mode; toneR.cut = tone.cut; toneR.block();
            for (int i = 0; i < n; i++) {
                cur += (target - cur) * slew;
                float rp = w - cur;
                while (rp < 0) rp += bl.length;
                int i0 = (int) rp;
                int i1 = i0 + 1 >= bl.length ? 0 : i0 + 1;
                float f = rp - i0;
                float dl = bl[i0] + (bl[i1] - bl[i0]) * f;
                float dr = br[i0] + (br[i1] - br[i0]) * f;
                dl = tone.run(dl); dr = toneR.run(dr);
                float inL = active ? l[i] : 0f;
                float inR = active ? r[i] : 0f;
                bl[w] = clip(inL + (dl * (1f - pp) + dr * pp) * fb);
                br[w] = clip(inR + (dr * (1f - pp) + dl * pp) * fb);
                if (++w >= bl.length) w = 0;
                l[i] += dl * 0.8f;
                r[i] += dr * 0.8f;
            }
        }
        private static float clip(float v) { return v > 1f ? 1f : (v < -1f ? -1f : v); }
    }

    /**
     * "Cloud" reverb: a Dattorro-style plate. The input goes through a pre-delay and four dense
     * diffusers into a figure-eight tank of two cross-coupled branches, each with a slowly
     * modulated all-pass, a darkening low-pass and a second diffuser. The modulation and the
     * all-pass diffusion keep the tail smooth and free of ringing/pitched resonances, and the
     * output is taken from 14 taps spread over both branches for a wide, decorrelated stereo image.
     *
     * x = decay time (0.6 s .. 14 s), y = wet level. It only listens while the pad is active; after
     * release the tail rings out naturally (at most ~2.5 s) and then goes completely silent.
     */
    public static final class Reverb extends Effect {
        /** Optional low-pass / high-pass on the wet signal. */
        public final Tone tone, toneR;
        // all lengths in samples at 29761 Hz (Dattorro's reference rate), scaled to the engine rate
        private static final double REF = 29761.0;
        private static final int[] IN_AP = {142, 107, 379, 277};
        private static final float[] IN_G = {0.75f, 0.75f, 0.625f, 0.625f};

        private static final class Line {
            final float[] b; int w;
            Line(int len) { b = new float[Math.max(4, len)]; }
            /** Oldest sample: the output of a delay of b.length samples. Call push() right after. */
            float oldest() { return b[w]; }
            void push(float v) { b[w] = v; if (++w >= b.length) w = 0; }
            /** Sample written `t` samples ago (0 = most recent). */
            float tap(int t) {
                int i = w - 1 - Math.min(t, b.length - 1);
                if (i < 0) i += b.length;
                return b[i];
            }
            void clear() { java.util.Arrays.fill(b, 0f); w = 0; }
        }

        private final Line[] inAp = new Line[4];
        private final Line pre;
        private final Line apL, apR;          // modulated decay diffusers
        private final Line d1L, d1R;          // first delays
        private final Line ap2L, ap2R;        // second diffusers
        private final Line d2L, d2R;          // second delays
        private final int[] tapL = new int[7], tapR = new int[7];
        private final float modDepth;
        private final int apLBase, apRBase;
        private double lfoL, lfoR, lfo2L, lfo2R;
        private final double lfoIncL, lfoIncR, lfo2IncL, lfo2IncR;
        private final int ap2LBase, ap2RBase;
        private final float mod2Depth;
        private float lpIn, lpL, lpR, dcIn;
        private float rt60 = 2f, wet = 0f, decay = 0.5f;
        private boolean clean = true;
        private final float loopSeconds;

        private static int sc(int v, double k) { return Math.max(2, (int) Math.round(v * k)); }

        public Reverb(int rate) {
            super(rate);
            tone = new Tone(rate); toneR = new Tone(rate);
            double k = rate / REF;
            for (int i = 0; i < 4; i++) inAp[i] = new Line(sc(IN_AP[i], k));
            pre = new Line(Math.max(8, (int) (0.022 * rate)));
            modDepth = (float) (14 * k);
            apLBase = sc(672, k); apRBase = sc(908, k);
            apL = new Line(apLBase + (int) modDepth + 4);
            apR = new Line(apRBase + (int) modDepth + 4);
            d1L = new Line(sc(4453, k)); d1R = new Line(sc(4217, k));
            mod2Depth = (float) (10 * k);
            ap2LBase = sc(1800, k); ap2RBase = sc(2656, k);
            ap2L = new Line(ap2LBase + (int) mod2Depth + 4); ap2R = new Line(ap2RBase + (int) mod2Depth + 4);
            d2L = new Line(sc(3720, k)); d2R = new Line(sc(3163, k));
            // output taps (Dattorro): read inside the opposite/own branch
            int[] tl = {266, 2974, 1913, 1996, 1990, 187, 1066};
            int[] tr = {353, 3627, 1228, 2673, 2111, 335, 121};
            for (int i = 0; i < 7; i++) { tapL[i] = sc(tl[i], k); tapR[i] = sc(tr[i], k); }
            lfoIncL = 2 * Math.PI * 0.43 / rate;
            lfoIncR = 2 * Math.PI * 0.61 / rate;
            lfo2IncL = 2 * Math.PI * 0.29 / rate;
            lfo2IncR = 2 * Math.PI * 0.37 / rate;
            lfoR = 1.9; lfo2L = 0.7; lfo2R = 3.4;
            loopSeconds = (float) ((672 + 4453 + 1800 + 3720 + 908 + 4217 + 2656 + 3163) / REF);
        }

        private void clear() {
            for (Line l : inAp) l.clear();
            pre.clear(); apL.clear(); apR.clear(); d1L.clear(); d1R.clear();
            ap2L.clear(); ap2R.clear(); d2L.clear(); d2R.clear();
            lpIn = lpL = lpR = dcIn = 0f; wet = 0f;
            tone.clear(); toneR.clear();
            clean = true;
        }

        private static float frac(Line l, float d) {
            // fractional read, d samples behind the newest sample
            int i0 = (int) d;
            float f = d - i0;
            float a = l.tap(i0), b = l.tap(i0 + 1);
            return a + (b - a) * f;
        }

        @Override public void process(float[] l, float[] r, int n, double bpm) {
            if (!active && wet < 1e-4f) {
                if (!clean) clear();
                return;
            }
            clean = false;
            tone.block(); toneR.mode = tone.mode; toneR.cut = tone.cut; toneR.block();
            if (active) {
                float rtT = 0.6f * (float) Math.pow(24.0, x);
                rt60 += (rtT - rt60) * 0.05f;
            }
            // calibrated so the measured RT60 matches the requested one
            decay = (float) Math.min(0.985, Math.pow(10.0, -3.0 * loopSeconds / (4.0 * Math.max(0.2, rt60))));
            final float dd2 = Math.max(0.25f, Math.min(0.5f, decay + 0.15f));
            final float wetT = active ? (float) (1.25 * Math.pow(y, 1.3)) : 0f;
            final float tail = Math.min(2.5f, Math.max(0.5f, rt60));
            final float wetFall = (float) Math.exp(-9.2 / (tail * rate));      // -80 dB over `tail` seconds
            final float damp = 0.5f;                                           // dark, soft tail
            final float bw = 0.8f;
            final float gMod = 0.7f;
            for (int i = 0; i < n; i++) {
                if (active) wet += (wetT - wet) * 0.002f; else wet *= wetFall;

                float in = active ? (l[i] + r[i]) * 0.5f : 0f;
                float hp = in - dcIn; dcIn += 0.003f * hp; in = hp;
                float pd = pre.oldest(); pre.push(in);
                lpIn += bw * (pd - lpIn);
                float s = lpIn;
                for (int a = 0; a < 4; a++) {
                    Line ln = inAp[a];
                    float d = ln.oldest();
                    float v = s - IN_G[a] * d;
                    ln.push(v);
                    s = IN_G[a] * v + d;
                }

                float feedL = d2R.oldest() * decay;      // each branch feeds the other
                float feedR = d2L.oldest() * decay;

                // ---- left branch
                lfoL += lfoIncL; if (lfoL > 6.283185307179586) lfoL -= 6.283185307179586;
                float dm = apLBase + modDepth * (float) Math.sin(lfoL);
                float d = frac(apL, dm);
                float v = (s + feedL) - gMod * d;
                apL.push(v);
                float y1 = gMod * v + d;
                float o1 = d1L.oldest(); d1L.push(y1);
                lpL += damp * (o1 - lpL);
                float z = lpL * decay;
                lfo2L += lfo2IncL; if (lfo2L > 6.283185307179586) lfo2L -= 6.283185307179586;
                float o2 = frac(ap2L, ap2LBase + mod2Depth * (float) Math.sin(lfo2L));
                float v2 = z - dd2 * o2;
                ap2L.push(v2);
                d2L.push(dd2 * v2 + o2);

                // ---- right branch
                lfoR += lfoIncR; if (lfoR > 6.283185307179586) lfoR -= 6.283185307179586;
                dm = apRBase + modDepth * (float) Math.sin(lfoR);
                d = frac(apR, dm);
                v = (s + feedR) - gMod * d;
                apR.push(v);
                y1 = gMod * v + d;
                o1 = d1R.oldest(); d1R.push(y1);
                lpR += damp * (o1 - lpR);
                z = lpR * decay;
                lfo2R += lfo2IncR; if (lfo2R > 6.283185307179586) lfo2R -= 6.283185307179586;
                o2 = frac(ap2R, ap2RBase + mod2Depth * (float) Math.sin(lfo2R));
                v2 = z - dd2 * o2;
                ap2R.push(v2);
                d2R.push(dd2 * v2 + o2);

                float outL = d1R.tap(tapL[0]) + d1R.tap(tapL[1]) - ap2R.tap(tapL[2]) + d2R.tap(tapL[3])
                        - d1L.tap(tapL[4]) - ap2L.tap(tapL[5]) - d2L.tap(tapL[6]);
                float outR = d1L.tap(tapR[0]) + d1L.tap(tapR[1]) - ap2L.tap(tapR[2]) + d2L.tap(tapR[3])
                        - d1R.tap(tapR[4]) - ap2R.tap(tapR[5]) - d2R.tap(tapR[6]);
                l[i] += tone.run(outL) * wet * 0.6f;
                r[i] += toneR.run(outR) * wet * 0.6f;
            }
            if (!(lpL == lpL) || !(lpR == lpR) || Math.abs(lpL) > 1e4f || Math.abs(lpR) > 1e4f) clear();
        }
    }
}
