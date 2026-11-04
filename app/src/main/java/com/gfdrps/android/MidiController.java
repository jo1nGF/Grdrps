package com.gfdrps.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;

import com.gfdrps.core.Engine;
import com.gfdrps.core.Slot;

import java.util.HashMap;
import java.util.Map;

/** USB / Bluetooth MIDI input: notes play the selected slot, CCs control the app through MIDI Learn. */
public final class MidiController {
    public static final int T_MASTER = 0, T_TEMPO = 1, T_SLOT_VOL = 2 /* ..7 */, T_FX_X = 8, T_FX_Y = 9, T_PAN = 10 /* ..15 */;
    public interface Listener { void onMidiStatus(String text); void onLearned(); }

    private final Context ctx;
    private final Engine engine;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Integer> bindings = new HashMap<>();   // (channel<<8|cc) -> target
    private final SharedPreferences prefs;
    private MidiManager mm;
    private MidiDevice device;
    private MidiOutputPort port;
    private Listener listener;

    public volatile int selectedSlot = 0;
    public volatile int selectedFx = 0;
    private volatile int learnTarget = -1;

    public MidiController(Context c, Engine e) {
        ctx = c.getApplicationContext();
        engine = e;
        prefs = ctx.getSharedPreferences("midi", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
            try { bindings.put(Integer.parseInt(en.getKey()), (Integer) en.getValue()); } catch (Exception ignored) { }
        }
    }

    public void setListener(Listener l) { listener = l; }

    public void startLearn(int target) { learnTarget = target; }
    public boolean isLearning() { return learnTarget >= 0; }

    public void clearBindings() {
        bindings.clear();
        prefs.edit().clear().apply();
    }

    public int bindingCount() { return bindings.size(); }

    public void connect() {
        mm = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
        if (mm == null) { status("MIDI indisponível"); return; }
        MidiDeviceInfo[] infos = mm.getDevices();
        for (MidiDeviceInfo info : infos) {
            if (info.getOutputPortCount() > 0) { open(info); return; }
        }
        status("Nenhum dispositivo MIDI");
        mm.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override public void onDeviceAdded(MidiDeviceInfo info) {
                if (device == null && info.getOutputPortCount() > 0) open(info);
            }
        }, ui);
    }

    private void open(MidiDeviceInfo info) {
        mm.openDevice(info, dev -> {
            if (dev == null) { status("Falha ao abrir MIDI"); return; }
            device = dev;
            port = dev.openOutputPort(0);
            if (port == null) { status("Porta MIDI indisponível"); return; }
            port.connect(new MidiReceiver() {
                @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
                    parse(data, offset, count);
                }
            });
            String n = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            status("MIDI: " + (n == null ? "conectado" : n));
        }, ui);
    }

    public void close() {
        try { if (port != null) port.close(); } catch (Exception ignored) { }
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        port = null; device = null;
    }

    private void status(String s) { if (listener != null) listener.onMidiStatus(s); }

    private void parse(byte[] d, int off, int count) {
        int i = off;
        int end = off + count;
        while (i < end) {
            int st = d[i] & 0xFF;
            if (st < 0x80) { i++; continue; }
            int type = st & 0xF0, ch = st & 0x0F;
            if ((type == 0x90 || type == 0x80 || type == 0xB0) && i + 2 < end) {
                int a = d[i + 1] & 0x7F, b = d[i + 2] & 0x7F;
                if (type == 0x90 && b > 0) note(true, a, b);
                else if (type == 0x80 || type == 0x90) note(false, a, 0);
                else cc(ch, a, b);
                i += 3;
            } else if ((type == 0xC0 || type == 0xD0) && i + 1 < end) i += 2;
            else if (type == 0xE0 && i + 2 < end) i += 3;
            else i++;
        }
    }

    private void note(boolean on, int n, int vel) {
        int slot = selectedSlot;
        Slot s = engine.slots[slot];
        float x;
        if (s.mode == Slot.KEYBOARD) x = clamp((n - 48 + 0.5f) / Slot.KEYS);
        else x = ((n - 36) % 24 + 24) % 24 / 24f + 0.5f / 24f;
        float y = 1f - vel / 127f;
        engine.touch(slot, on ? 0 : 2, 100 + n, x, y);
    }

    private void cc(int ch, int cc, int val) {
        int key = ch << 8 | cc;
        int lt = learnTarget;
        if (lt >= 0) {
            bindings.put(key, lt);
            prefs.edit().putInt(String.valueOf(key), lt).apply();
            learnTarget = -1;
            if (listener != null) ui.post(() -> listener.onLearned());
            return;
        }
        Integer t = bindings.get(key);
        if (t == null) return;
        float v = val / 127f;
        if (t == T_MASTER) engine.masterVolume = v;
        else if (t == T_TEMPO) engine.bpm = 60.0 + v * 140.0;
        else if (t >= T_SLOT_VOL && t < T_SLOT_VOL + Engine.SLOTS) engine.slots[t - T_SLOT_VOL].volume = v;
        else if (t == T_FX_X) { Slot s = engine.slots[selectedSlot]; s.fx[selectedFx].x = v; s.fx[selectedFx].active = true; }
        else if (t == T_FX_Y) { Slot s = engine.slots[selectedSlot]; s.fx[selectedFx].y = v; s.fx[selectedFx].active = true; }
        else if (t >= T_PAN && t < T_PAN + Engine.SLOTS) engine.slots[t - T_PAN].pan = v;
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
