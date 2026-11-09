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

    /** Cubic (Catmull-Rom) interpolated read: much cleaner than linear when pitched or scratched. Clamped to the buffer. */
    public float at(double pos) {
        final float[] d = data;
        int n = d.length;
        if (n == 0) return 0f;
        if (pos < 0) pos = 0;
        if (pos > n - 1) pos = n - 1;
        int i = (int) pos;
        float f = (float) (pos - i);
        if (f == 0f) return d[i];
        float y0 = d[i > 0 ? i - 1 : 0], y1 = d[i];
        float y2 = d[i + 1 < n ? i + 1 : n - 1], y3 = d[i + 2 < n ? i + 2 : n - 1];
        float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * f + c2) * f + c1) * f + y1;
    }
}
