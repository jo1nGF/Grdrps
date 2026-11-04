package com.gfdrps.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Minimal WAV (PCM 8/16/24/32 + float32) and AIFF (PCM) reader, 16-bit WAV writer. */
public final class WavIO {
    private WavIO() {}

    public static Sample read(byte[] b, String name) throws IOException {
        if (b.length < 12) throw new IOException("Arquivo muito pequeno");
        String tag = new String(b, 0, 4, "US-ASCII");
        if (tag.equals("RIFF")) return readWav(b, name);
        if (tag.equals("FORM")) return readAiff(b, name);
        throw new IOException("Formato não suportado (use WAV ou AIFF)");
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24;
    }
    private static int le16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static int be32(byte[] b, int o) {
        return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255);
    }
    private static int be16(byte[] b, int o) { return (b[o] & 255) << 8 | (b[o + 1] & 255); }

    private static Sample readWav(byte[] b, String name) throws IOException {
        int pos = 12, fmt = 1, ch = 1, rate = 44100, bits = 16;
        int dataOff = -1, dataLen = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, "US-ASCII");
            int len = le32(b, pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                fmt = le16(b, body);
                ch = le16(b, body + 2);
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
                if (fmt == 0xFFFE && len >= 26) fmt = le16(b, body + 24);
            } else if (id.equals("data")) {
                dataOff = body;
                dataLen = (len < 0 || body + len > b.length) ? b.length - body : len;
                break;
            }
            if (len < 0) break;
            pos = body + len + (len & 1);
        }
        if (dataOff < 0) throw new IOException("WAV sem dados");
        if (ch < 1) throw new IOException("WAV inválido");
        int bps = bits / 8;
        if (bps < 1) throw new IOException("WAV inválido");
        int frames = dataLen / (bps * ch);
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            float sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = dataOff + (i * ch + c) * bps;
                float v;
                if (fmt == 3 && bits == 32) v = Float.intBitsToFloat(le32(b, o));
                else if (bits == 8) v = ((b[o] & 255) - 128) / 128f;
                else if (bits == 16) v = (short) le16(b, o) / 32768f;
                else if (bits == 24) v = ((b[o] & 255) << 8 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 24) / 2147483648f;
                else if (bits == 32) v = le32(b, o) / 2147483648f;
                else throw new IOException("Profundidade de bits não suportada: " + bits);
                sum += v;
            }
            out[i] = sum / ch;
        }
        return new Sample(out, rate, name);
    }

    private static double ieee80(byte[] b, int o) {
        int exp = ((b[o] & 0x7F) << 8) | (b[o + 1] & 255);
        long hi = be32(b, o + 2) & 0xFFFFFFFFL;
        long lo = be32(b, o + 6) & 0xFFFFFFFFL;
        double m = hi * 4294967296.0 + lo;
        if (exp == 0 && m == 0) return 0;
        return m * Math.pow(2, exp - 16383 - 63);
    }

    private static Sample readAiff(byte[] b, String name) throws IOException {
        int pos = 12, ch = 1, bits = 16, rate = 44100, frames = 0;
        int dataOff = -1;
        boolean comp = false;
        String form = new String(b, 8, 4, "US-ASCII");
        if (form.equals("AIFC")) comp = true;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, "US-ASCII");
            int len = be32(b, pos + 4);
            int body = pos + 8;
            if (id.equals("COMM")) {
                ch = be16(b, body);
                frames = be32(b, body + 2);
                bits = be16(b, body + 6);
                rate = (int) Math.round(ieee80(b, body + 8));
                if (comp && len >= 22) {
                    String c = new String(b, body + 18, 4, "US-ASCII");
                    if (!c.equals("NONE")) throw new IOException("AIFF comprimido não suportado");
                }
            } else if (id.equals("SSND")) {
                int offset = be32(b, body);
                dataOff = body + 8 + offset;
                break;
            }
            if (len < 0) break;
            pos = body + len + (len & 1);
        }
        if (dataOff < 0 || ch < 1) throw new IOException("AIFF inválido");
        int bps = (bits + 7) / 8;
        int avail = (b.length - dataOff) / (bps * ch);
        if (frames > avail) frames = avail;
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            float sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = dataOff + (i * ch + c) * bps;
                float v;
                if (bps == 1) v = b[o] / 128f;
                else if (bps == 2) v = (short) be16(b, o) / 32768f;
                else if (bps == 3) v = ((b[o] << 16) | ((b[o + 1] & 255) << 8) | (b[o + 2] & 255)) / 8388608f;
                else if (bps == 4) v = be32(b, o) / 2147483648f;
                else throw new IOException("Profundidade não suportada");
                sum += v;
            }
            out[i] = sum / ch;
        }
        return new Sample(out, rate, name);
    }

    /** 16-bit mono PCM WAV. */
    public static byte[] writeWav16(float[] d, int rate) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(44 + d.length * 2);
        int dl = d.length * 2;
        w4(o, "RIFF"); w32(o, 36 + dl); w4(o, "WAVE"); w4(o, "fmt "); w32(o, 16);
        w16(o, 1); w16(o, 1); w32(o, rate); w32(o, rate * 2); w16(o, 2); w16(o, 16);
        w4(o, "data"); w32(o, dl);
        for (float f : d) {
            int v = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
            o.write(v & 255); o.write((v >> 8) & 255);
        }
        return o.toByteArray();
    }

    private static void w4(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < 4; i++) o.write(s.charAt(i));
    }
    private static void w32(ByteArrayOutputStream o, int v) {
        o.write(v & 255); o.write((v >> 8) & 255); o.write((v >> 16) & 255); o.write((v >> 24) & 255);
    }
    private static void w16(ByteArrayOutputStream o, int v) { o.write(v & 255); o.write((v >> 8) & 255); }
}
