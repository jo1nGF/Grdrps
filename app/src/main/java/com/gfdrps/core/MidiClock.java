package com.gfdrps.core;

/** Turns incoming MIDI clock ticks (24 per quarter note) into a tempo. Thread-safe enough for one MIDI thread. */
public final class MidiClock {
    private final long[] ring = new long[49];
    private int n, head;
    private double bpm;

    /** Feed one 0xF8 tick with its time in nanoseconds. Returns the smoothed BPM, or 0 until it has enough ticks. */
    public synchronized double tick(long nanos) {
        if (n > 0) {
            long last = ring[(head + n - 1) % ring.length];
            if (nanos - last > 500_000_000L) { n = 0; head = 0; }       // gap: the sender stopped, start over
        }
        if (n == ring.length) { head = (head + 1) % ring.length; n--; }
        ring[(head + n) % ring.length] = nanos;
        n++;
        if (n >= 13) {
            long first = ring[head], last = ring[(head + n - 1) % ring.length];
            double ticks = n - 1;
            double sec = (last - first) / 1e9;
            if (sec > 0) {
                double b = 60.0 * ticks / (24.0 * sec);
                bpm = bpm == 0 ? b : bpm * 0.7 + b * 0.3;
                if (b < 20 || b > 400) bpm = 0;
            }
        }
        return bpm;
    }

    public synchronized void reset() { n = 0; head = 0; bpm = 0; }
}
