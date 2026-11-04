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
    }

    public void start() {
        if (running) return;
        running = true;
        track.play();
        thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] buf = new short[chunkFrames * 2];
            while (running) {
                engine.process(buf, chunkFrames);
                track.write(buf, 0, buf.length);
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
