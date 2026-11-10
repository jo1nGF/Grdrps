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

    /** Effect pad positions (and delay sync / filter type) so a project comes back sounding the same. */
    private static JSONArray fxJson(com.gfdrps.core.Fx.Effect[] fx) throws Exception {
        JSONArray a = new JSONArray();
        for (com.gfdrps.core.Fx.Effect f : fx) {
            JSONObject o = new JSONObject();
            o.put("x", f.x); o.put("y", f.y);
            if (f instanceof com.gfdrps.core.Fx.Delay) o.put("sync", ((com.gfdrps.core.Fx.Delay) f).sync);
            if (f instanceof com.gfdrps.core.Fx.Filter) o.put("hp", ((com.gfdrps.core.Fx.Filter) f).highPass);
            com.gfdrps.core.Fx.Tone tn = com.gfdrps.core.Fx.toneOf(f);
            if (tn != null) { o.put("tone", tn.mode); o.put("cut", tn.cut); }
            if (f instanceof com.gfdrps.core.Fx.Delay) o.put("follow", ((com.gfdrps.core.Fx.Delay) f).follow);
            if (f instanceof com.gfdrps.core.Fx.AmpMod) { o.put("shape", ((com.gfdrps.core.Fx.AmpMod) f).shape); o.put("async", ((com.gfdrps.core.Fx.AmpMod) f).sync); }
            a.put(o);
        }
        return a;
    }

    private static void fxLoad(JSONArray a, com.gfdrps.core.Fx.Effect[] fx) {
        for (int k = 0; k < fx.length && k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            fx[k].setXY((float) o.optDouble("x", fx[k].x), (float) o.optDouble("y", fx[k].y));
            fx[k].active = false;
            if (fx[k] instanceof com.gfdrps.core.Fx.Delay) ((com.gfdrps.core.Fx.Delay) fx[k]).sync = o.optBoolean("sync", false);
            if (fx[k] instanceof com.gfdrps.core.Fx.Filter) ((com.gfdrps.core.Fx.Filter) fx[k]).highPass = o.optBoolean("hp", false);
            com.gfdrps.core.Fx.Tone tn = com.gfdrps.core.Fx.toneOf(fx[k]);
            if (tn != null) { tn.mode = o.optInt("tone", 0); tn.cut = (float) o.optDouble("cut", 0.5); }
            if (fx[k] instanceof com.gfdrps.core.Fx.Delay) ((com.gfdrps.core.Fx.Delay) fx[k]).follow = o.optBoolean("follow", false);
            if (fx[k] instanceof com.gfdrps.core.Fx.AmpMod) { ((com.gfdrps.core.Fx.AmpMod) fx[k]).shape = o.optInt("shape", 1); ((com.gfdrps.core.Fx.AmpMod) fx[k]).sync = o.optBoolean("async", false); }
        }
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
        j.put("mfx", fxJson(e.master));
        j.put("fxHold", e.fxHold);
        j.put("tracks", e.slotCount);
        { JSONObject dj = new JSONObject(); dj.put("comp", e.dyn.compOn); dj.put("lim", e.dyn.limOn); dj.put("amt", e.dyn.amount); dj.put("ceil", e.dyn.ceilingDb); j.put("dyn", dj); }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < e.slotCount; i++) {
            Slot s = e.slots[i];
            JSONObject o = new JSONObject();
            o.put("mode", s.mode); o.put("volume", s.volume); o.put("attack", s.attack);
            o.put("decay", s.decay); o.put("sustain", s.sustain); o.put("arpDir", s.arpDir); o.put("arpRate", s.arpRate); o.put("release", s.release); o.put("pan", s.pan); o.put("div", s.div);
            o.put("transpose", s.transpose); o.put("fine", s.fine); o.put("infinite", s.infinite); o.put("gate", s.gate);
            o.put("legato", s.legato); o.put("arpRandom", s.arpRandom); o.put("sampleBeats", s.sampleBeats);
            o.put("reversed", s.reversed);
            o.put("quantize", s.quantize); o.put("shuffle", s.shuffle);
            o.put("pingPong", s.pingPong); o.put("stretch", s.stretch); o.put("bowSize", s.bowSize);
            o.put("regStart", s.regStart); o.put("regEnd", s.regEnd);
            o.put("fx", fxJson(s.fx));
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
        e.fxHold = j.optBoolean("fxHold", false);
        JSONObject dj = j.optJSONObject("dyn");
        if (dj != null) { e.dyn.compOn = dj.optBoolean("comp", false); e.dyn.limOn = dj.optBoolean("lim", false); e.dyn.amount = (float) dj.optDouble("amt", 0.4); e.dyn.ceilingDb = (float) dj.optDouble("ceil", -0.5); }
        JSONArray mfx = j.optJSONArray("mfx");
        if (mfx != null) fxLoad(mfx, e.master);
        e.clearUndo();
        JSONArray arr = j.getJSONArray("slots");
        e.ensureSlots(Math.max(j.optInt("tracks", 6), Math.min(Engine.SLOTS, arr.length())));
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
            s.transpose = o.optInt("transpose", 0); s.fine = (float) o.optDouble("fine", 0);
            s.infinite = o.optBoolean("infinite", false);
            s.gate = o.optBoolean("gate", false);
            s.legato = o.optBoolean("legato", false);
            s.arpRandom = o.optBoolean("arpRandom", false);
            s.quantize = o.optBoolean("quantize", false);
            s.pingPong = o.optBoolean("pingPong", false); s.stretch = o.optBoolean("stretch", false);
            s.bowSize = (float) o.optDouble("bowSize", 0.45);
            s.regStart = (float) o.optDouble("regStart", 0.0); s.regEnd = (float) o.optDouble("regEnd", 1.0);
            JSONArray fxa = o.optJSONArray("fx");
            if (fxa != null) fxLoad(fxa, s.fx);
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

    // ---------------------------------------------------------------- gesture presets (a take saved by name, reusable on any track)
    private static File presetDir(Context c) { File f = new File(root(c).getParentFile(), "gesture_presets"); f.mkdirs(); return f; }

    public static String[] listGesturePresets(Context c) {
        File[] fs = presetDir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return new String[0];
        String[] out = new String[fs.length];
        for (int i = 0; i < fs.length; i++) out[i] = fs[i].getName().replaceAll("\\.json$", "");
        java.util.Arrays.sort(out);
        return out;
    }

    public static void saveGesturePreset(Context c, GestureRecorder gr, String name) throws Exception {
        int gn = gr.eventCount();
        if (gn == 0) throw new IOException("Nenhum gesto gravado");
        JSONObject g = new JSONObject();
        JSONArray gp = new JSONArray(), gt = new JSONArray(), gi = new JSONArray(), gx = new JSONArray(), gy = new JSONArray();
        for (int k = 0; k < gn; k++) {
            gp.put(Math.round(gr.phaseAt(k) * 10000.0) / 10000.0); gt.put(gr.typeAt(k)); gi.put(gr.idAt(k));
            gx.put(Math.round(gr.xAt(k) * 10000.0) / 10000.0); gy.put(Math.round(gr.yAt(k) * 10000.0) / 10000.0);
        }
        g.put("bars", gr.bars()); g.put("p", gp); g.put("t", gt); g.put("i", gi); g.put("x", gx); g.put("y", gy);
        try (FileOutputStream fo = new FileOutputStream(new File(presetDir(c), safe(name) + ".json"))) { fo.write(g.toString().getBytes("UTF-8")); }
    }

    public static void loadGesturePreset(Context c, Engine e, int slot, String name) throws Exception {
        JSONObject gj = new JSONObject(new String(readAll(new File(presetDir(c), safe(name) + ".json")), "UTF-8"));
        JSONArray gp = gj.getJSONArray("p"), gt = gj.getJSONArray("t"), gi = gj.getJSONArray("i"), gx = gj.getJSONArray("x"), gy = gj.getJSONArray("y");
        int gn = Math.min(gp.length(), Math.min(gt.length(), Math.min(gi.length(), Math.min(gx.length(), gy.length()))));
        double[] ph = new double[gn]; int[] ty = new int[gn], idd = new int[gn]; float[] xx = new float[gn], yy = new float[gn];
        for (int k = 0; k < gn; k++) { ph[k] = gp.getDouble(k); ty[k] = gt.getInt(k); idd[k] = gi.getInt(k); xx[k] = (float) gx.getDouble(k); yy[k] = (float) gy.getDouble(k); }
        e.setGestures(slot, gj.optInt("bars", 4), ph, ty, idd, xx, yy);
    }

    public static void deleteGesturePreset(Context c, String name) { new File(presetDir(c), safe(name) + ".json").delete(); }

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
