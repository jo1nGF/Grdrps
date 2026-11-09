package com.gfdrps.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.gfdrps.core.Sample;
import com.gfdrps.core.WavIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Imports WAV/AIFF directly; anything else the phone can decode (MP3, M4A, OGG, FLAC) goes through MediaCodec. */
public final class SampleLoader {
    private static final int MAX_SECONDS = 240;          // longer files are cut here (keeps memory safe)
    private static final long MAX_DIRECT_BYTES = 96L * 1024 * 1024;
    private SampleLoader() {}

    public static String displayName(Context c, Uri uri) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = cur.getString(i);
            }
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "sample";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static Sample load(Context c, Uri uri) throws IOException {
        String name = displayName(c, uri);
        long size = -1;
        try (android.content.res.AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (fd != null) size = fd.getLength();
        } catch (Exception ignored) { }
        if (size > MAX_DIRECT_BYTES) return decode(c, uri, name);        // big files are streamed through the codec, never held whole in memory
        byte[] bytes = readAll(c.getContentResolver(), uri);
        if (bytes.length > 12) {
            String tag = new String(bytes, 0, 4, "US-ASCII");
            if (tag.equals("RIFF") || tag.equals("FORM")) {
                try { return WavIO.read(bytes, name); } catch (IOException ignored) { /* fall through to codec */ }
            }
        }
        return decode(c, uri, name);
    }

    private static byte[] readAll(ContentResolver r, Uri uri) throws IOException {
        try (InputStream in = r.openInputStream(uri)) {
            if (in == null) throw new IOException("Não foi possível abrir o arquivo");
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_DIRECT_BYTES) throw new IOException("Arquivo muito grande");
            }
            return out.toByteArray();
        }
    }

    private static Sample decode(Context c, Uri uri, String name) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("Formato de áudio não suportado");
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int guess = durUs > 0 ? (int) Math.min((long) rate * MAX_SECONDS, durUs * rate / 1_000_000L + rate) : rate * 10;
            float[] data = new float[Math.max(rate, guess)];
            int len = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    ob.order(ByteOrder.nativeOrder());
                    java.nio.ShortBuffer sb = ob.asShortBuffer();
                    int frames = sb.remaining() / Math.max(1, ch);
                    if (len + frames > data.length) {
                        int cap = rate * MAX_SECONDS;
                        if (len + frames > cap) { frames = Math.max(0, cap - len); outDone = true; inDone = true; }
                        if (len + frames > data.length) data = java.util.Arrays.copyOf(data, Math.min(cap, Math.max(data.length + data.length / 2, len + frames)));
                    }
                    for (int i = 0; i < frames; i++) {
                        float sum = 0;
                        for (int k = 0; k < ch; k++) sum += sb.get() / 32768f;
                        data[len++] = sum / ch;
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            if (len < 64) throw new IOException("Arquivo sem áudio");
            return new Sample(java.util.Arrays.copyOf(data, len), rate, name);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Não foi possível decodificar: " + e.getMessage());
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            ex.release();
        }
    }
}
