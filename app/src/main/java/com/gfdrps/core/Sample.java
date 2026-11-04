package com.gfdrps.core;

/** Mono audio buffer used by one slot. Stereo sources are downmixed on import. */
public final class Sample {
    public final float[] data;
    public final int sampleRate;
    public final String name;
    /** Min/max peaks for waveform drawing (PEAKS columns). */
    public static final int PEAKS = 1024;
    public final float[] peakMin = new float[PEAKS];
    public final float[] peakMax = new float[PEAKS];

    public Sample(float[] data, int sampleRate, String name) {
        this.data = data;
        this.sampleRate = sampleRate;
        this.name = name;
        computePeaks();
    }

    public int length() { return data.length; }

    private void computePeaks() {
        int n = data.length;
        for (int i = 0; i < PEAKS; i++) {
            int a = (int) ((long) i * n / PEAKS);
            int b = Math.max(a + 1, (int) ((long) (i + 1) * n / PEAKS));
            float mn = 0, mx = 0;
            for (int j = a; j < b && j < n; j++) {
                float v = data[j];
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            peakMin[i] = mn;
            peakMax[i] = mx;
        }
    }

    /** Linear interpolated read; position in samples, clamped to the buffer. */
    public float at(double pos) {
        int n = data.length;
        if (n == 0) return 0f;
        if (pos < 0) pos = 0;
        if (pos > n - 1) pos = n - 1;
        int i = (int) pos;
        int j = i + 1 < n ? i + 1 : i;
        float f = (float) (pos - i);
        return data[i] + (data[j] - data[i]) * f;
    }
}
