package com.gfdrps.core;

import java.util.Random;

/** Generates built-in demo samples so the app makes sound with no files imported. */
public final class DemoSynth {
    private DemoSynth() {}

    public static final String[] NAMES = {"Drum Loop", "Pad", "Pluck Seq", "Noise Sweep", "Bass", "Vox Chop"};

    public static Sample make(int index, int rate, double bpm) {
        switch (index % 6) {
            case 0: return drums(rate, bpm);
            case 1: return pad(rate);
            case 2: return pluck(rate, bpm);
            case 3: return sweep(rate);
            case 4: return bass(rate, bpm);
            default: return chops(rate, bpm);
        }
    }

    private static Sample drums(int rate, double bpm) {
        double beat = 60.0 / bpm;
        int n = (int) (rate * beat * 8);
        float[] d = new float[n];
        Random r = new Random(7);
        for (int step = 0; step < 16; step++) {
            int off = (int) (rate * beat * step / 2.0);
            boolean kick = step % 4 == 0 || step == 10;
            boolean snare = step == 4 || step == 12;
            boolean hat = step % 2 == 1 || step % 4 == 2;
            if (kick) {
                int len = (int) (rate * 0.28);
                double ph = 0;
                for (int i = 0; i < len && off + i < n; i++) {
                    double t = i / (double) rate;
                    double f = 45 + 110 * Math.exp(-t * 28);
                    ph += 2 * Math.PI * f / rate;
                    d[off + i] += (float) (Math.sin(ph) * Math.exp(-t * 9) * 0.9);
                }
            }
            if (snare) {
                int len = (int) (rate * 0.2);
                for (int i = 0; i < len && off + i < n; i++) {
                    double t = i / (double) rate;
                    d[off + i] += (float) ((r.nextFloat() * 2 - 1) * Math.exp(-t * 20) * 0.55
                            + Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 25) * 0.3);
                }
            }
            if (hat) {
                int len = (int) (rate * 0.05);
                float prev = 0;
                for (int i = 0; i < len && off + i < n; i++) {
                    double t = i / (double) rate;
                    float x = r.nextFloat() * 2 - 1;
                    float hp = x - prev; prev = x;
                    d[off + i] += (float) (hp * Math.exp(-t * 70) * 0.25);
                }
            }
        }
        return norm(d, rate, NAMES[0]);
    }

    private static Sample pad(int rate) {
        int n = rate * 8;
        float[] d = new float[n];
        double[] notes = {110.0, 138.59, 164.81, 220.0};
        for (double f : notes) {
            for (int v = 0; v < 3; v++) {
                double det = 1 + (v - 1) * 0.004;
                double ph = v * 1.3;
                for (int i = 0; i < n; i++) {
                    ph += 2 * Math.PI * f * det / rate;
                    double saw = (ph / Math.PI) % 2.0 - 1.0;
                    d[i] += (float) (saw * 0.08);
                }
            }
        }
        float lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double env = Math.sin(Math.PI * t);
            float a = (float) (0.02 + 0.1 * env);
            lp += a * (d[i] - lp);
            d[i] = lp * (float) (0.3 + 0.7 * env);
        }
        return norm(d, rate, NAMES[1]);
    }

    private static Sample pluck(int rate, double bpm) {
        double beat = 60.0 / bpm;
        int n = (int) (rate * beat * 8);
        float[] d = new float[n];
        double[] seq = {220, 261.63, 329.63, 392, 329.63, 261.63, 293.66, 349.23};
        for (int s = 0; s < 16; s++) {
            double f = seq[s % seq.length] * ((s / 8) == 1 ? 1.5 : 1.0);
            int off = (int) (rate * beat * s / 2.0);
            int len = (int) (rate * 0.6);
            for (int i = 0; i < len && off + i < n; i++) {
                double t = i / (double) rate;
                double v = Math.sin(2 * Math.PI * f * t) + 0.4 * Math.sin(4 * Math.PI * f * t) * Math.exp(-t * 14);
                d[off + i] += (float) (v * Math.exp(-t * 6) * 0.35);
            }
        }
        return norm(d, rate, NAMES[2]);
    }

    private static Sample sweep(int rate) {
        int n = rate * 6;
        float[] d = new float[n];
        Random r = new Random(3);
        float lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            float a = (float) (0.005 + 0.4 * t * t);
            lp += a * ((r.nextFloat() * 2 - 1) - lp);
            d[i] = lp * (float) (0.3 + t);
        }
        return norm(d, rate, NAMES[3]);
    }

    private static Sample bass(int rate, double bpm) {
        double beat = 60.0 / bpm;
        int n = (int) (rate * beat * 8);
        float[] d = new float[n];
        double[] seq = {55, 55, 82.41, 73.42};
        for (int s = 0; s < 8; s++) {
            double f = seq[s % 4];
            int off = (int) (rate * beat * s);
            int len = (int) (rate * beat * 0.9);
            double ph = 0;
            for (int i = 0; i < len && off + i < n; i++) {
                double t = i / (double) rate;
                ph += 2 * Math.PI * f / rate;
                double v = Math.tanh(2.5 * Math.sin(ph)) * Math.exp(-t * 2.5);
                d[off + i] += (float) (v * 0.7);
            }
        }
        return norm(d, rate, NAMES[4]);
    }

    private static Sample chops(int rate, double bpm) {
        double beat = 60.0 / bpm;
        int n = (int) (rate * beat * 4);
        float[] d = new float[n];
        double[][] formants = {{700, 1200}, {400, 2000}, {300, 800}, {600, 1000}};
        for (int s = 0; s < 8; s++) {
            double[] fm = formants[s % 4];
            int off = (int) (rate * beat * s / 2.0);
            int len = (int) (rate * beat * 0.45);
            double ph = 0;
            double f0 = 150 + 20 * (s % 3);
            for (int i = 0; i < len && off + i < n; i++) {
                double t = i / (double) rate;
                ph += 2 * Math.PI * f0 / rate;
                double v = 0;
                for (int h = 1; h < 24; h++) {
                    double hf = h * f0;
                    double g = Math.exp(-Math.pow((hf - fm[0]) / 220, 2)) + 0.6 * Math.exp(-Math.pow((hf - fm[1]) / 300, 2));
                    v += g * Math.sin(h * ph) / h;
                }
                double env = Math.min(1, i / (rate * 0.01)) * Math.exp(-t * 4);
                d[off + i] += (float) (v * env * 0.5);
            }
        }
        return norm(d, rate, NAMES[5]);
    }

    private static Sample norm(float[] d, int rate, String name) {
        float mx = 0;
        for (float f : d) mx = Math.max(mx, Math.abs(f));
        if (mx > 0) {
            float g = 0.8f / mx;
            for (int i = 0; i < d.length; i++) d[i] *= g;
        }
        return new Sample(d, rate, name);
    }
}
