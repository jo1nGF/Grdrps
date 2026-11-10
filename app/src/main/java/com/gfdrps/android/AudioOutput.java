package com.gfdrps.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

import com.gfdrps.core.Engine;

/** Streams the engine to an AudioTrack on a high-priority thread, requesting low-latency mode. */
public final class AudioOutput {
    private final Engine engine;
    private final AudioTrack track;
    private final int chunkFrames;
    private volatile boolean running;
    private Thread thread;
    private final int burst;

    /** Output rate the device prefers (48000 on Galaxy S10). */
    public static int preferredRate(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            String r = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            if (r != null) return Integer.parseInt(r);
        } catch (Exception ignored) { }
        return 48000;
    }

    public AudioOutput(Context c, Engine engine) {
        this.engine = engine;
        int rate = engine.rate;
        int minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int framesPerBuf = 256;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            String f = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            if (f != null) framesPerBuf = Math.max(64, Integer.parseInt(f));
        } catch (Exception ignored) { }
        chunkFrames = framesPerBuf;
        burst = framesPerBuf;
        int bufBytes = Math.max(minBytes, framesPerBuf * 4 * 2);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        try { track.setBufferSizeInFrames(burst); } catch (Exception ignored) { }   // smallest buffer that is stable; grows on underruns
    }

    public void start() {
        if (running) return;
        running = true;
        track.play();
        thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] buf = new short[chunkFrames * 2];
            int lastUnderruns = 0, bufFrames = burst, check = 0;
            while (running) {
                try {
                    engine.process(buf, chunkFrames);
                } catch (Throwable t) {                 // never let an audio bug kill the app: output silence for this chunk
                    java.util.Arrays.fill(buf, (short) 0);
                }
                int w = track.write(buf, 0, buf.length);
                if (w < 0) break;
                if (++check >= 16) {                    // adaptive latency: starts at ONE burst, adds a burst after an underrun (up to 6)
                    check = 0;
                    try {
                        int u = track.getUnderrunCount();
                        if (u > lastUnderruns && bufFrames < burst * 6) { bufFrames += burst; track.setBufferSizeInFrames(bufFrames); }
                        lastUnderruns = u;
                    } catch (Exception ignored) { }
                }
            }
        }, "gfdrps-audio");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        try { track.pause(); track.flush(); } catch (Exception ignored) { }
    }

    public void release() {
        stop();
        track.release();
    }
}
