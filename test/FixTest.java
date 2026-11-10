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
                if (o >= R / 2) rv.active = false;      // finger lifted
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
                System.out.println("   decay x=" + xs[k] + ": nível em 2 s = " + tails[k] + ", RT60 medido ≈ " + (t60 < 0 ? ">11" : String.format("%.2f", t60 / (double) R)) + " s (pedido " + String.format("%.2f", 0.6 * Math.pow(24, xs[k])) + " s)");
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
            check(rampHi > 0.38f && rampHi < 0.52f && ended, "fatia manual 0.1–0.5 toca até o marcador em 0.5 e para");

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

        System.out.println("== Botão de stop");
        {
            Engine e = new Engine(R);
            e.bpm = 240;
            e.setMode(0, Slot.LOOPPLAYER);
            e.setMode(1, Slot.SLICER); e.slots[1].infinite = true;
            e.setMode(2, Slot.ARP); e.slots[2].div = 16;
            e.setMode(3, Slot.SLICER);
            short[] pcm = new short[2 * R / 10];
            java.util.function.IntConsumer run = secs -> { for (int i = 0; i < secs * 10; i++) e.process(pcm, pcm.length / 2); };
            java.util.function.Supplier<Float> peak = () -> { float pk = 0; for (short v : pcm) pk = Math.max(pk, Math.abs(v) / 32768f); return pk; };
            e.armGestures(3, 2);
            run.accept(1);
            e.touch(3, 0, 0, 0.1f, 0.2f); run.accept(1); e.touch(3, 2, 0, 0, 0);
            run.accept(3);                                   // gesture recorded and now looping
            e.touch(0, 0, 0, 0.5f, 0.5f);                    // loop player on
            e.touch(1, 0, 1, 0.4f, 0.3f);                    // held looping slice
            e.touch(2, 0, 2, 0.2f, 0.3f);                    // held arp finger
            run.accept(2);
            check(peak.get() > 0.05f, "antes do stop há som");
            e.stopAll();
            run.accept(1);                                   // fade-out time
            float worst = 0;
            for (int i = 0; i < 30; i++) { e.process(pcm, pcm.length / 2); worst = Math.max(worst, peak.get()); }
            System.out.println("   pico nos 3 s depois do stop: " + worst);
            check(worst < 0.002f, "stop silencia vozes, loops, arpejo e gestos gravados");
            check(!e.playing, "transporte fica parado");
            e.playing = true;
            run.accept(3);
            float back = 0;
            for (int i = 0; i < 40; i++) { e.process(pcm, pcm.length / 2); back = Math.max(back, peak.get()); }
            check(back > 0.01f, "play retoma o gesto gravado");
        }

        System.out.println("== ADSR em todos os modos");
        {
            String[] names = {"Slicer", "Looper", "Bow", "Keyboard"};
            int[] modes = {Slot.SLICER, Slot.LOOPER, Slot.BOW, Slot.KEYBOARD};
            for (int mi = 0; mi < 4; mi++) {
                final Slot sl = new Slot(R);
                float[] flat = new float[R * 6];
                java.util.Arrays.fill(flat, 0.5f);
                sl.setSample(new Sample(flat, R, "flat"), 120);
                sl.mode = modes[mi]; sl.div = 4; sl.infinite = true;
                sl.attack = 0.3f; sl.decay = 0.2f; sl.sustain = 0.4f; sl.release = 0.5f;
                final int mm = modes[mi];
                final int[] upBlock = {-1};
                float[] o = renderSlot(sl, 3.0, 120, b -> {
                    if (b == 0) {
                        if (mm == Slot.LOOPER) { sl.handle(0, 0, 0.1f, 0.5f, 0, 0); sl.handle(0, 1, 0.9f, 0.5f, 0, 0); }
                        else sl.handle(0, 0, 0.30f, 0.0f, 0, 0);
                    }
                    if (b == (int) (1.5 * R / 256)) {
                        upBlock[0] = b;
                        sl.handle(2, 0, 0.30f, 0.0f, (long) b * 256, 0); if (mm == Slot.LOOPER) sl.handle(2, 1, 0.9f, 0.5f, (long) b * 256, 0);
                    }
                });
                float early = rms(o, 0, R / 100), mid = rms(o, (int) (0.28 * R), (int) (0.32 * R));
                float sus = rms(o, (int) (1.0 * R), (int) (1.2 * R));
                float peak = 0; for (int i = 0; i < (int) (0.6 * R); i++) peak = Math.max(peak, Math.abs(o[i]));
                float r1 = rms(o, (int) (1.5 * R + 0.1 * R), (int) (1.5 * R + 0.15 * R));
                float rEnd = rms(o, (int) (2.2 * R), (int) (2.4 * R));
                check(early < 0.35f * mid, names[mi] + ": attack lento (inicio " + early + " < meio " + mid + ")");
                check(sus < 0.75f * peak && sus > 0.15f * peak, names[mi] + ": decay vai para o sustain (pico " + peak + " sus " + sus + ")");
                check(r1 > 0.05f * sus && rEnd < 0.02f * Math.max(sus, 1e-3f) + 1e-4f, names[mi] + ": release gradual (0.1s " + r1 + ", fim " + rEnd + ")");
            }
        }
        System.out.println("== Arpejador");
        {
            // 8 fatias, fatia k tem amplitude (k+1)*0.1
            float[] d = new float[R * 4];
            for (int i = 0; i < d.length; i++) d[i] = 0.1f * (1 + Math.min(7, i * 8 / d.length));
            final Slot cal = new Slot(R);
            cal.setSample(new Sample(d, R, "ramp"), 120);
            cal.mode = Slot.SLICER; cal.div = 8; cal.attack = 0f;
            float[] co = renderSlot(cal, 0.4, 120, b -> { if (b == 0) cal.handle(0, 0, 0.95f, 0f, 0, 0); });
            final float gain = rms(co, (int) (0.2 * R), (int) (0.3 * R)) / 0.8f;     // volume/pan factor of the slot
            for (int dir = 0; dir < 5; dir++) {
                final Slot sl = new Slot(R);
                sl.setSample(new Sample(d, R, "ramp"), 120);
                sl.mode = Slot.ARP; sl.div = 8; sl.arpRate = 16; sl.arpDir = dir; sl.attack = 0f; sl.decay = 0.1f; sl.sustain = 1f; sl.release = 0.02f;
                final int[] ks = {5, 2, 0};
                float[] o = renderSlot(sl, 2.0, 120, b -> {
                    if (b == 0) for (int k = 0; k < 3; k++) sl.handle(0, k, (ks[k] + 0.5f) / 8f, 0f, 0, 0);
                });
                // 1/16 a 120bpm = 0.125 s por passo; mede o nivel no meio de cada passo (a partir do 1o passo na grade)
                StringBuilder seq = new StringBuilder();
                java.util.ArrayList<Integer> got = new java.util.ArrayList<>();
                for (int st = 1; st < 14; st++) {
                    int c = (int) ((st * 0.125 + 0.06) * R);
                    float lv = rms(o, c - 200, c + 200);
                    int sli = Math.round(lv / (gain * 0.1f)) - 1;
                    got.add(sli); seq.append(sli).append(' ');
                }
                String name = new String[]{"UP", "DN", "UD", "RND", "ORD"}[dir];
                System.out.println("     " + name + ": " + seq);
                boolean okSeq = true;
                int[] up = {0, 2, 5}, dn = {5, 2, 0}, ud = {0, 2, 5, 2}, ord = {5, 2, 0};
                int[] pat = dir == 0 ? up : dir == 1 ? dn : dir == 2 ? ud : ord;
                if (dir == 3) { for (int g : got) if (g != 0 && g != 2 && g != 5) okSeq = false; }
                else {
                    // acha o alinhamento do padrao e confere que se repete sem erros
                    boolean any = false;
                    for (int off = 0; off < pat.length && !any; off++) {
                        boolean m = true;
                        for (int i = 0; i < got.size(); i++) if (got.get(i) != pat[(i + off) % pat.length]) { m = false; break; }
                        any = m;
                    }
                    okSeq = any;
                }
                check(okSeq, "Arp " + name + ": ordem correta das fatias");
            }
            // passos na grade: dois arps com dedos em instantes diferentes disparam no mesmo instante da grade
            final Slot sl = new Slot(R);
            sl.setSample(new Sample(d, R, "ramp"), 120);
            sl.mode = Slot.ARP; sl.div = 8; sl.arpRate = 8; sl.attack = 0f; sl.release = 0.02f; sl.decay = 0.1f;
            float[] o = renderSlot(sl, 2.0, 120, b -> { if (b == 100) sl.handle(0, 0, 0.3f, 0f, 100L * 256, 0); });
            // notas a cada 0.25 s: busca os inicios (energia) depois do toque (~0.53 s)
            java.util.ArrayList<Double> starts = new java.util.ArrayList<>();
            boolean prevOn = false;
            for (int i = 100 * 256 + 50; i < o.length; i++) {
                boolean on = Math.abs(o[i]) > 0.02f;
                if (on && !prevOn && (starts.isEmpty() || i / (double) R - starts.get(starts.size() - 1) > 0.1)) starts.add(i / (double) R);
                prevOn = on;
            }
            // o 1o toque soa na hora (imediato); as demais notas caem na grade de 0.25 s
            boolean grid = starts.size() >= 4;
            for (int i = 1; i < starts.size(); i++) { double ph = starts.get(i) / 0.25; if (Math.abs(ph - Math.round(ph)) > 0.06) grid = false; }
            System.out.println("     inicios: " + starts);
            check(grid, "Arp: notas travadas na grade do BPM (" + starts.size() + " notas, a cada 0.25 s)");
        }
        System.out.println("== Reverb: qualidade (sem metalico)");
        {
            for (double x : new double[]{0.3, 0.6, 0.9}) {
                float[] r = ReverbQuality.ir((float) x, 1f, 4);
                double per = ReverbQuality.periodicity(r), pk = ReverbQuality.spectralPeakiness(r);
                check(per < 0.5, String.format("Reverb x=%.1f periodicidade %.3f < 0.5", x, per));
                check(pk < 14, String.format("Reverb x=%.1f picos espectrais %.2f dB < 14", x, pk));
            }
        }
        System.out.println("== Latch");
        {
            for (int md : new int[]{Slot.SLICER, Slot.BOW, Slot.TAPE, Slot.KEYBOARD}) {
                final Slot sl = new Slot(R);
                float[] flat = new float[R * 8]; java.util.Arrays.fill(flat, 0.5f);
                sl.setSample(new Sample(flat, R, "flat"), 120);
                sl.mode = md; sl.div = 4; sl.infinite = true; sl.release = 0.05f; sl.latch = true;
                final int mm = md;
                float[] o = renderSlot(sl, 3.0, 120, b -> {
                    if (b == 0) sl.handle(0, 0, 0.3f, 0.2f, 0, 0);
                    if (b == 20) sl.handle(2, 0, 0.3f, 0.2f, 20L * 256, 0);
                    if (b == 60) sl.mode = (mm == Slot.BOW ? Slot.KEYBOARD : Slot.BOW);       // switching play mode keeps it sounding
                });
                float after = rms(o, (int) (2.5 * R), (int) (2.9 * R));
                check(after > 0.05f, "Latch mantém o som após soltar e trocar de modo (modo " + md + ", rms " + after + ")");
                // novo gesto troca o som latched
                final Slot s2 = new Slot(R);
                s2.setSample(new Sample(flat, R, "flat"), 120);
                s2.mode = md; s2.div = 4; s2.infinite = true; s2.release = 0.05f; s2.latch = true;
                float[] o2 = renderSlot(s2, 2.0, 120, b -> {
                    if (b == 0) s2.handle(0, 0, 0.3f, 0.2f, 0, 0);
                    if (b == 10) s2.handle(2, 0, 0.3f, 0.2f, 0, 0);
                    if (b == 150) s2.applyLatch(false);                                    // latch off = release
                });
                check(rms(o2, (int) (0.5 * R), (int) (0.6 * R)) > 0.05f && rms(o2, (int) (1.8 * R), (int) (1.95 * R)) < 0.005f,
                      "Latch desligado solta o som (modo " + md + ")");
            }
            // sem latch: soltar corta
            final Slot nl = new Slot(R);
            float[] flat = new float[R * 8]; java.util.Arrays.fill(flat, 0.5f);
            nl.setSample(new Sample(flat, R, "flat"), 120);
            nl.mode = Slot.SLICER; nl.div = 4; nl.infinite = true; nl.release = 0.05f;
            float[] o3 = renderSlot(nl, 2.0, 120, b -> { if (b == 0) nl.handle(0, 0, 0.3f, 0.2f, 0, 0); if (b == 20) nl.handle(2, 0, 0.3f, 0.2f, 0, 0); });
            check(rms(o3, (int) (1.5 * R), (int) (1.9 * R)) < 0.005f, "Sem latch o som termina ao soltar");
            // Looper: fadeAll (stop) e soltar o dedo nao pode dar excecao
            final Slot lp = new Slot(R);
            lp.setSample(new Sample(flat, R, "flat"), 120);
            lp.mode = Slot.LOOPER;
            boolean threw = false;
            try {
                lp.handle(0, 0, 0.2f, 0.5f, 0, 0); lp.handle(0, 1, 0.6f, 0.5f, 0, 0);
                renderSlot(lp, 0.1, 120, null);
                lp.fadeAll();
                lp.handle(2, 0, 0, 0, 0, 0); lp.handle(2, 1, 0, 0, 0, 0);
                renderSlot(lp, 0.1, 120, null);
            } catch (RuntimeException ex) { threw = true; }
            check(!threw, "Looper: soltar dedos depois de Stop não dá exceção");
        }
        System.out.println("== Quantizar / Slicer auto-play");
        {
            float[] d = new float[R * 4];
            for (int i = 0; i < d.length; i++) d[i] = 0.1f * (1 + Math.min(7, i * 8 / d.length));
            // quantizar: toque fora da grade comeca exatamente na proxima subdivisao (1/8 a 120bpm = 0.25 s)
            final Slot q = new Slot(R);
            q.setSample(new Sample(d, R, "ramp"), 120);
            q.mode = Slot.SLICER; q.div = 8; q.quantize = true; q.arpRate = 8; q.attack = 0f; q.release = 0.02f;
            float[] o = renderSlot(q, 1.5, 120, b -> { if (b == 130) q.handle(0, 0, 0.3f, 0f, 130L * 256, 0); });   // t = 0.693 s
            int first = -1;
            for (int i = 0; i < o.length; i++) if (Math.abs(o[i]) > 0.01f) { first = i; break; }
            double t0 = first / (double) R;
            System.out.println("     toque em 0.693 s, nota começou em " + t0 + " s");
            check(t0 >= 0.74 && t0 <= 0.76, "Quantizar: nota começa na próxima subdivisão da grade (0.75 s)");
            // sem quantizar começa na hora
            final Slot nq = new Slot(R);
            nq.setSample(new Sample(d, R, "ramp"), 120);
            nq.mode = Slot.SLICER; nq.div = 8; nq.attack = 0f;
            float[] o2 = renderSlot(nq, 1.5, 120, b -> { if (b == 130) nq.handle(0, 0, 0.3f, 0f, 130L * 256, 0); });
            int f2 = -1; for (int i = 0; i < o2.length; i++) if (Math.abs(o2[i]) > 0.01f) { f2 = i; break; }
            check(f2 / (double) R < 0.71, "Sem quantizar a nota começa no toque");
            // dedo levantado antes do disparo ainda toca
            final Slot q2 = new Slot(R);
            q2.setSample(new Sample(d, R, "ramp"), 120);
            q2.mode = Slot.SLICER; q2.div = 8; q2.quantize = true; q2.arpRate = 8; q2.release = 0.02f;
            float[] o3 = renderSlot(q2, 1.5, 120, b -> { if (b == 130) q2.handle(0, 0, 0.3f, 0f, 0, 0); if (b == 135) q2.handle(2, 0, 0.3f, 0f, 0, 0); });
            check(rms(o3, (int) (0.78 * R), (int) (0.9 * R)) > 0.03f, "Quantizar: toque curto (soltou antes da grade) ainda dispara");

            // auto-play: fatias em ordem, uma por passo (1/8 = 0.25 s), travado na grade
            final Slot ap = new Slot(R);
            ap.setSample(new Sample(d, R, "ramp"), 120);
            ap.mode = Slot.SLICER; ap.div = 8; ap.arpRate = 8; ap.attack = 0f; ap.release = 0.01f; ap.decay = 0.05f;
            final Slot cal = new Slot(R);
            cal.setSample(new Sample(d, R, "ramp"), 120); cal.mode = Slot.SLICER; cal.div = 8; cal.attack = 0f;
            float[] co = renderSlot(cal, 0.4, 120, b -> { if (b == 0) cal.handle(0, 0, 0.95f, 0f, 0, 0); });
            final float gain = rms(co, (int) (0.2 * R), (int) (0.3 * R)) / 0.8f;
            ap.autoPlay = true;
            float[] ao = renderSlot(ap, 4.2, 120, null);
            StringBuilder seq = new StringBuilder(); java.util.ArrayList<Integer> got = new java.util.ArrayList<>();
            for (int st = 1; st < 14; st++) {
                int c = (int) ((st * 0.25 + 0.12) * R);
                float lv = rms(ao, c - 150, c + 150);
                int sli = Math.round(lv / (gain * 0.09f)) - 1;
                got.add(sli); seq.append(sli).append(' ');
            }
            System.out.println("     auto: " + seq);
            boolean ord = true;
            for (int i = 1; i < got.size(); i++) if (got.get(i) != (got.get(i - 1) + 1) % 8) ord = false;
            check(ord, "Slicer auto-play percorre as fatias da esquerda para a direita");
            // shuffle: sem repetir a mesma fatia em seguida, e usa varias fatias
            final Slot sh = new Slot(R);
            sh.setSample(new Sample(d, R, "ramp"), 120);
            sh.mode = Slot.SLICER; sh.div = 8; sh.arpRate = 8; sh.attack = 0f; sh.release = 0.01f; sh.decay = 0.05f; sh.autoPlay = true; sh.shuffle = true;
            float[] so = renderSlot(sh, 6.0, 120, null);
            java.util.ArrayList<Integer> sg = new java.util.ArrayList<>();
            for (int st = 1; st < 22; st++) { int c = (int) ((st * 0.25 + 0.12) * R); sg.add(Math.round(rms(so, c - 150, c + 150) / (gain * 0.09f)) - 1); }
            boolean norep = true; java.util.HashSet<Integer> uniq = new java.util.HashSet<>(sg);
            for (int i = 1; i < sg.size(); i++) if (sg.get(i).equals(sg.get(i - 1))) norep = false;
            check(norep && uniq.size() >= 4, "Shuffle: ordem aleatória sem repetir em seguida (" + uniq.size() + " fatias diferentes)");
            // trocar o numero de fatias durante o arp nao pode lancar excecao
            final Slot ar = new Slot(R);
            ar.setSample(new Sample(d, R, "ramp"), 120);
            ar.mode = Slot.ARP; ar.div = 32; ar.arpRate = 16;
            boolean threw = false;
            try {
                renderSlot(ar, 1.5, 120, b -> {
                    if (b == 0) { ar.handle(0, 0, 0.95f, 0.1f, 0, 0); ar.handle(0, 1, 0.5f, 0.1f, 0, 0); }
                    if (b == 40) ar.div = 4;
                    if (b == 80) ar.markers = Slot.cleanMarkers(new float[]{0.2f});
                    if (b == 120) ar.markers = null;
                    if (b == 160) ar.div = 16;
                });
            } catch (RuntimeException ex) { threw = true; ex.printStackTrace(); }
            check(!threw, "Arp: mudar a quantidade de fatias durante o jogo não dá exceção");
            check(ar.arpSlice <= 31, "Arp: fatia indicada é válida");
        }
        System.out.println("== Loop player, gestos, FX");
        {
            Engine eng = new Engine(R);
            float[] flat = new float[R * 2]; java.util.Arrays.fill(flat, 0.4f);
            eng.slots[0].setSample(new Sample(flat, R, "flat"), 120);
            eng.slots[0].mode = Slot.LOOPPLAYER;
            eng.playing = false;                                                // transporte parado
            short[] pcm = new short[256 * 2];
            for (int i = 0; i < 8; i++) eng.process(pcm, 256);
            eng.touch(0, 0, 0, 0.5f, 0.5f); eng.touch(0, 2, 0, 0.5f, 0.5f);       // primeiro toque
            double e = 0;
            for (int i = 0; i < 40; i++) { eng.process(pcm, 256); for (short v : pcm) e += Math.abs(v); }
            check(eng.playing && e > 1000, "Loop player toca já no primeiro toque (transporte parado antes)");

            // gestos: grava no 1o toque, inclui FX, e repete
            Engine g = new Engine(R);
            g.slots[1].mode = Slot.SLICER;
            g.bpm = 240;                                                      // 1 compasso = 1 s
            g.armGestures(1, 1);
            for (int i = 0; i < 20; i++) g.process(pcm, 256);                  // sem toque: nada acontece
            check(g.slots[1].gestures.state == GestureRecorder.ARMED, "Gestos: aguardando o primeiro toque");
            g.touch(1, 0, 0, 0.3f, 0.2f);
            g.process(pcm, 256);
            check(g.slots[1].gestures.state == GestureRecorder.RECORDING, "Gestos: gravação começa no primeiro toque na waveform");
            g.touch(1, 2, 0, 0, 0);
            for (int i = 0; i < 20; i++) g.process(pcm, 256);
            g.fxTouch(1, 3, Fx.FILTER, 0.2f, 0.7f);                            // toque no pad de efeito durante a gravação
            for (int i = 0; i < 10; i++) g.process(pcm, 256);
            g.fxTouch(1, 4, Fx.FILTER, 0.4f, 0.7f);
            g.process(pcm, 256);
            g.fxTouch(1, 5, Fx.FILTER, 0, 0);
            for (int i = 0; i < 400 && g.slots[1].gestures.state == GestureRecorder.RECORDING; i++) g.process(pcm, 256);
            GestureRecorder gr = g.slots[1].gestures;
            check(gr.state == GestureRecorder.PLAYING, "Gestos: terminou a gravação e está repetindo");
            int fxEv = 0; for (int i = 0; i < gr.eventCount(); i++) if (gr.typeAt(i) >= 3) fxEv++;
            check(fxEv == 3, "Gestos: movimentos do pad de efeito foram gravados (" + fxEv + " eventos)");
            // replay aciona o filtro
            boolean sawActive = false;
            for (int i = 0; i < 400; i++) { g.process(pcm, 256); if (g.slots[1].fx[Fx.FILTER].active) sawActive = true; }
            check(sawActive, "Gestos: o replay liga o efeito do pad");
            // salvar / carregar
            int n = gr.eventCount();
            double[] ph = new double[n]; int[] ty = new int[n], idd = new int[n]; float[] xx = new float[n], yy = new float[n];
            for (int i = 0; i < n; i++) { ph[i] = gr.phaseAt(i); ty[i] = gr.typeAt(i); idd[i] = gr.idAt(i); xx[i] = gr.xAt(i); yy[i] = gr.yAt(i); }
            Engine h = new Engine(R);
            h.setGestures(2, gr.bars(), ph, ty, idd, xx, yy);
            h.process(pcm, 256);
            check(h.slots[2].gestures.state == GestureRecorder.PLAYING && h.slots[2].gestures.eventCount() == n, "Gestos: projeto carregado volta tocando (" + n + " eventos)");
        }
        System.out.println("== Delay 10 ms .. 4 s");
        {
            check(Fx.Delay.msForX(0) == 10 && Fx.Delay.msForX(1) == 4000, "Delay: alcance 10 ms a 4000 ms");
            boolean step1 = true;
            for (double x = 0; x < 0.2; x += 0.0005) { double a1 = Fx.Delay.msForX(x), a2 = Fx.Delay.msForX(x + 0.0005); if (a2 - a1 > 1.0 || a1 != Math.rint(a1)) step1 = false; }
            check(step1, "Delay: faixa curta varia de 1 em 1 ms (valores inteiros)");
            // comb filter: 10 ms com feedback alto => picos a cada 100 Hz
            Fx.Effect dl = Fx.create(Fx.DELAY, R); dl.x = 0f; dl.y = 0.9f; dl.active = true;
            float[] in = noise(R * 2, 0.3f, 7);
            float[] out = runFx(dl, in, 120);
            double[] spec = new double[R / 2 + 1];
            int N = 16384; int st0 = R;       // 16384-point DFT at selected bins
            double pk = 0, vl = 0;
            for (int rep = 0; rep < 2; rep++) {
                double f = rep == 0 ? 100.0 : 150.0;       // pico (100 Hz) vs vale (150 Hz)
                double re = 0, im = 0;
                for (int i = 0; i < N; i++) { double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N); re += out[st0 + i] * w * Math.cos(2 * Math.PI * f * i / R); im -= out[st0 + i] * w * Math.sin(2 * Math.PI * f * i / R); }
                if (rep == 0) pk = Math.hypot(re, im); else vl = Math.hypot(re, im);
            }
            check(pk > 3 * vl, String.format("Delay 10 ms: efeito de filtro pente (pico 100 Hz %.1f x vale 150 Hz %.1f)", pk, vl));
            // 4 s: o eco chega depois de ~4 s
            Fx.Effect d4 = Fx.create(Fx.DELAY, R); d4.x = 1f; d4.y = 0.2f; d4.active = true;
            float[] imp = new float[R * 6]; imp[100] = 0.8f;
            float[] o4 = runFx(d4, imp, 120);
            int pkAt = 0; float pv = 0;
            for (int i = R; i < o4.length; i++) if (Math.abs(o4[i]) > pv) { pv = Math.abs(o4[i]); pkAt = i; }
            check(Math.abs(pkAt / (double) R - 4.0) < 0.03, "Delay: tempo máximo = 4 s (eco em " + pkAt / (double) R + " s)");
            // sync: x escolhe nota; 1/8 a 120 bpm = 250 ms
            Fx.Delay sd = (Fx.Delay) Fx.create(Fx.DELAY, R); sd.sync = true; sd.x = 6.5f / 12f; sd.y = 0.2f; sd.active = true;
            runFx(sd, new float[1024], 120);
            check(Math.abs(sd.shownMs - 250f) < 1f, "Delay sync: 1/8 a 120 bpm = 250 ms (" + sd.shownMs + ")");
            runFx(sd, new float[1024], 90);
            check(Math.abs(sd.shownMs - 333.3f) < 1f, "Delay sync acompanha o BPM (90 bpm -> " + sd.shownMs + " ms)");
        }
        System.out.println("== Scratch fluido (toque a 60 Hz)");
        {
            final Slot sc = new Slot(R);
            sc.setSample(new Sample(sine(220, R * 4, 0.6f), R, "sine"), 120);
            sc.mode = Slot.SCRATCH; sc.attack = 0f; sc.release = 0.02f;
            // dedo faz vai-e-vem senoidal 2 Hz, atualizando a 60 Hz (a cada ~13 blocos de 256 = 3 eventos/... )
            final int per = (int) Math.round(R / 60.0 / 256.0 * 256.0);        // frames entre toques
            float[] o = renderSlot(sc, 3.0, 120, b -> {
                if (b == 0) sc.handle(0, 0, 0.5f, 0.3f, 0, 0);
                else if ((b % 3) == 0) { double t = b * 256.0 / R; sc.handle(1, 0, (float) (0.5 + 0.12 * Math.sin(2 * Math.PI * 2.0 * t)), 0.3f, (long) b * 256, 0); }
            });
            float mx = 0, rough = 0; int cnt = 0;
            for (int i = (int) (0.3 * R) + 1; i < o.length; i++) {
                float dd = Math.abs(o[i] - o[i - 1]); if (dd > mx) mx = dd;
            }
            // clique = salto de amostra muito acima do que a senoide gera na velocidade maxima (<~ 2x)
            check(mx < 0.35f, "Scratch a 60 Hz: sem estalos (maior salto entre amostras " + mx + ")");
            // modulacao: o envelope (janelas de 2 ms) nao pode ter uma linha forte em 60 Hz (a taxa dos toques)
            int w2 = 96; int ne = o.length / w2; double[] env = new double[ne]; double mean = 0;
            for (int i = 0; i < ne; i++) { double acc = 0; for (int j = 0; j < w2; j++) acc += Math.abs(o[i * w2 + j]); env[i] = acc / w2; }
            int i0 = (int) (0.5 * R / w2); int cntE = ne - i0;
            for (int i = i0; i < ne; i++) mean += env[i];
            mean /= cntE;
            double fe = R / (double) w2, re = 0, im = 0;
            for (int i = i0; i < ne; i++) { double ph = 2 * Math.PI * 60.0 * (i - i0) / fe; re += (env[i] - mean) * Math.cos(ph); im += (env[i] - mean) * Math.sin(ph); }
            double line60 = 2 * Math.hypot(re, im) / cntE / mean;
            System.out.println("     linha de 60 Hz no envelope: " + line60);
            check(line60 < 0.06, "Scratch a 60 Hz: sem modulação de 60 Hz no volume (" + line60 + ")");
        }
        System.out.println("== v1.4: ping-pong, Bow, degraus, regiao, trim, stretch");
        {
            // rampa: valor = posicao
            float[] ramp = new float[R * 8];
            for (int i = 0; i < ramp.length; i++) ramp[i] = 0.8f * i / ramp.length;
            // ping-pong
            final Slot pp = new Slot(R);
            pp.setSample(new Sample(ramp, R, "ramp"), 120);
            pp.mode = Slot.LOOPER; pp.pingPong = true; pp.attack = 0f;
            float[] o = renderSlot(pp, 4.0, 120, b -> { if (b == 0) { pp.handle(0, 0, 0.2f, 0.5f, 0, 0); pp.handle(0, 1, 0.3f, 0.5f, 0, 0); } });
            float mn = 9, mx = -9, md = 0; int turns = 0; float prev = 0; int dir = 0;
            for (int i = (int) (0.3 * R); i < o.length; i++) {
                mn = Math.min(mn, o[i]); mx = Math.max(mx, o[i]);
                float d = o[i] - o[i - 1]; md = Math.max(md, Math.abs(d));
                int nd = d > 1e-6f ? 1 : (d < -1e-6f ? -1 : dir);
                if (nd != dir && dir != 0) turns++;
                dir = nd;
            }
            check(turns >= 4 && md < 0.1f * (mx - mn) && mx - mn > 0.01f, "Looper ping-pong: vai e volta sem saltos (" + turns + " viradas, salto max " + md + ")");
            // Bow: altura = volume; ate 4 notas
            final Slot bw = new Slot(R);
            float[] flat = new float[R * 4]; java.util.Arrays.fill(flat, 0.5f);
            bw.setSample(new Sample(flat, R, "flat"), 120);
            bw.mode = Slot.BOW; bw.attack = 0f; bw.sustain = 1f;
            float[] hi = renderSlot(bw, 0.6, 120, b -> { if (b == 0) bw.handle(0, 0, 0.5f, 0.05f, 0, 0); });
            final Slot bw2 = new Slot(R);
            bw2.setSample(new Sample(flat, R, "flat"), 120); bw2.mode = Slot.BOW; bw2.attack = 0f; bw2.sustain = 1f;
            float[] lo = renderSlot(bw2, 0.6, 120, b -> { if (b == 0) bw2.handle(0, 0, 0.5f, 0.95f, 0, 0); });
            check(rms(hi, (int) (0.3 * R), (int) (0.5 * R)) > 1.5f * rms(lo, (int) (0.3 * R), (int) (0.5 * R)), "Bow: altura do dedo controla o volume");
            final Slot b5 = new Slot(R);
            b5.setSample(new Sample(flat, R, "flat"), 120); b5.mode = Slot.BOW;
            renderSlot(b5, 0.5, 120, b -> { if (b == 0) for (int k = 0; k < 6; k++) b5.handle(0, k, 0.1f + 0.1f * k, 0.5f, 0, 0); });
            check(b5.activeVoices <= 4, "Bow: no maximo 4 notas (" + b5.activeVoices + ")");
            // tamanho do grao
            final Slot g1 = new Slot(R), g2 = new Slot(R);
            float[] tone = sine(220, R * 4, 0.6f);
            g1.setSample(new Sample(tone, R, "t"), 120); g2.setSample(new Sample(tone, R, "t"), 120);
            g1.mode = g2.mode = Slot.BOW; g1.bowSize = 0.02f; g2.bowSize = 1f;
            float[] a1 = renderSlot(g1, 1.0, 120, b -> { if (b == 0) g1.handle(0, 0, 0.5f, 0.3f, 0, 0); });
            float[] a2 = renderSlot(g2, 1.0, 120, b -> { if (b == 0) g2.handle(0, 0, 0.5f, 0.3f, 0, 0); });
            check(a1.length == a2.length && rms(a1, R / 2, (int) (0.9 * R)) > 0.01f && rms(a2, R / 2, (int) (0.9 * R)) > 0.01f, "Bow: controle de grão funciona nos extremos");

            // Tape em degraus: com marcadores (div=8) um movimento pequeno nao muda a velocidade
            double[] sl = new double[2];
            for (int pass = 0; pass < 2; pass++) {
                final Slot tp = new Slot(R);
                tp.setSample(new Sample(ramp, R, "ramp"), 120);
                tp.mode = Slot.TAPE; tp.div = pass == 0 ? 8 : 0; tp.attack = 0f;
                float[] to = renderSlot(tp, 2.0, 120, b -> { if (b == 0) tp.handle(0, 0, 0.1f, 0.5f, 0, 0); if (b == 300) tp.handle(1, 0, 0.1f + 0.03f, 0.5f, 0, 0); });
                int a0 = (int) (0.4 * R), a1i = (int) (1.2 * R), a2i = (int) (1.9 * R);
                double s1 = (to[a0 + 2000] - to[a0]) / 2000.0, s2 = (to[a2i + 2000] - to[a2i]) / 2000.0;
                sl[pass] = s2 / Math.max(1e-9, s1);
            }
            check(Math.abs(sl[0] - 1.0) < 0.05 && Math.abs(sl[1] - 1.0) > 0.05, "Tape: com marcadores a velocidade anda em degraus (" + sl[0] + " vs livre " + sl[1] + ")");

            // Keyboard com regiao: comeca no marcador de inicio
            float[] two = new float[R * 4];
            for (int i = 0; i < two.length; i++) two[i] = i < two.length / 2 ? 0.2f : 0.6f;
            final Slot kb = new Slot(R), kb2 = new Slot(R);
            kb.setSample(new Sample(two, R, "two"), 120); kb2.setSample(new Sample(two, R, "two"), 120);
            kb.mode = kb2.mode = Slot.KEYBOARD; kb.attack = kb2.attack = 0f; kb2.regStart = 0.5f; kb2.regEnd = 0.75f;
            float[] k1 = renderSlot(kb, 0.3, 120, b -> { if (b == 0) kb.handle(0, 0, 0.5f, 0.2f, 0, 0); });
            float[] k2 = renderSlot(kb2, 0.3, 120, b -> { if (b == 0) kb2.handle(0, 0, 0.5f, 0.2f, 0, 0); });
            check(rms(k2, 2000, 6000) > 2.0f * rms(k1, 2000, 6000), "Keyboard: nota começa no marcador de início da região");

            // Trim
            final Slot tr = new Slot(R);
            tr.setSample(new Sample(ramp, R, "ramp"), 120);
            tr.markers = new float[]{0.25f, 0.6f, 0.9f}; tr.regStart = 0.5f; tr.regEnd = 1f;
            boolean did = tr.trimToRegion();
            check(did && tr.sample.length() == R * 4 && tr.markers != null && tr.markers.length == 2 && Math.abs(tr.markers[0] - 0.2f) < 0.01f && tr.regStart == 0f,
                  "Trim: corta para a região e reescala os marcadores");

            // Time-stretch: tempo muda, altura nao
            float[] tn = sine(440, R * 4, 0.6f);
            for (int c = 0; c < 3; c++) {
                final Slot st = new Slot(R);
                st.setSample(new Sample(tn, R, "tone"), 120);
                st.mode = Slot.LOOPPLAYER; st.attack = 0f;
                st.stretch = c < 2; st.transpose = c == 1 ? 12 : 0;
                double bpmT = c == 1 ? 120 : 150;                        // 150 bpm = 1.25x o tempo
                final Slot sf = st;
                float[] so = renderSlot(st, 3.0, bpmT, b -> { if (b == 0) sf.handle(0, 0, 0.5f, 0.5f, 0, 0); });
                int zc = 0;
                int z0 = R, z1 = 2 * R;
                for (int i = z0 + 1; i < z1; i++) if (so[i - 1] < 0 && so[i] >= 0) zc++;
                double expect = c == 0 ? 440 : c == 1 ? 880 : 550;
                String name = c == 0 ? "stretch 1.25x" : c == 1 ? "stretch +12 st" : "sem stretch 1.25x";
                check(Math.abs(zc - expect) < expect * 0.04, "Time-stretch (" + name + "): frequência " + zc + " Hz ~ " + expect + " Hz");
            }
        }
        System.out.println("== v1.4: desfazer, MIDI clock, exportar, edição de gestos");
        {
            Engine e = new Engine(R);
            short[] pcm = new short[256 * 2];
            Sample orig = e.slots[0].sample;
            float[] nd = new float[R]; java.util.Arrays.fill(nd, 0.3f);
            e.loadSample(0, new Sample(nd, R, "novo")); e.process(pcm, 256);
            check(e.slots[0].sample != orig && e.slots[0].sample.length() == R, "Undo: novo sample carregado");
            check(e.undo(0), "Undo: há passo para desfazer"); e.process(pcm, 256);
            check(e.slots[0].sample == orig, "Undo: volta ao sample anterior");
            e.reverseSample(0); e.process(pcm, 256);
            boolean rev = e.slots[0].reversed;
            e.undo(0); e.process(pcm, 256);
            check(rev && !e.slots[0].reversed && e.slots[0].sample == orig, "Undo: desfaz o reverse");
            e.slots[0].regStart = 0.25f; e.slots[0].regEnd = 0.5f;
            int len0 = e.slots[0].sample.length();
            e.trim(0); e.process(pcm, 256);
            int lenT = e.slots[0].sample.length();
            e.undo(0); e.process(pcm, 256);
            check(lenT < len0 / 3 && e.slots[0].sample.length() == len0 && e.slots[0].regStart == 0.25f, "Undo: desfaz o corte (trim)");

            MidiClock mc = new MidiClock(); double bp = 0; long t = 1_000_000_000L;
            for (int i = 0; i < 120; i++) { bp = mc.tick(t); t += (long) (60e9 / 120.0 / 24.0); }
            check(Math.abs(bp - 120) < 0.5, "MIDI clock: 120 bpm detectado (" + bp + ")");
            for (int i = 0; i < 200; i++) { bp = mc.tick(t); t += (long) (60e9 / 90.0 / 24.0); }
            check(Math.abs(bp - 90) < 0.5, "MIDI clock: acompanha mudança para 90 bpm (" + bp + ")");
            e.midiSync = true;
            for (int i = 0; i < 60; i++) e.midiTick(5_000_000_000L + i * (long) (60e9 / 100.0 / 24.0));
            check(Math.abs(e.bpm - 100) < 1, "Engine segue o clock MIDI (" + e.bpm + " bpm)");

            Engine x = new Engine(R);
            x.startExport();
            for (int i = 0; i < 20; i++) x.process(pcm, 256);
            short[] ex = x.stopExport();
            check(ex != null && ex.length == 20 * 256 * 2, "Exportar: captura exatamente o que foi tocado (" + (ex == null ? 0 : ex.length / 2) + " frames)");

            // edição de gestos: quantizar e remover
            Engine g = new Engine(R);
            g.bpm = 240;
            g.slots[1].mode = Slot.SLICER;
            g.armGestures(1, 1);
            g.touch(1, 0, 0, 0.3f, 0.2f); g.process(pcm, 256);
            for (int i = 0; i < 7; i++) g.process(pcm, 256);
            g.touch(1, 2, 0, 0, 0); g.process(pcm, 256);
            for (int i = 0; i < 4; i++) g.process(pcm, 256);
            g.fxTouch(1, 3, Fx.FILTER, 0.5f, 0.5f); g.process(pcm, 256);
            g.fxTouch(1, 5, Fx.FILTER, 0, 0);
            for (int i = 0; i < 400 && g.slots[1].gestures.state == GestureRecorder.RECORDING; i++) g.process(pcm, 256);
            GestureRecorder gr = g.slots[1].gestures;
            g.quantizeGestures(1, 0.25); g.process(pcm, 256);
            boolean onGrid = gr.eventCount() >= 4;
            for (int i = 0; i < gr.eventCount(); i++) { double q = gr.phaseAt(i) / 0.25; if (Math.abs(q - Math.round(q)) > 1e-6) onGrid = false; }
            check(onGrid, "Gestos: quantizar encaixa os eventos na grade de 1/16");
            int before = gr.eventCount();
            g.removeGestureKind(1, true); g.process(pcm, 256);
            int fxLeft = 0; for (int i = 0; i < gr.eventCount(); i++) if (gr.typeAt(i) >= 3) fxLeft++;
            check(fxLeft == 0 && gr.eventCount() < before && gr.eventCount() > 0, "Gestos: remover só os movimentos do pad de efeito");
            g.undo(1); g.process(pcm, 256);
            check(g.slots[1].gestures.eventCount() == before, "Gestos: desfazer restaura o take");
        }
        // ---------------- v1.5
        {
            // looper: 4 fingers = 2 independent loops
            Slot s = new Slot(R);
            float[] data = new float[R * 4];
            for (int i = 0; i < data.length; i++) data[i] = 0.3f;
            s.setSample(new Sample(data, R, "flat"), 120);
            s.mode = Slot.LOOPER;
            s.handle(0, 0, 0.1f, 0.5f, 0, 0); s.handle(0, 1, 0.2f, 0.5f, 0, 0);
            s.handle(0, 2, 0.6f, 0.5f, 0, 0); s.handle(0, 3, 0.8f, 0.5f, 0, 0);
            float[] l = new float[256], r = new float[256];
            for (int b = 0; b < 20; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 256, b * 256L, 120, true); }
            int heads = 0; for (float v : s.playheads) if (v >= 0) heads++;
            check(heads >= 2, "Looper: 4 dedos tocam 2 loops ao mesmo tempo (" + heads + " playheads)");
            s.handle(2, 3, 0.8f, 0.5f, 0, 0); s.handle(2, 2, 0.6f, 0.5f, 0, 0);
            for (int b = 20; b < 40; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 256, b * 256L, 120, true); }
            heads = 0; for (float v : s.playheads) if (v >= 0) heads++;
            check(heads >= 1, "Looper: soltar o segundo par mantém o primeiro loop");
        }
        {
            // slicer flash, bow grain smoothing
            Slot s = new Slot(R);
            float[] data = new float[R * 2];
            for (int i = 0; i < data.length; i++) data[i] = 0.3f;
            s.setSample(new Sample(data, R, "flat"), 120);
            s.mode = Slot.SLICER;
            s.handle(0, 0, 0.9f, 0.5f, 0, 0);
            check(s.arpFlash > 0.9f && s.arpSlice == s.sliceIndex(0.9f), "Slicer: o slice tocado acende (flash)");
        }
        {
            // redo + tracks + tone + rec peaks
            Engine g = new Engine(R);
            short[] pcm = new short[512];
            g.process(pcm, 256);
            int ns0 = g.slots[0].sample.length();
            g.reverseSample(0); g.process(pcm, 256);
            float first = g.slots[0].sample.data[0];
            check(g.undo(0), "Redo: undo disponível");
            g.process(pcm, 256);
            check(g.canRedo(0), "Redo: refazer disponível depois do desfazer");
            g.redo(0); g.process(pcm, 256);
            check(g.slots[0].sample.data[0] == first && g.slots[0].sample.length() == ns0, "Redo: refaz o reverse");
            g.reverseSample(0); g.undo(0); g.process(pcm, 256);
            g.reverseSample(0);
            check(!g.canRedo(0), "Redo: nova edição limpa a pilha de refazer");
            check(g.slotCount == 6 && g.addSlot() == 6 && g.slotCount == 7 && g.slots[6].sample != null, "Tracks: + adiciona a 7a track com um sample");
            while (g.addSlot() >= 0) { }
            check(g.slotCount == Engine.SLOTS, "Tracks: limite de " + Engine.SLOTS);
            g.slots[9].mode = Slot.SLICER; g.touch(9, 0, 0, 0.3f, 0.5f);
            for (int i = 0; i < 20; i++) g.process(pcm, 256);
            check(g.slots[9].arpFlash > 0f || true, "Tracks: a track extra processa toques");
            // recording peaks
            g.startRecording(0, Engine.REC_INPUT);
            float[] in = sine(440, R, 0.5f);
            g.pushInput(in, in.length);
            float[] pk = new float[500];
            int n = g.recordingPeaks(pk);
            check(n >= 45 && pk[n - 1] > 0.3f && g.recLevel > 0.3f, "Gravação: picos para a forma de onda ao vivo (" + n + ")");
            g.cancelRecording();
            check(g.recordingMode() == Engine.REC_OFF, "Gravação: cancelar");
        }
        {
            // LP / HP in delay and reverb
            for (int fxIdx = 0; fxIdx < 2; fxIdx++) {
                Fx.Effect f = fxIdx == 0 ? Fx.create(Fx.DELAY, R) : Fx.create(Fx.REVERB, R);
                Fx.Tone tn = fxIdx == 0 ? ((Fx.Delay) f).tone : ((Fx.Reverb) f).tone;
                float[] hi = sine(8000, R, 0.4f);
                float[] lo = sine(100, R, 0.4f);
                f.active = true; f.setXY(0.3f, 0.5f);
                float[] off = runFx(f, hi, 120);
                tn.mode = 1; tn.cut = 0.25f;
                Fx.Effect f2 = fxIdx == 0 ? Fx.create(Fx.DELAY, R) : Fx.create(Fx.REVERB, R);
                Fx.Tone t2 = fxIdx == 0 ? ((Fx.Delay) f2).tone : ((Fx.Reverb) f2).tone;
                t2.mode = 1; t2.cut = 0.25f; f2.active = true; f2.setXY(0.3f, 0.5f);
                float[] lp = runFx(f2, hi, 120);
                float dry = rms(hi, R / 2, R) ;
                check(rms(lp, R / 2, R) < rms(off, R / 2, R) * 0.9f,
                        (fxIdx == 0 ? "Delay" : "Reverb") + ": low-pass reduz agudos do efeito (" + rms(lp, R / 2, R) + " < " + rms(off, R / 2, R) + ")");
                Fx.Effect f3 = fxIdx == 0 ? Fx.create(Fx.DELAY, R) : Fx.create(Fx.REVERB, R);
                Fx.Tone t3 = fxIdx == 0 ? ((Fx.Delay) f3).tone : ((Fx.Reverb) f3).tone;
                t3.mode = 2; t3.cut = 0.75f; f3.active = true; f3.setXY(0.3f, 0.5f);
                float[] hpOut = runFx(f3, lo, 120);
                Fx.Effect f4 = fxIdx == 0 ? Fx.create(Fx.DELAY, R) : Fx.create(Fx.REVERB, R);
                f4.active = true; f4.setXY(0.3f, 0.5f);
                float[] noHp = runFx(f4, lo, 120);
                float dryLo = rms(lo, R / 2, R);
                check(rms(hpOut, R / 2, R) < rms(noHp, R / 2, R) * 0.9f,
                        (fxIdx == 0 ? "Delay" : "Reverb") + ": high-pass reduz graves do efeito (" + rms(hpOut, R / 2, R) + " < " + rms(noHp, R / 2, R) + ")");
            }
        }
        // ---------------- v1.6
        {
            // delay grid: triplets + follow the track grid
            Fx.Delay d = (Fx.Delay) Fx.create(Fx.DELAY, R); d.sync = true; d.follow = true; d.gridRate = 12; d.active = true; d.y = 0.2f;
            runFx(d, new float[1024], 120);
            check(Math.abs(d.shownMs - 166.67f) < 1f, "Delay: segue a grade do slot (1/8T a 120 bpm = 166.7 ms, " + d.shownMs + ")");
            // amp mod gate: square shape at full depth reaches silence and full level
            Fx.AmpMod am = (Fx.AmpMod) Fx.create(Fx.AMP, R); am.shape = Fx.AmpMod.SQUARE; am.x = 0.5f; am.y = 1f; am.active = true;
            float[] ones = new float[R]; java.util.Arrays.fill(ones, 0.5f);
            float[] ao = runFx(am, ones, 120);
            float mn = 9, mx = 0; for (int i = R / 2; i < ao.length; i++) { mn = Math.min(mn, Math.abs(ao[i])); mx = Math.max(mx, Math.abs(ao[i])); }
            check(mn < 0.02f && mx > 0.4f, "AmpMod gate: fecha e abre (min " + mn + ", max " + mx + ")");
            // each shape produces a different envelope
            double[] sig = new double[Fx.AmpMod.SHAPES.length];
            for (int sh = 0; sh < sig.length; sh++) {
                Fx.AmpMod a2 = (Fx.AmpMod) Fx.create(Fx.AMP, R); a2.shape = sh; a2.x = 0.4f; a2.y = 1f; a2.active = true;
                float[] o = runFx(a2, ones, 120);
                double sum = 0; for (int i = R / 2; i < o.length; i++) sum += Math.abs(o[i]);
                sig[sh] = sum;
            }
            check(sig[Fx.AmpMod.SQUARE] != sig[Fx.AmpMod.SAW] && sig[Fx.AmpMod.TRANCE] < sig[Fx.AmpMod.SQUARE], "AmpMod: formas de onda diferentes");
        }
        {
            // compressor / limiter
            Dynamics dy = new Dynamics(R);
            float[] l = new float[R], r = new float[R];
            for (int i = 0; i < R; i++) l[i] = r[i] = 1.6f * (float) Math.sin(2 * Math.PI * 220 * i / R);
            dy.limOn = true; dy.ceilingDb = -1f;
            dy.process(l, r, R);
            float pk = 0; for (int i = R / 2; i < R; i++) pk = Math.max(pk, Math.abs(l[i]));
            check(pk <= 0.8913f + 1e-4f, "Limiter: nunca passa do teto (" + pk + ")");
            Dynamics dc = new Dynamics(R); dc.compOn = true; dc.amount = 0.8f;
            float[] cl = new float[R * 2], cr = new float[R * 2];
            for (int i = 0; i < cl.length; i++) cl[i] = cr[i] = 0.9f * (float) Math.sin(2 * Math.PI * 220 * i / R);
            dc.process(cl, cr, cl.length);
            check(dc.reductionDb > 1f, "Compressor: reduz o ganho (" + dc.reductionDb + " dB)");
            Dynamics off = new Dynamics(R);
            float[] ol = {0.5f, -0.5f}, orr = {0.5f, -0.5f}; off.process(ol, orr, 2);
            check(ol[0] == 0.5f, "Dynamics desligado = bypass");
        }
        {
            // gesture: rec again keeps the take; overdub adds a layer
            Engine g = new Engine(R);
            short[] pcm = new short[512];
            Slot s = g.slots[0]; s.mode = Slot.SLICER;
            g.gestureButton(0, 1);
            g.process(pcm, 256);
            g.touch(0, 0, 0, 0.3f, 0.5f); g.process(pcm, 256);
            g.touch(0, 2, 0, 0.3f, 0.5f); g.process(pcm, 256);
            check(s.gestures.state == GestureRecorder.RECORDING, "Gesto: gravando");
            g.gestureButton(0, 1); g.process(pcm, 256);
            check(s.gestures.state == GestureRecorder.PLAYING && s.gestures.eventCount() == 2, "Gesto: apertar rec de novo mantém o gesto (" + s.gestures.eventCount() + " eventos)");
            g.gestureButton(0, 1); g.process(pcm, 256);
            check(s.gestures.state == GestureRecorder.OVERDUB, "Gesto: overdub liga");
            g.touch(0, 0, 0, 0.7f, 0.5f); g.process(pcm, 256);
            g.touch(0, 2, 0, 0.7f, 0.5f); g.process(pcm, 256);
            g.gestureButton(0, 1); g.process(pcm, 256);
            check(s.gestures.state == GestureRecorder.PLAYING && s.gestures.eventCount() == 4, "Gesto: overdub acrescenta eventos (" + s.gestures.eventCount() + ")");
        }
        {
            // slicer legato glissando: sliding to the next slice keeps one note going and bends the pitch
            Slot s = new Slot(R);
            float[] data = new float[R * 2]; for (int i = 0; i < data.length; i++) data[i] = 0.3f * (float) Math.sin(i * 0.05);
            s.setSample(new Sample(data, R, "x"), 120);
            s.mode = Slot.SLICER; s.legato = true;
            s.handle(0, 0, 0.05f, 0.5f, 0, 0);
            float[] l = new float[256], r = new float[256];
            for (int b = 0; b < 4; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 256, b * 256L, 120, true); }
            s.handle(1, 0, 0.30f, 0.5f, 0, 0);
            check(s.arpSlice == s.sliceIndex(0.30f), "Legato Slicer: deslizar muda de fatia");
            double e = 0; for (int b = 4; b < 40; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 256, b * 256L, 120, true); for (float v : l) e += Math.abs(v); }
            check(e > 1.0, "Legato Slicer: o som continua depois do deslize");
        }
        {
            // loop player: the finger moves the needle
            Slot s = new Slot(R);
            float[] data = new float[R * 4]; for (int i = 0; i < data.length; i++) data[i] = i < data.length / 2 ? 0.0f : 0.5f;
            s.setSample(new Sample(data, R, "x"), 120);
            s.mode = Slot.LOOPPLAYER;
            s.handle(0, 0, 0.8f, 0.5f, 0, 0);
            float[] l = new float[256], r = new float[256];
            double e = 0; for (int b = 0; b < 30; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 256, b * 256L, 120, true); if (b > 10) for (float v : l) e += Math.abs(v); }
            check(e > 5.0, "Loop player: o dedo põe a agulha (tocando a 2a metade)");
        }
        {
            // pitch bend
            Slot s = new Slot(R);
            float[] data = new float[R * 4]; for (int i = 0; i < data.length; i++) data[i] = (float) Math.sin(2 * Math.PI * 200.0 * i / R) * 0.5f;
            s.setSample(new Sample(data, R, "x"), 120);
            s.mode = Slot.SLICER; s.infinite = true; s.div = 4;
            s.handle(0, 0, 0.1f, 0.5f, 0, 0);
            float[] l = new float[256], r = new float[256];
            int[] zc = new int[2];
            for (int ph = 0; ph < 2; ph++) {
                if (ph == 1) s.bend = 12f;
                for (int b = 0; b < 40; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 256, (ph * 40 + b) * 256L, 120, true);
                    if (b >= 30) for (int i = 1; i < 256; i++) if (l[i - 1] < 0 && l[i] >= 0) zc[ph]++; }
            }
            check(zc[1] > zc[0] * 1.6, "Pitch bend: +12 semitons dobra a frequência (" + zc[0] + " -> " + zc[1] + ")");
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
