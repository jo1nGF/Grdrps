import com.gfdrps.core.*;

/** Measures how "metallic" a reverb impulse response is: periodicity of the late tail and spectral peakiness. */
public class ReverbQuality {
    static final int R = 48000;

    static float[] ir(float x, float y, int seconds) {
        Fx.Effect e = Fx.create(Fx.REVERB, R);
        e.active = true; e.setXY(x, y);
        float[] out = new float[R * seconds];
        float[] l = new float[256], r = new float[256];
        for (int o = 0; o + 256 <= out.length; o += 256) {
            java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0);
            if (o == 0) { l[0] = 1f; r[0] = 1f; }
            e.process(l, r, 256, 120);
            for (int i = 0; i < 256; i++) out[o + i] = l[i] - r[i] * 0f;   // left channel
        }
        return out;
    }

    /** Max normalized autocorrelation at lags 3..80 ms of the envelope-flattened tail (0 = noise-like, high = ringing). */
    public static double periodicity(float[] ir) {
        int from = (int) (0.35 * R), to = (int) (1.6 * R);
        int n = to - from;
        double[] t = new double[n];
        int win = 480;
        for (int i = 0; i < n; i += win) {
            double e = 0; int m = Math.min(win, n - i);
            for (int k = 0; k < m; k++) e += ir[from + i + k] * ir[from + i + k];
            double g = 1.0 / Math.sqrt(e / m + 1e-18);
            for (int k = 0; k < m; k++) t[i + k] = ir[from + i + k] * g;
        }
        double e0 = 0; for (double v : t) e0 += v * v;
        double best = 0;
        for (int lag = (int) (0.003 * R); lag <= (int) (0.08 * R); lag++) {
            double s = 0;
            for (int i = 0; i + lag < n; i++) s += t[i] * t[i + lag];
            best = Math.max(best, Math.abs(s / e0));
        }
        return best;
    }

    /** Std-dev (dB) of the tail spectrum around its smooth shape between 300 Hz and 6 kHz; ~5.6 dB is ideal diffuse noise. */
    public static double spectralPeakiness(float[] ir) {
        int N = 1 << 16;
        int from = (int) (0.4 * R);
        double[] re = new double[N], im = new double[N];
        for (int i = 0; i < N; i++) re[i] = ir[from + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        fft(re, im);
        int b0 = (int) (300.0 * N / R), b1 = (int) (6000.0 * N / R);
        double[] db = new double[b1 - b0];
        for (int b = b0; b < b1; b++) db[b - b0] = 10 * Math.log10(re[b] * re[b] + im[b] * im[b] + 1e-30);
        int w = 48;
        double sum = 0; int cnt = 0;
        for (int i = w; i < db.length - w; i++) {
            double m = 0; for (int k = -w; k <= w; k++) m += db[i + k];
            m /= (2 * w + 1);
            sum += (db[i] - m) * (db[i] - m); cnt++;
        }
        return Math.sqrt(sum / cnt);
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }

    public static void main(String[] a) {
        for (float x : new float[]{0.3f, 0.6f, 0.9f}) {
            float[] r = ir(x, 1f, 4);
            System.out.printf("x=%.1f  periodicidade=%.3f  picos espectrais=%.2f dB%n", x, periodicity(r), spectralPeakiness(r));
        }
    }
}
