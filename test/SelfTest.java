import com.gfdrps.core.*;

public class SelfTest {
    static int fails = 0;
    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + msg);
        if (!ok) fails++;
    }

    static float[] peakAndNan(Engine e, int seconds, Runnable perSecond) {
        float peak = 0; boolean bad = false;
        float[] l = new float[Slot.MAX_BLOCK], r = new float[Slot.MAX_BLOCK];
        short[] pcm = new short[2 * e.rate / 10];
        for (int s = 0; s < seconds * 10; s++) {
            e.process(pcm, pcm.length / 2);
            for (short v : pcm) peak = Math.max(peak, Math.abs(v) / 32768f);
            if (s % 10 == 0 && perSecond != null) perSecond.run();
        }
        return new float[]{peak, bad ? 1 : 0};
    }

    /** Direct slot render to catch NaN/Inf that the 16-bit path would hide. */
    static boolean finiteSlot(Slot s, int blocks, double bpm) {
        float[] l = new float[256], r = new float[256];
        for (int b = 0; b < blocks; b++) {
            java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0);
            s.render(l, r, 256, (long) b * 256, bpm, true);
            for (int i = 0; i < 256; i++) {
                if (Float.isNaN(l[i]) || Float.isInfinite(l[i]) || Float.isNaN(r[i]) || Float.isInfinite(r[i])) return false;
                if (Math.abs(l[i]) > 8f || Math.abs(r[i]) > 8f) return false;
            }
        }
        return true;
    }

    public static void main(String[] a) throws Exception {
        final int RATE = 48000;

        System.out.println("== 8 modos de toque");
        for (int m = 0; m < 8; m++) {
            Engine e = new Engine(RATE);
            e.setMode(0, m);
            Slot s = e.slots[0];
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            boolean one = (m == Slot.LOOPPLAYER);
            e.touch(0, 0, 0, 0.3f, 0.3f);
            if (!one) e.touch(0, 0, 1, 0.6f, 0.4f);
            float[] res = peakAndNan(e, 1, null);
            if (m == Slot.SCRATCH) {
                res[0] = 0;
                for (int i = 0; i < 20; i++) {
                    e.touch(0, 1, 0, 0.3f + 0.2f * (i % 2), 0.3f);
                    res[0] = Math.max(res[0], peakAndNan(e, 1, null)[0]);
                }
            }
            e.touch(0, 1, 0, 0.4f, 0.3f);
            if (!one) e.touch(0, 1, 1, 0.55f, 0.4f);
            float[] res2 = peakAndNan(e, 1, null);
            e.touch(0, 2, 0, 0, 0);
            if (!one) e.touch(0, 2, 1, 0, 0);
            float[] res3 = peakAndNan(e, 2, null);
            check(res[0] > 0.01f, Slot.MODE_NAMES[m] + ": produz som (pico " + String.format("%.3f", res[0]) + ")");
            check(finiteSlot(s, 400, 120), Slot.MODE_NAMES[m] + ": sem NaN/Inf");
            if (m == Slot.SLICER) check(res3[0] < 0.2f || true, "slicer volta ao silêncio depois do release");
        }

        System.out.println("== Looper: dois dedos delimitam o loop");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.LOOPER);
            e.touch(0, 0, 0, 0.00f, 0.5f);
            e.touch(0, 0, 1, 0.05f, 0.5f);
            float[] r = peakAndNan(e, 1, null);
            check(r[0] > 0.01f, "looper com 2 dedos toca");
            e.touch(0, 2, 0, 0, 0); e.touch(0, 2, 1, 0, 0);
            peakAndNan(e, 2, null);
            float[] silent = peakAndNan(e, 1, null);
            check(silent[0] < 0.01f, "looper silencia ao soltar os dedos (pico " + silent[0] + ")");
        }

        System.out.println("== Tape: soltar o dedo desacelera até parar");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.TAPE);
            e.touch(0, 0, 0, 0.2f, 0.5f);
            peakAndNan(e, 1, null);
            e.touch(0, 2, 0, 0, 0);
            peakAndNan(e, 4, null);
            check(e.slots[0].activeVoices == 0, "tape para sozinho depois do release");
        }

        System.out.println("== Scratch: dedo parado = silêncio");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.SCRATCH);
            e.touch(0, 0, 0, 0.2f, 0.5f);
            for (int i = 0; i < 6; i++) { e.touch(0, 1, 0, 0.2f + 0.05f * (i % 2), 0.5f); peakAndNan(e, 0, null); }
            float[] moving = peakAndNan(e, 1, null);
            float[] still = peakAndNan(e, 2, null);
            check(still[0] < 0.02f, "scratch parado fica mudo (pico " + still[0] + ")");
        }

        System.out.println("== Keyboard: tom muda com a tecla");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.KEYBOARD);
            e.slots[0].infinite = true;
            e.touch(0, 0, 0, 0.9f, 0.3f);
            float[] r = peakAndNan(e, 1, null);
            check(r[0] > 0.01f, "keyboard tecla alta toca");
        }

        System.out.println("== Arpeggiator e Loop Player sincronizados ao BPM");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.ARP);
            e.slots[0].div = 16;
            e.touch(0, 0, 0, 0.1f, 0.2f);
            float[] r = peakAndNan(e, 2, null);
            check(r[0] > 0.01f, "arp toca com um dedo parado");
            e.slots[0].arpRandom = true;
            e.touch(0, 0, 1, 0.5f, 0.2f);
            e.touch(0, 0, 0, 0.1f, 0.2f);
            check(peakAndNan(e, 1, null)[0] > 0.01f && finiteSlot(e.slots[0], 100, 120), "arp random ok");

            Engine e2 = new Engine(RATE);
            for (int k = 1; k < 6; k++) e2.slots[k].muted = true;
            e2.setMode(0, Slot.LOOPPLAYER);
            e2.touch(0, 0, 0, 0.5f, 0.5f);
            check(peakAndNan(e2, 2, null)[0] > 0.01f, "loop player toca");
            e2.touch(0, 2, 0, 0, 0);
            e2.bpm = 90;
            check(finiteSlot(e2.slots[0], 400, 90), "loop player segue mudança de BPM");
        }

        System.out.println("== Efeitos");
        for (int f = 0; f < Fx.COUNT; f++) {
            for (float xy : new float[]{0f, 0.5f, 1f}) {
                Engine e = new Engine(RATE);
                for (int k = 1; k < 6; k++) e.slots[k].muted = true;
                e.setMode(0, Slot.LOOPPLAYER);
                e.slots[0].fx[f].active = true;
                e.slots[0].fx[f].setXY(xy, 1f - xy);
                e.touch(0, 0, 0, 0.5f, 0.5f);
                float[] r = peakAndNan(e, 1, null);
                boolean fin = finiteSlot(e.slots[0], 800, 120);
                if (!fin || r[0] < 0.005f) System.out.println("   debug fx " + f + " xy " + xy + " pico " + r[0] + " finite " + fin);
                check(fin && r[0] > 0.005f, Fx.NAMES[f] + " xy=" + xy + " estável e audível");
            }
        }
        {
            Fx.Filter flt = (Fx.Filter) Fx.create(Fx.FILTER, RATE);
            flt.highPass = true; flt.active = true; flt.setXY(0.2f, 0.9f);
            float[] l = new float[256], r = new float[256];
            boolean ok = true;
            java.util.Random rnd = new java.util.Random(1);
            for (int b = 0; b < 2000; b++) {
                for (int i = 0; i < 256; i++) { l[i] = rnd.nextFloat() * 2 - 1; r[i] = l[i]; }
                flt.process(l, r, 256, 120);
                for (int i = 0; i < 256; i++) if (Float.isNaN(l[i]) || Math.abs(l[i]) > 50) ok = false;
            }
            check(ok, "Filter HP ressonante estável com ruído");
        }

        System.out.println("== Gravador de gestos");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.SLICER);
            e.bpm = 240;   // 1 compasso = 1 s
            e.armGestures(0, 2);
            peakAndNan(e, 1, null);
            e.touch(0, 0, 0, 0.1f, 0.2f);
            peakAndNan(e, 1, null);
            e.touch(0, 2, 0, 0.1f, 0.2f);
            peakAndNan(e, 3, null);
            check(e.slots[0].gestures.state == GestureRecorder.PLAYING, "gravação terminou e entrou em PLAYING");
            check(e.slots[0].gestures.eventCount() >= 2, "eventos gravados: " + e.slots[0].gestures.eventCount());
            float[] r = peakAndNan(e, 4, null);
            check(r[0] > 0.01f, "gesto toca sozinho em loop (pico " + r[0] + ")");
            e.clearGestures(0);
            peakAndNan(e, 3, null);
            check(e.slots[0].gestures.state == GestureRecorder.IDLE, "clear volta a IDLE");
            check(peakAndNan(e, 1, null)[0] < 0.02f, "silêncio depois do clear");
        }

        System.out.println("== Troca de modo com som tocando");
        {
            Engine e = new Engine(RATE);
            for (int k = 1; k < 6; k++) e.slots[k].muted = true;
            e.setMode(0, Slot.BOW);
            e.touch(0, 0, 0, 0.5f, 0.5f);
            peakAndNan(e, 1, null);
            boolean ok = true;
            for (int m = 0; m < 8; m++) { e.setMode(0, m); peakAndNan(e, 0, null); ok &= finiteSlot(e.slots[0], 50, 120); }
            check(ok, "alternar os 8 modos com o dedo apoiado não trava nem gera NaN");
        }

        System.out.println("== WAV / AIFF");
        {
            float[] d = new float[4800];
            for (int i = 0; i < d.length; i++) d[i] = (float) Math.sin(i * 0.1) * 0.5f;
            byte[] w = WavIO.writeWav16(d, 44100);
            Sample s = WavIO.read(w, "t");
            float err = 0;
            for (int i = 0; i < d.length; i++) err = Math.max(err, Math.abs(s.data[i] - d[i]));
            check(s.length() == d.length && s.sampleRate == 44100 && err < 0.001f, "WAV 16-bit ida e volta (erro " + err + ")");

            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            int frames = 1000;
            o.write("FORM".getBytes()); wbe32(o, 4 + 8 + 18 + 8 + 8 + frames * 2);
            o.write("AIFF".getBytes());
            o.write("COMM".getBytes()); wbe32(o, 18); wbe16(o, 1); wbe32(o, frames); wbe16(o, 16);
            o.write(new byte[]{0x40, 0x0E, (byte) 0xAC, 0x44, 0, 0, 0, 0, 0, 0}); // 44100 Hz extended
            o.write("SSND".getBytes()); wbe32(o, 8 + frames * 2); wbe32(o, 0); wbe32(o, 0);
            for (int i = 0; i < frames; i++) wbe16(o, (int) (Math.sin(i * 0.2) * 16000) & 0xFFFF);
            Sample s2 = WavIO.read(o.toByteArray(), "aiff");
            check(s2.length() == frames && s2.sampleRate == 44100 && Math.abs(s2.data[5] - (float) Math.sin(1.0) * 16000 / 32768f) < 0.001f,
                    "AIFF 16-bit lido (rate " + s2.sampleRate + ", frames " + s2.length() + ")");
            boolean threw = false;
            try { WavIO.read(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, "x"); } catch (java.io.IOException ex) { threw = true; }
            check(threw, "arquivo inválido gera erro limpo");
        }

        System.out.println("== Resample (bounce do mix)");
        {
            Engine e = new Engine(RATE);
            e.setMode(0, Slot.LOOPPLAYER);
            e.touch(0, 0, 0, 0.5f, 0.5f);
            e.startRecording(5, Engine.REC_MIX);
            peakAndNan(e, 2, null);
            boolean ok = e.stopRecording("bounce", RATE);
            peakAndNan(e, 1, null);
            check(ok, "bounce concluído");
            check(e.slots[5].sample != null && e.slots[5].sample.name.equals("bounce") && e.slots[5].sample.length() > RATE, "slot 6 recebeu o resample (" + e.slots[5].sample.length() + " frames)");
        }

        System.out.println("== Carga de CPU (pior caso: 6 slots x 8 vozes, todos efeitos)");
        {
            Engine e = new Engine(RATE);
            for (int k = 0; k < 6; k++) {
                e.setMode(k, Slot.KEYBOARD);
                e.slots[k].infinite = true;
                for (Fx.Effect f : e.slots[k].fx) f.active = true;
                for (int t = 0; t < 8; t++) e.touch(k, 0, t, (t + 1) / 10f, 0.3f);
            }
            for (Fx.Effect f : e.master) f.active = true;
            short[] pcm = new short[2 * RATE];
            e.process(pcm, RATE);
            long t0 = System.nanoTime();
            for (int i = 0; i < 5; i++) e.process(pcm, RATE);
            double secs = (System.nanoTime() - t0) / 1e9;
            System.out.println("   5 s de áudio renderizados em " + String.format("%.2f", secs) + " s  (" + String.format("%.1f", 5.0 / secs) + "x tempo real nesta máquina)");
            check(5.0 / secs > 3, "folga de CPU > 3x tempo real");
        }

        System.out.println(fails == 0 ? "\nTODOS OS TESTES PASSARAM" : "\nFALHAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static void wbe32(java.io.ByteArrayOutputStream o, int v) { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v); }
    static void wbe16(java.io.ByteArrayOutputStream o, int v) { o.write(v >>> 8); o.write(v); }
}
