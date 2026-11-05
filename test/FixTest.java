import com.gfdrps.core.*;

/** Regression tests for the issues found on the phone: reverb stuck on, filter HP, scratch noise, looper, clicks, slices. */
public class FixTest {
    static int fails = 0;
    static final int R = 48000;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + msg);
        if (!ok) fails++;
    }

    static float rms(float[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i] * a[i];
        return (float) Math.sqrt(s / Math.max(1, to - from));
    }

    static float[] sine(double hz, int n, float amp) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = (float) (Math.sin(2 * Math.PI * hz * i / R) * amp);
        return d;
    }

    /** Pushes a mono signal through an effect in 256-frame blocks (stereo identical). */
    static float[] runFx(Fx.Effect fx, float[] in, double bpm) {
        float[] out = new float[in.length];
        float[] l = new float[256], r = new float[256];
        for (int o = 0; o < in.length; o += 256) {
            int n = Math.min(256, in.length - o);
            System.arraycopy(in, o, l, 0, n);
            System.arraycopy(in, o, r, 0, n);
            fx.process(l, r, n, bpm);
            System.arraycopy(l, 0, out, o, n);
        }
        return out;
    }

    static float maxDiff(float[] a, int from, int to) {
        float m = 0;
        for (int i = Math.max(1, from); i < to; i++) m = Math.max(m, Math.abs(a[i] - a[i - 1]));
        return m;
    }

    static float[] noise(int n, float amp, long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = (r.nextFloat() * 2 - 1) * amp;
        return d;
    }

    /** Renders a slot for `seconds`, calling step(blockIndex) before every 256-frame block. Returns mono output. */
    interface Step { void at(int block); }
    static float[] renderSlot(Slot s, double seconds, double bpm, Step step) {
        int blocks = (int) (seconds * R / 256);
        float[] out = new float[blocks * 256];
        float[] l = new float[256], r = new float[256];
        for (int b = 0; b < blocks; b++) {
            if (step != null) step.at(b);
            java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0);
            s.render(l, r, 256, (long) b * 256, bpm, true);
            for (int i = 0; i < 256; i++) out[b * 256 + i] = (l[i] + r[i]) * 0.5f;
        }
        return out;
    }

    static Slot sineSlot(double hz, float amp) {
        Slot s = new Slot(R);
        s.setSample(new Sample(sine(hz, R * 4, amp), R, "sine"), 120);
        return s;
    }

    public static void main(String[] a) {
        System.out.println("== Reverb");
        {
            Fx.Effect rv = Fx.create(Fx.REVERB, R);
            float[] dry = noise(R, 0.3f, 1);
            float[] same = runFx(rv, dry, 120);
            boolean identical = true;
            for (int i = 0; i < dry.length; i++) if (same[i] != dry[i]) { identical = false; break; }
            check(identical, "reverb desligado deixa o sinal exatamente igual (bypass limpo)");

            // burst while active, then release
            rv = Fx.create(Fx.REVERB, R);
            rv.active = true; rv.setXY(0.6f, 1f);
            float[] in = new float[R * 6];
            float[] burst = noise(R / 2, 0.4f, 2);
            System.arraycopy(burst, 0, in, 0, burst.length);
            float[] out = new float[in.length];
            float[] l = new float[256], r = new float[256];
            for (int o = 0; o + 256 <= in.length; o += 256) {
                if (o == R / 2) rv.active = false;      // finger lifted
                System.arraycopy(in, o, l, 0, 256); System.arraycopy(in, o, r, 0, 256);
                rv.process(l, r, 256, 120);
                System.arraycopy(l, 0, out, o, 256);
            }
            float wetNow = rms(out, R / 2 + 2000, R / 2 + 6000);
            float late = rms(out, R * 4, R * 6);
            System.out.println("   cauda logo após soltar: " + wetNow + " | 3.5-5.5 s depois: " + late);
            check(wetNow > 0.002f, "cauda audível logo após soltar o pad");
            check(late < 1e-5f, "reverb some por completo depois de soltar (sem reverb residual)");

            // decay control: long vs short
            float[] tails = new float[3];
            float[] xs = {0f, 0.5f, 1f};
            for (int k = 0; k < 3; k++) {
                Fx.Effect e = Fx.create(Fx.REVERB, R);
                e.active = true; e.setXY(xs[k], 1f);
                float[] inp = new float[R * 12];
                System.arraycopy(burst, 0, inp, 0, burst.length);
                float[] o2 = runFx(e, inp, 120);
                tails[k] = rms(o2, R * 2, R * 2 + R / 2);
                // measure RT60: time from burst end until level falls 60 dB below the level just after the burst
                float ref = rms(o2, R / 2, R / 2 + 2400);
                int t60 = -1;
                for (int w = R / 2; w + 2400 < o2.length; w += 2400) {
                    if (rms(o2, w, w + 2400) < ref * 0.001f) { t60 = w - R / 2; break; }
                }
                System.out.println("   decay x=" + xs[k] + ": nível em 2 s = " + tails[k] + ", RT60 medido ≈ " + (t60 < 0 ? ">11" : String.format("%.2f", t60 / (double) R)) + " s (pedido " + String.format("%.2f", 0.4 * Math.pow(20, xs[k])) + " s)");
            }
            check(tails[1] > tails[0] * 5 && tails[2] > tails[1] * 2, "controle de decay (x) faz diferença clara em toda a faixa");

            // stability + loudness at full settings
            Fx.Effect e = Fx.create(Fx.REVERB, R);
            e.active = true; e.setXY(1f, 1f);
            float[] big = noise(R * 10, 0.9f, 3);
            float[] ob = runFx(e, big, 120);
            float pk = 0; boolean fin = true;
            for (float v : ob) { pk = Math.max(pk, Math.abs(v)); if (Float.isNaN(v) || Float.isInfinite(v)) fin = false; }
            check(fin && pk < 6f, "estável com ruído forte, decay e wet no máximo (pico " + pk + ")");
            float wetRms = rms(ob, R * 6, R * 10), dryRms = rms(big, R * 6, R * 10);
            System.out.println("   nível wet+dry / dry em regime = " + wetRms / dryRms);
            check(wetRms / dryRms > 1.2f && wetRms / dryRms < 3.5f, "nível do reverb equilibrado");

            // no ringing at a single frequency (a sign of a metallic, bad reverb): impulse response spectral spread
            Fx.Effect e2 = Fx.create(Fx.REVERB, R);
            e2.active = true; e2.setXY(0.5f, 1f);
            float[] imp = new float[R * 3]; imp[0] = 1f;
            float[] ir = runFx(e2, imp, 120);
            // echo density: fraction of 1ms windows after 200 ms that carry energy
            int dense = 0, tot = 0;
            for (int w = (int) (0.2 * R); w + 48 < (int) (1.2 * R); w += 48) {
                tot++;
                float local = rms(ir, w - 600, w + 600);      // 25 ms neighbourhood
                if (rms(ir, w, w + 48) > 0.25f * local) dense++;   // no gaps: every ms carries a share of the energy
            }
            System.out.println("   densidade de ecos 0.2–1.2 s: " + dense + "/" + tot);
            check(dense > tot * 0.97, "resposta densa e contínua (sem eco discreto/metálico)");
        }

        System.out.println("== Filtro LP / HP");
        {
            float[] lo = sine(100, R, 0.5f), hi = sine(8000, R, 0.5f);
            Fx.Filter f = (Fx.Filter) Fx.create(Fx.FILTER, R);
            f.active = true; f.setXY(0.537f, 0f);   // ~1 kHz, no resonance
            f.highPass = false;
            float lpLo = rms(runFx(f, lo, 120), R / 2, R), lpHi = rms(runFx(f, hi, 120), R / 2, R);
            Fx.Filter g = (Fx.Filter) Fx.create(Fx.FILTER, R);
            g.active = true; g.setXY(0.537f, 0f); g.highPass = true;
            float hpLo = rms(runFx(g, lo, 120), R / 2, R), hpHi = rms(runFx(g, hi, 120), R / 2, R);
            float ref = 0.5f / (float) Math.sqrt(2);
            System.out.println("   LP: 100Hz=" + lpLo / ref + " 8kHz=" + lpHi / ref + " | HP: 100Hz=" + hpLo / ref + " 8kHz=" + hpHi / ref);
            check(lpLo / ref > 0.9f && lpHi / ref < 0.1f, "Low-pass deixa passar graves e corta agudos");
            check(hpHi / ref > 0.9f && hpLo / ref < 0.1f, "High-pass deixa passar agudos e corta graves");
        }

        System.out.println("== Looper: duração precisa, reverse e quantização");
        {
            // 2 s sample, 48 kHz. fingers at 0.23 and 0.47 => loop = 0.24*2 s = 0.48 s
            double expect = 0.24 * 2.0;
            double per = loopPeriod(0, 0.23f, 0.47f);
            System.out.println("   período medido " + per + " s, esperado " + expect + " s");
            check(Math.abs(per - expect) / expect < 0.01, "duração do loop = distância entre os dedos (±1%)");
            double per2 = loopPeriod(0, 0.10f, 0.60f);
            check(Math.abs(per2 - 1.0) / 1.0 < 0.01, "loop largo (0.10–0.60) dura 1.0 s");
            double per3 = loopPeriod(0, 0.50f, 0.52f);
            check(Math.abs(per3 - 0.04) / 0.04 < 0.05, "loop curto (0.02 = 40 ms) preciso (" + per3 + " s)");
            double perQ = loopPeriod(8, 0.23f, 0.47f);
            System.out.println("   com quantização 8: " + perQ + " s");
            check(Math.abs(perQ - 0.5) < 0.01, "quantização 8 ajusta os marcadores à grade (0.25–0.5 → 0.5 s)");
            // reverse
            Slot s = sineSlot(220, 0.5f);
            Slot rampSlot = new Slot(R);
            float[] ramp = new float[R * 2];
            for (int i = 0; i < ramp.length; i++) ramp[i] = (float) i / ramp.length;
            rampSlot.setSample(new Sample(ramp, R, "ramp"), 120);
            rampSlot.mode = Slot.LOOPER;
            rampSlot.handle(0, 0, 0.60f, 0.5f, 0, 0);
            rampSlot.handle(0, 1, 0.20f, 0.5f, 0, 0);   // second finger LEFT of the first => reverse
            int dec = 0, inc = 0;
            float prev = -1;
            float[] l = new float[256], r = new float[256];
            for (int b = 0; b < 200; b++) {
                java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0);
                rampSlot.render(l, r, 256, b * 256L, 120, true);
                if (b > 20) for (int i = 1; i < 256; i++) {
                    float d = l[i] - l[i - 1];
                    if (Math.abs(d) < 0.01f) { if (d < 0) dec++; else if (d > 0) inc++; }
                }
            }
            System.out.println("   reverse: subindo=" + inc + " descendo=" + dec);
            check(dec > inc * 5, "dedos cruzados tocam o loop em reverse");
        }

        System.out.println("== Scratch");
        {
            Slot s = sineSlot(300, 0.8f);
            s.mode = Slot.SCRATCH;
            final float[] x = {0.5f};
            // finger goes down, then wiggles smoothly at 60 Hz like a real touch screen
            float[] out = renderSlot(s, 4, 120, b -> {
                if (b == 0) s.handle(0, 0, 0.5f, 0.5f, 0, 0);
                if (b > 10 && b < 600 && b % 3 == 0) {
                    float t = (b - 10) / 187.5f;
                    s.handle(1, 0, 0.5f + 0.12f * (float) Math.sin(t * 2 * Math.PI * 1.5), 0.5f, 0, 0);
                }
                if (b == 600) s.handle(2, 0, 0, 0, 0, 0);
            });
            float moving = rms(out, 10000, 100000);
            float md = maxDiff(out, 4000, 120000);
            System.out.println("   scratch: rms em movimento " + moving + ", maior salto entre amostras " + md);
            check(moving > 0.03f, "scratch produz som ao arrastar");
            check(md < 0.3f, "scratch sem ruído/estalos (maior salto " + md + ")");
            // finger down but still
            Slot s2 = sineSlot(300, 0.8f);
            s2.mode = Slot.SCRATCH;
            float[] still = renderSlot(s2, 2, 120, b -> { if (b == 0) s2.handle(0, 0, 0.5f, 0.5f, 0, 0); });
            check(rms(still, R, 2 * R) < 0.005f, "dedo parado = silêncio");
        }

        System.out.println("== Estalos ao arrastar (maior salto entre amostras com seno de 220 Hz)");
        {
            String[] names = {"Slicer", "Looper", "Bow", "Tape", "Scratch", "Arp", "Keyboard", "LoopPlayer"};
            for (int m = 0; m < 8; m++) {
                final int mode = m;
                Slot s = sineSlot(220, 0.5f);
                s.mode = mode;
                s.legato = true; s.infinite = true;
                if (mode == Slot.ARP) s.div = 16;
                float[] out = renderSlot(s, 5, 120, b -> {
                    if (b == 0) { s.handle(0, 0, 0.3f, 0.3f, 0, 0); if (mode == Slot.LOOPER) s.handle(0, 1, 0.45f, 0.4f, 0, 0); }
                    if (b > 20 && b < 800 && b % 3 == 0) {
                        float t = (b - 20) / 187.5f;
                        float xx = 0.3f + 0.25f * (float) (0.5 + 0.5 * Math.sin(t * 2 * Math.PI * 0.8));
                        float yy = 0.2f + 0.6f * (float) (0.5 + 0.5 * Math.sin(t * 2 * Math.PI * 1.7));
                        s.handle(1, 0, xx, yy, 0, 0);
                        if (mode == Slot.LOOPER) s.handle(1, 1, xx + 0.12f, 0.4f, 0, 0);
                    }
                    if (b == 800) { s.handle(2, 0, 0, 0, 0, 0); if (mode == Slot.LOOPER) s.handle(2, 1, 0, 0, 0, 0); }
                    if (b == 400) s.volume = 0.2f;    // sudden slider move
                    if (b == 420) s.volume = 0.9f;
                    if (b == 440) s.pan = 0.1f;
                });
                float md = maxDiff(out, 3000, 5 * R - 300);
                float pk = 0; for (float v : out) pk = Math.max(pk, Math.abs(v));
                System.out.println("   " + names[m] + ": maior salto " + md + " (pico " + pk + ")");
                check(md < 0.30f, names[m] + " sem estalos ao arrastar");
            }
            // effects switching on/off
            Slot s = sineSlot(220, 0.5f);
            s.mode = Slot.LOOPPLAYER;
            for (int f = 0; f < Fx.COUNT; f++) {
                final int ff = f;
                Slot t = sineSlot(220, 0.5f);
                t.mode = Slot.LOOPPLAYER;
                if (f == Fx.FILTER) ((Fx.Filter) t.fx[f]).highPass = true;
                float[] out = renderSlot(t, 4, 120, b -> {
                    if (b == 0) t.handle(0, 0, 0.5f, 0.5f, 0, 0);
                    if (b == 100) { t.fx[ff].setXY(0.4f, 0.8f); t.fx[ff].active = true; }
                    if (b == 300) t.fx[ff].active = false;
                });
                float md = maxDiff(out, 3000, 4 * R - 300);
                check(md < 0.30f, Fx.NAMES[f] + " liga/desliga sem estalo (maior salto " + md + ")");
            }
        }

        System.out.println("== Slices manuais, automáticos e reverse");
        {
            Slot s = new Slot(R);
            float[] ramp = new float[R * 2];
            for (int i = 0; i < ramp.length; i++) ramp[i] = (float) i / ramp.length;
            s.setSample(new Sample(ramp, R, "ramp"), 120);
            s.markers = Slot.cleanMarkers(new float[]{0.8f, 0.1f, 0.5f, 0.5001f, 1.2f, -1f});
            check(s.markers.length == 3 && s.markers[0] == 0.1f && s.markers[2] == 0.8f, "marcadores são ordenados, sem duplicatas e dentro de 0..1");
            s.mode = Slot.SLICER;
            float lo = 9, hi = -9;
            float[] l = new float[256], r = new float[256];
            s.handle(0, 0, 0.3f, 0.5f, 0, 0);      // between 0.1 and 0.5
            for (int b = 0; b < 400; b++) {
                java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0);
                s.render(l, r, 256, b * 256L, 120, true);
                for (int i = 0; i < 256; i++) { float v = (l[i] + r[i]); if (Math.abs(v) > 0.02f) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
            }
            // output is the ramp scaled by volume/pan/env; so its range reveals which region played
            System.out.println("   fatia tocada: valores de " + lo + " a " + hi);
            float rampHi = hi / 0.7071f;      // (l+r) = ramp * vel(0.625) * volume(0.8) * 1.414
            boolean ended = true;
            for (float ph : s.playheads) if (ph >= 0) ended = false;
            System.out.println("   rampa máxima tocada ≈ " + rampHi + ", voz terminou: " + ended);
            check(rampHi > 0.46f && rampHi < 0.52f && ended, "fatia manual 0.1–0.5 toca até o marcador em 0.5 e para");

            Engine e = new Engine(R);
            int n = e.autoSlice(0, 32, 0.5f);
            float[] m = e.slots[0].markers;
            System.out.println("   auto-slice no loop de bateria: " + n + " marcadores");
            check(n >= 8 && n <= 32, "detecção automática encontra os golpes (" + n + ")");
            boolean near = false;
            if (m != null) for (float v : m) if (Math.abs(v * 4.0 - 1.0) < 0.02) near = true;   // snare at 1.0 s of 4 s
            check(near, "detectou a caixa em 1.0 s dentro de ±20 ms");

            Slot rs = e.slots[1];
            rs.markers = Slot.cleanMarkers(new float[]{0.25f});
            float first = rs.sample.data[10];
            int len = rs.sample.length();
            float last = rs.sample.data[len - 11];
            rs.reverseSample();
            check(rs.reversed && Math.abs(rs.sample.data[len - 11] - first) < 1e-9 && Math.abs(rs.markers[0] - 0.75f) < 1e-6, "reverse inverte o áudio e espelha os marcadores");
            rs.reverseSample();
            check(!rs.reversed && Math.abs(rs.sample.data[10] - first) < 1e-9, "reverse de novo restaura o original");
        }

        System.out.println(fails == 0 ? "\nTODOS OS TESTES PASSARAM" : "\nFALHAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    /** Measures the loop period in seconds from playhead wraps, with two fingers at fx0/fx1. */
    static double loopPeriod(int div, float fa, float fb) {
        Slot s = new Slot(R);
        float[] data = new float[R * 2];
        for (int i = 0; i < data.length; i++) data[i] = 0.3f;
        s.setSample(new Sample(data, R, "flat"), 120);
        s.mode = Slot.LOOPER;
        s.div = div;
        s.handle(0, 0, fa, 0.5f, 0, 0);
        s.handle(0, 1, fb, 0.5f, 0, 0);
        float[] l = new float[256], r = new float[256];
        double prev = -1;
        java.util.ArrayList<Double> wraps = new java.util.ArrayList<>();
        for (int b = 0; b < 40 * R / 256; b++) {
            java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0);
            s.render(l, r, 256, b * 256L, 120, true);
            double ph = -1;
            for (float v : s.playheads) if (v >= 0) ph = v;
            if (prev >= 0 && ph >= 0 && ph < prev - 0.001) wraps.add((b * 256.0 + 128) / R);
            prev = ph;
        }
        if (wraps.size() < 3) return -1;
        // fit: average spacing between first and last wrap; block granularity averages out over many loops
        return (wraps.get(wraps.size() - 1) - wraps.get(0)) / (wraps.size() - 1);
    }
}
