package com.gfdrps.android;

import android.content.Context;

import com.gfdrps.core.Engine;
import com.gfdrps.core.GestureRecorder;
import com.gfdrps.core.Sample;
import com.gfdrps.core.Slot;
import com.gfdrps.core.WavIO;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Saves/loads projects in the app's private storage: one folder per project (project.json + slotN.wav). */
public final class ProjectStore {
    private ProjectStore() {}

    private static File root(Context c) {
        File f = new File(c.getFilesDir(), "projects");
        f.mkdirs();
        return f;
    }

    public static String[] list(Context c) {
        File[] dirs = root(c).listFiles(File::isDirectory);
        if (dirs == null) return new String[0];
        String[] out = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) out[i] = dirs[i].getName();
        java.util.Arrays.sort(out);
        return out;
    }

    public static String safe(String n) {
        String s = n.trim().replaceAll("[^A-Za-z0-9 _\\-]", "_");
        return s.isEmpty() ? "projeto" : s;
    }

    public static void save(Context c, Engine e, String name) throws Exception {
        File dir = new File(root(c), safe(name));
        dir.mkdirs();
        JSONObject j = new JSONObject();
        j.put("bpm", e.bpm);
        j.put("master", e.masterVolume);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < Engine.SLOTS; i++) {
            Slot s = e.slots[i];
            JSONObject o = new JSONObject();
            o.put("mode", s.mode); o.put("volume", s.volume); o.put("attack", s.attack);
            o.put("decay", s.decay); o.put("sustain", s.sustain); o.put("arpDir", s.arpDir); o.put("arpRate", s.arpRate); o.put("release", s.release); o.put("pan", s.pan); o.put("div", s.div);
            o.put("transpose", s.transpose); o.put("infinite", s.infinite); o.put("gate", s.gate);
            o.put("legato", s.legato); o.put("arpRandom", s.arpRandom); o.put("sampleBeats", s.sampleBeats);
            o.put("reversed", s.reversed);
            o.put("quantize", s.quantize); o.put("shuffle", s.shuffle);
            GestureRecorder gr = s.gestures;
            int gn = gr.state == GestureRecorder.PLAYING ? gr.eventCount() : 0;
            if (gn > 0) {                                           // the recorded take (finger and effect-pad events)
                JSONObject g = new JSONObject();
                JSONArray gp = new JSONArray(), gt = new JSONArray(), gi = new JSONArray(), gx = new JSONArray(), gy = new JSONArray();
                for (int k = 0; k < gn; k++) {
                    gp.put(Math.round(gr.phaseAt(k) * 10000.0) / 10000.0); gt.put(gr.typeAt(k)); gi.put(gr.idAt(k));
                    gx.put(Math.round(gr.xAt(k) * 10000.0) / 10000.0); gy.put(Math.round(gr.yAt(k) * 10000.0) / 10000.0);
                }
                g.put("bars", gr.bars()); g.put("p", gp); g.put("t", gt); g.put("i", gi); g.put("x", gx); g.put("y", gy);
                o.put("gestures", g);
            }
            float[] mk = s.markers;
            if (mk != null) { JSONArray ma = new JSONArray(); for (float v : mk) ma.put((double) v); o.put("markers", ma); }
            Sample sm = s.sample;
            if (sm != null) {
                o.put("name", sm.name);
                o.put("rate", sm.sampleRate);
                try (FileOutputStream fo = new FileOutputStream(new File(dir, "slot" + i + ".wav"))) {
                    fo.write(WavIO.writeWav16(sm.data, sm.sampleRate));
                }
            }
            arr.put(o);
        }
        j.put("slots", arr);
        try (FileOutputStream fo = new FileOutputStream(new File(dir, "project.json"))) {
            fo.write(j.toString(1).getBytes("UTF-8"));
        }
    }

    public static void load(Context c, Engine e, String name) throws Exception {
        File dir = new File(root(c), safe(name));
        JSONObject j = new JSONObject(new String(readAll(new File(dir, "project.json")), "UTF-8"));
        e.bpm = j.optDouble("bpm", 120);
        e.masterVolume = (float) j.optDouble("master", 0.8);
        JSONArray arr = j.getJSONArray("slots");
        for (int i = 0; i < Engine.SLOTS && i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Slot s = e.slots[i];
            s.mode = o.optInt("mode", 0);
            s.volume = (float) o.optDouble("volume", 0.8);
            s.attack = (float) o.optDouble("attack", 0);
            s.release = (float) o.optDouble("release", 0.15);
            s.decay = (float) o.optDouble("decay", 0.25); s.sustain = (float) o.optDouble("sustain", 1.0);
            s.arpDir = o.optInt("arpDir", 0); s.arpRate = o.optInt("arpRate", 16);
            s.pan = (float) o.optDouble("pan", 0.5);
            s.div = o.optInt("div", 0);
            s.transpose = o.optInt("transpose", 0);
            s.infinite = o.optBoolean("infinite", false);
            s.gate = o.optBoolean("gate", false);
            s.legato = o.optBoolean("legato", false);
            s.arpRandom = o.optBoolean("arpRandom", false);
            s.quantize = o.optBoolean("quantize", false);
            s.shuffle = o.optBoolean("shuffle", false);
            JSONObject gj = o.optJSONObject("gestures");
            if (gj != null) {
                JSONArray gp = gj.getJSONArray("p"), gt = gj.getJSONArray("t"), gi = gj.getJSONArray("i"), gx = gj.getJSONArray("x"), gy = gj.getJSONArray("y");
                int gn = Math.min(gp.length(), Math.min(gt.length(), Math.min(gi.length(), Math.min(gx.length(), gy.length()))));
                double[] ph = new double[gn]; int[] ty = new int[gn], idd = new int[gn]; float[] xx = new float[gn], yy = new float[gn];
                for (int k = 0; k < gn; k++) { ph[k] = gp.getDouble(k); ty[k] = gt.getInt(k); idd[k] = gi.getInt(k); xx[k] = (float) gx.getDouble(k); yy[k] = (float) gy.getDouble(k); }
                e.setGestures(i, gj.optInt("bars", 4), ph, ty, idd, xx, yy);
            } else e.clearGestures(i);
            File wav = new File(dir, "slot" + i + ".wav");
            if (wav.exists()) {
                Sample sm = WavIO.read(readAll(wav), o.optString("name", "slot " + (i + 1)));
                e.loadSample(i, sm);
                final double sb = o.optDouble("sampleBeats", 8);
                final int idx = i;
                final boolean rev = o.optBoolean("reversed", false);
                JSONArray ma = o.optJSONArray("markers");
                final float[] mk = ma == null ? null : new float[ma.length()];
                if (mk != null) for (int k = 0; k < mk.length; k++) mk[k] = (float) ma.getDouble(k);
                e.post(() -> {                                 // runs after the sample is set
                    e.slots[idx].sampleBeats = sb;
                    e.slots[idx].reversed = rev;
                    e.slots[idx].markers = Slot.cleanMarkers(mk);
                });
            }
        }
    }

    public static void delete(Context c, String name) {
        File dir = new File(root(c), safe(name));
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
