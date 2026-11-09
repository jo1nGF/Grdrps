package com.gfdrps.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Saves WAV files to the phone's Music/GfDrps folder (visible to the Files app and to other music apps). */
final class Exporter {
    private Exporter() { }

    /** Stereo/mono 16-bit PCM, interleaved. Returns a human readable place, e.g. "Music/GfDrps/nome.wav". */
    static String saveWav(Context c, String name, short[] pcm, int rate, int channels) throws IOException {
        String file = safe(name) + ".wav";
        try (OutputStream out = open(c, file)) {
            int dataBytes = pcm.length * 2;
            out.write(header(dataBytes, rate, channels));
            byte[] buf = new byte[1 << 16];
            int bi = 0;
            for (short v : pcm) {
                buf[bi++] = (byte) v; buf[bi++] = (byte) (v >> 8);
                if (bi == buf.length) { out.write(buf, 0, bi); bi = 0; }
            }
            if (bi > 0) out.write(buf, 0, bi);
        }
        return "Music/GfDrps/" + file;
    }

    /** One slot's sample (mono float) as a 16-bit WAV. */
    static String saveSample(Context c, String name, float[] data, int rate) throws IOException {
        short[] pcm = new short[data.length];
        for (int i = 0; i < data.length; i++) {
            float v = data[i];
            pcm[i] = (short) (Math.max(-1f, Math.min(1f, v)) * 32767f);
        }
        return saveWav(c, name, pcm, rate, 1);
    }

    private static byte[] header(int dataBytes, int rate, int ch) {
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataBytes); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, ch); le32(h, 24, rate); le32(h, 28, rate * ch * 2); le16(h, 32, ch * 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataBytes);
        return h;
    }
    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }

    private static String safe(String n) {
        String s = n == null ? "gfdrps" : n.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return s.isEmpty() ? "gfdrps" : s;
    }

    private static OutputStream open(Context c, String file) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, file);
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/GfDrps");
            ContentResolver r = c.getContentResolver();
            Uri uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("não foi possível criar o arquivo");
            OutputStream o = r.openOutputStream(uri);
            if (o == null) throw new IOException("não foi possível abrir o arquivo");
            return o;
        }
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "GfDrps");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("pasta indisponível");
        return new FileOutputStream(new File(dir, file));
    }
}
