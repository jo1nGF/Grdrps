package com.gfdrps.android;

import android.content.Context;

import com.gfdrps.core.Engine;
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
            o.put("release", s.release); o.put("pan", s.pan); o.put("div", s.div);
            o.put("transpose", s.transpose); o.put("infinite", s.infinite); o.put("gate", s.gate);
            o.put("legato", s.legato); o.put("arpRandom", s.arpRandom); o.put("sampleBeats", s.sampleBeats);
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
            s.pan = (float) o.optDouble("pan", 0.5);
            s.div = o.optInt("div", 8);
            s.transpose = o.optInt("transpose", 0);
            s.infinite = o.optBoolean("infinite", false);
            s.gate = o.optBoolean("gate", false);
            s.legato = o.optBoolean("legato", false);
            s.arpRandom = o.optBoolean("arpRandom", false);
            File wav = new File(dir, "slot" + i + ".wav");
            if (wav.exists()) {
                Sample sm = WavIO.read(readAll(wav), o.optString("name", "slot " + (i + 1)));
                e.loadSample(i, sm);
                final double sb = o.optDouble("sampleBeats", 8);
                final int idx = i;
                e.post(() -> e.slots[idx].sampleBeats = sb);   // runs after the sample is set
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
