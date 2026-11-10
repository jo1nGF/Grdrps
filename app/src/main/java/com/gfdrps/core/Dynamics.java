package com.gfdrps.core;

/** Master bus dynamics: a gentle stereo-linked compressor followed by a look-ahead peak limiter. */
public final class Dynamics {
    public volatile boolean compOn = false, limOn = false;
    /** 0..1: how hard the compressor works (threshold -6..-30 dB, ratio 2..8:1). */
    public volatile float amount = 0.4f;
    /** Limiter ceiling in dB (-6..0). */
    public volatile float ceilingDb = -0.5f;
    /** Gain reduction shown by the UI in dB (0 = none). */
    public volatile float reductionDb;

    private final int rate;
    private float env, cgain = 1f, lgain = 1f;
    private final int look;
    private final float[] dl, dr;
    private int wp;

    public Dynamics(int rate) {
        this.rate = rate;
        look = Math.max(8, rate / 1000 * 2);               // 2 ms look-ahead
        dl = new float[look + 1]; dr = new float[look + 1];
    }

    public void process(float[] l, float[] r, int n) {
        final boolean comp = compOn, lim = limOn;
        if (!comp && !lim) { reductionDb = 0f; return; }
        final float amt = Math.max(0f, Math.min(1f, amount));
        final float thr = -6f - 24f * amt, ratio = 2f + 6f * amt;
        final float atk = 1f - (float) Math.exp(-1.0 / (0.010 * rate)), rel = 1f - (float) Math.exp(-1.0 / (0.150 * rate));
        final float makeup = comp ? (float) Math.pow(10.0, (-thr * (1f - 1f / ratio) * 0.45f) / 20.0) : 1f;
        final float ceil = (float) Math.pow(10.0, Math.min(0f, Math.max(-12f, ceilingDb)) / 20.0);
        final float latk = 1f - (float) Math.exp(-1.0 / (0.0007 * rate)), lrel = 1f - (float) Math.exp(-1.0 / (0.080 * rate));
        float minG = 1f;
        for (int i = 0; i < n; i++) {
            float a = l[i], b = r[i];
            if (comp) {
                float pk = Math.max(Math.abs(a), Math.abs(b));
                env += (pk - env) * (pk > env ? atk : rel);
                float db = 20f * (float) Math.log10(Math.max(1e-6f, env));
                float over = db - thr, red = 0f;
                if (over > 3f) red = over * (1f - 1f / ratio);                     // above the knee
                else if (over > -3f) { float t = over + 3f; red = (t * t / 12f) * (1f - 1f / ratio); }   // soft knee, 6 dB wide
                float tg = (float) Math.pow(10.0, -red / 20.0) * makeup;
                cgain += (tg - cgain) * 0.02f;
                a *= cgain; b *= cgain;
                minG = Math.min(minG, cgain / makeup);
            }
            if (lim) {
                dl[wp] = a; dr[wp] = b;
                float need = Math.max(Math.abs(a), Math.abs(b));
                float tg = need > ceil ? ceil / need : 1f;
                if (tg < lgain) lgain += (tg - lgain) * latk * 6f; else lgain += (tg - lgain) * lrel;
                if (lgain > 1f) lgain = 1f;
                if (++wp > look) wp = 0;
                float oa = dl[wp] * lgain, ob = dr[wp] * lgain;                    // delayed by the look-ahead
                if (oa > ceil) oa = ceil; else if (oa < -ceil) oa = -ceil;
                if (ob > ceil) ob = ceil; else if (ob < -ceil) ob = -ceil;
                l[i] = oa; r[i] = ob;
                minG = Math.min(minG, lgain);
            } else { l[i] = a; r[i] = b; }
        }
        reductionDb = minG >= 0.999f ? 0f : -20f * (float) Math.log10(minG);
    }
}
