package com.gfdrps.android;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.gfdrps.core.Engine;

/** Captures the microphone and feeds the engine's recording buffer. Needs RECORD_AUDIO permission. */
public final class MicRecorder {
    private final Engine engine;
    private AudioRecord rec;
    private volatile boolean running;
    private Thread thread;

    public MicRecorder(Engine engine) { this.engine = engine; }

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) return true;
        int rate = engine.rate;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 2));
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; return false; }
            rec.startRecording();
        } catch (Exception e) {
            return false;
        }
        running = true;
        thread = new Thread(() -> {
            short[] s = new short[1024];
            float[] f = new float[1024];
            while (running) {
                int n = rec.read(s, 0, s.length);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) f[i] = s[i] / 32768f;
                engine.pushInput(f, n);
            }
        }, "gfdrps-mic");
        thread.start();
        return true;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
        }
    }
}
