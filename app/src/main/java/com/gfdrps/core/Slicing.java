package com.gfdrps.core;

/** Transient detection used to place slice markers automatically. */
public final class Slicing {
    private Slicing() {}

    /**
     * Finds note/hit onsets and returns marker positions (0..1, sorted, interior).
     * sensitivity 0..1: higher finds more (softer) onsets. At most maxMarkers are kept (the strongest).
     */
    public static float[] detect(Sample s, int maxMarkers, float sensitivity) {
        float[] d = s.data;
        int sr = s.sampleRate;
        int hop = Math.max(32, sr / 200);          // 5 ms
        int win = hop * 2;
        int frames = d.length / hop;
        if (frames < 8) return new float[0];
        float[] e = new float[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            int a = f * hop, b = Math.min(d.length, a + win);
            for (int i = a; i < b; i++) sum += d[i] * d[i];
            e[f] = (float) (10.0 * Math.log10(sum / Math.max(1, b - a) + 1e-9));   // dB
        }
        float[] flux = new float[frames];
        for (int f = 1; f < frames; f++) flux[f] = Math.max(0f, e[f] - e[f - 1]);
        // light smoothing so a single noisy frame is not a hit
        float[] fs = new float[frames];
        for (int f = 1; f < frames - 1; f++) fs[f] = 0.25f * flux[f - 1] + 0.5f * flux[f] + 0.25f * flux[f + 1];
        int radius = (int) (0.3 * sr / hop);
        float k = 2.2f - 1.6f * Math.max(0f, Math.min(1f, sensitivity));   // threshold multiplier
        int minGap = Math.max(1, (int) (0.06 * sr / hop));
        java.util.ArrayList<int[]> cand = new java.util.ArrayList<>();
        java.util.ArrayList<Float> strength = new java.util.ArrayList<>();
        int last = -minGap * 2;
        for (int f = 2; f < frames - 2; f++) {
            float v = fs[f];
            if (v < 1.5f) continue;                           // must rise by at least ~1.5 dB
            if (!(v >= fs[f - 1] && v > fs[f + 1])) continue; // local peak
            double mean = 0; int c = 0;
            for (int g = Math.max(1, f - radius); g < Math.min(frames, f + radius); g++) { mean += fs[g]; c++; }
            mean /= Math.max(1, c);
            if (v < mean * k) continue;
            if (f - last < minGap) {
                // keep the stronger of two close candidates
                int li = cand.size() - 1;
                if (li >= 0 && strength.get(li) < v) { cand.set(li, new int[]{f}); strength.set(li, v); last = f; }
                continue;
            }
            cand.add(new int[]{f});
            strength.add(v);
            last = f;
        }
        // keep the strongest maxMarkers
        Integer[] order = new Integer[cand.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Float.compare(strength.get(y), strength.get(x)));
        int keep = Math.min(maxMarkers, order.length);
        float[] out = new float[keep];
        for (int i = 0; i < keep; i++) {
            int f = cand.get(order[i])[0];
            // the energy rise is measured at the end of the window, step back to where it started
            int pos = Math.max(0, f * hop - hop);
            // snap back to the previous zero crossing to avoid a click at the slice start
            int lim = Math.max(0, pos - hop);
            int z = pos;
            while (z > lim && !(d[z - 1] <= 0 && d[z] > 0)) z--;
            if (z > lim) pos = z;
            out[i] = pos / (float) d.length;
        }
        return Slot.cleanMarkers(out) == null ? new float[0] : Slot.cleanMarkers(out);
    }
}
