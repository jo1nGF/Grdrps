package com.gfdrps.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.gfdrps.core.Engine;
import com.gfdrps.core.Fx;
import com.gfdrps.core.GestureRecorder;
import com.gfdrps.core.Sample;
import com.gfdrps.core.Slot;

public final class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_MIC = 12;
    private static final int[] BARS = {1, 2, 4, 8, 16, 32, 64, 128};

    private Engine engine;
    private AudioOutput audio;
    private MicRecorder mic;
    private MidiController midi;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int sel = 0, fxSel = 0, barsIdx = 2;
    private boolean masterFx = false;

    private WaveView wave;
    private XYPadView pad;
    private final Widgets.Tile[] modeTiles = new Widgets.Tile[8];
    private final Widgets.Tile[] divTiles = new Widgets.Tile[Slot.DIVS.length];
    private final Widgets.Tile[] fxTiles = new Widgets.Tile[Fx.COUNT];
    private final SlotStrip[] strips = new SlotStrip[Engine.SLOTS];
    private Widgets.Tile gateT, diceT, glideT, infT, recGestT, barsT, metroT, playT, micT, mixT, trValT, masterFxT, latchT;
    private Widgets.Slider volS, attS, relS, panS, masterS;
    private BpmView bpmView;
    private int pendingMicMode = Engine.REC_OFF;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        engine = new Engine(AudioOutput.preferredRate(this));
        audio = new AudioOutput(this, engine);
        mic = new MicRecorder(engine);
        midi = new MidiController(this, engine);
        midi.setListener(new MidiController.Listener() {
            @Override public void onMidiStatus(String t) { toast(t); }
            @Override public void onLearned() { toast("MIDI mapeado"); }
        });
        setContentView(buildUi());
        select(0);
        ui.post(tick);
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        audio.start();
        midi.connect();
    }

    @Override protected void onPause() {
        wave.releaseAll();
        if (engine.recordingMode() != Engine.REC_OFF) finishRecording();
        audio.stop();
        midi.close();
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        audio.release();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemUi(); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ UI construction

    private static LinearLayout.LayoutParams lp(float wt) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, wt); }
    private static LinearLayout.LayoutParams lpv(float wt) { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, wt); }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private Widgets.Tile tile() { return new Widgets.Tile(this); }

    private View buildUi() {
        LinearLayout root = col();
        root.setBackgroundColor(Theme.BG);

        // ---- top area
        LinearLayout top = row();
        LinearLayout left = col();
        LinearLayout right = row();
        top.addView(left, lp(6.3f));
        top.addView(right, lp(3.7f));

        // row 1: menu, metronome, bpm, master volume, stop, help
        LinearLayout r1 = row();
        r1.addView(tile().text("Gf").accent(0xFFFFFFFF).onClick(this::mainMenu), lp(1f));
        metroT = tile().icon(Theme.I_METRO).accent(0xFF2FE0C8).onClick(() -> { engine.metronome = !engine.metronome; metroT.setOn(engine.metronome); });
        r1.addView(metroT, lp(0.9f));
        bpmView = new BpmView(this, engine);
        r1.addView(bpmView, lp(2.2f));
        masterS = new Widgets.Slider(this, false).accent(0xFFE9EDF2).onValue(v -> engine.masterVolume = v);
        masterS.set(engine.masterVolume);
        r1.addView(masterS, lp(4f));
        r1.addView(tile().icon(Theme.I_STOP).accent(0xFFFF4D5E).onClick(this::panic), lp(1f));
        r1.addView(tile().icon(Theme.I_HELP).onClick(this::help), lp(1f));
        left.addView(r1, lpv(1f));

        // row 2: file, gate, 8 modes
        LinearLayout r2 = row();
        r2.addView(tile().icon(Theme.I_FILE).accent(0xFFFFFFFF).onClick(this::pickFile), lp(1f));
        gateT = tile().icon(Theme.I_GATE).accent(0xFFFFD23F).onClick(() -> { Slot s = engine.slots[sel]; s.gate = !s.gate; refresh(); });
        r2.addView(gateT, lp(1f));
        int[] modeIcons = {Theme.I_SLICER, Theme.I_LOOPER, Theme.I_BOW, Theme.I_TAPE, Theme.I_SCRATCH, Theme.I_ARP, Theme.I_KEYS, Theme.I_LOOPPLAY};
        for (int m = 0; m < 8; m++) {
            final int mm = m;
            modeTiles[m] = tile().icon(modeIcons[m]).accent(Theme.MODE[m]).onClick(() -> { engine.setMode(sel, mm); refresh(); });
            r2.addView(modeTiles[m], lp(1f));
        }
        left.addView(r2, lpv(1f));

        // row 3: sliders | controls
        LinearLayout r3 = row();
        volS = new Widgets.Slider(this, true).accent(0xFFE9EDF2).onValue(v -> engine.slots[sel].volume = v);
        attS = new Widgets.Slider(this, true).accent(0xFFFF9A2E).onValue(v -> engine.slots[sel].attack = v * v * 2f);
        relS = new Widgets.Slider(this, true).accent(0xFF3DD6FF).onValue(v -> engine.slots[sel].release = 0.01f + v * v * 3f);
        panS = new Widgets.Slider(this, true).accent(0xFFB46CFF).onValue(v -> engine.slots[sel].pan = v);
        r3.addView(volS, lp(0.7f)); r3.addView(attS, lp(0.7f)); r3.addView(relS, lp(0.7f)); r3.addView(panS, lp(0.7f));

        LinearLayout mid = col();
        // div row
        LinearLayout dr = row();
        for (int k = 0; k < Slot.DIVS.length; k++) {
            final int kk = k;
            divTiles[k] = tile().text(String.valueOf(Slot.DIVS[k])).accent(0xFFFFFFFF).onClick(() -> { engine.slots[sel].div = Slot.DIVS[kk]; refresh(); });
            dr.addView(divTiles[k], lp(1f));
        }
        diceT = tile().icon(Theme.I_DICE).accent(0xFFFF4FA3).onClick(() -> { Slot s = engine.slots[sel]; s.arpRandom = !s.arpRandom; refresh(); });
        dr.addView(diceT, lp(1f));
        mid.addView(dr, lpv(1f));
        // transpose row
        LinearLayout tr = row();
        tr.addView(tile().text("-12").onClick(() -> transpose(-12)), lp(1f));
        tr.addView(tile().text("-1").onClick(() -> transpose(-1)), lp(1f));
        trValT = tile().text("0").accent(0xFFFFFFFF).textScale(0.6f).onClick(() -> { engine.slots[sel].transpose = 0; refresh(); });
        tr.addView(trValT, lp(1.4f));
        tr.addView(tile().text("+1").onClick(() -> transpose(1)), lp(1f));
        tr.addView(tile().text("+12").onClick(() -> transpose(12)), lp(1f));
        glideT = tile().icon(Theme.I_GLIDE).accent(0xFF7C6CFF).onClick(() -> { Slot s = engine.slots[sel]; s.legato = !s.legato; refresh(); });
        tr.addView(glideT, lp(1f));
        mid.addView(tr, lpv(1f));
        // gesture row
        LinearLayout gr = row();
        recGestT = tile().icon(Theme.I_REC).accent(0xFFFF3B4E).onClick(this::toggleGestures);
        gr.addView(recGestT, lp(1.2f));
        barsT = tile().text("4").accent(0xFFFFFFFF).textScale(0.5f).onClick(() -> { barsIdx = (barsIdx + 1) % BARS.length; refresh(); });
        gr.addView(barsT, lp(1.6f));
        gr.addView(tile().icon(Theme.I_CLEAR).onClick(() -> engine.clearGestures(sel)), lp(1f));
        infT = tile().icon(Theme.I_LOOPINF).accent(0xFF2FE0C8).onClick(() -> { Slot s = engine.slots[sel]; s.infinite = !s.infinite; refresh(); });
        gr.addView(infT, lp(1.2f));
        mid.addView(gr, lpv(1f));
        r3.addView(mid, lp(5.6f));
        left.addView(r3, lpv(2f));

        // right: XY pad + fx column
        pad = new XYPadView(this, engine);
        right.addView(pad, lp(5.6f));
        LinearLayout fxCol = col();
        int[] fxIcons = {Theme.I_DIST, Theme.I_FILTER, Theme.I_AMP, Theme.I_DELAY, Theme.I_REVERB};
        for (int f = 0; f < Fx.COUNT; f++) {
            final int ff = f;
            fxTiles[f] = tile().icon(fxIcons[f]).accent(Theme.FX[f]).onClick(() -> { fxSel = ff; pad.select(sel, fxSel, masterFx); midi.selectedFx = ff; refresh(); });
            fxCol.addView(fxTiles[f], lpv(1f));
        }
        latchT = tile().text("hold").textScale(0.3f).accent(0xFFFFFFFF).onClick(() -> { pad.setLatch(!pad.isLatch()); latchT.setOn(pad.isLatch()); });
        fxCol.addView(latchT, lpv(0.8f));
        masterFxT = tile().text("M").accent(0xFFFFD23F).onClick(() -> { masterFx = !masterFx; pad.select(sel, fxSel, masterFx); refresh(); });
        fxCol.addView(masterFxT, lpv(0.8f));
        right.addView(fxCol, lp(1f));

        root.addView(top, lpv(5.2f));

        // ---- waveform
        wave = new WaveView(this, engine);
        root.addView(wave, lpv(5.3f));

        // ---- bottom strip
        LinearLayout strip = row();
        playT = tile().icon(Theme.I_PLAY).accent(0xFFE9EDF2).onClick(() -> { engine.playing = !engine.playing; refresh(); });
        strip.addView(playT, lp(1.2f));
        for (int i = 0; i < Engine.SLOTS; i++) {
            strips[i] = new SlotStrip(this, engine, i, new SlotStrip.Callbacks() {
                @Override public void onSelect(int s) { select(s); }
                @Override public void onMenu(int s) { select(s); slotMenu(s); }
            });
            strip.addView(strips[i], lp(2f));
        }
        micT = tile().icon(Theme.I_MIC).accent(0xFFFF3B4E).onClick(() -> toggleRecord(Engine.REC_INPUT));
        mixT = tile().icon(Theme.I_MIX).accent(0xFFFFFFFF).onClick(() -> toggleRecord(Engine.REC_MIX));
        strip.addView(micT, lp(1f));
        strip.addView(mixT, lp(1f));
        root.addView(strip, lpv(1.5f));
        return root;
    }

    // ------------------------------------------------------------------ state sync

    private void select(int i) {
        sel = i;
        wave.setSlot(i);
        midi.selectedSlot = i;
        pad.select(i, fxSel, masterFx);
        refresh();
    }

    private void transpose(int d) {
        Slot s = engine.slots[sel];
        s.transpose = Math.max(-24, Math.min(24, s.transpose + d));
        refresh();
    }

    private void refresh() {
        Slot s = engine.slots[sel];
        for (int m = 0; m < 8; m++) modeTiles[m].setOn(s.mode == m);
        for (int k = 0; k < Slot.DIVS.length; k++) divTiles[k].setOn(s.div == Slot.DIVS[k]);
        for (int f = 0; f < Fx.COUNT; f++) fxTiles[f].setOn(fxSel == f);
        gateT.setOn(s.gate); diceT.setOn(s.arpRandom); glideT.setOn(s.legato); infT.setOn(s.infinite);
        masterFxT.setOn(masterFx);
        trValT.text((s.transpose > 0 ? "+" : "") + s.transpose);
        volS.set(s.volume);
        attS.set((float) Math.sqrt(s.attack / 2f));
        relS.set((float) Math.sqrt(Math.max(0f, (s.release - 0.01f) / 3f)));
        panS.set(s.pan);
        metroT.setOn(engine.metronome);
        playT.icon(engine.playing ? Theme.I_STOP : Theme.I_PLAY);
        playT.setOn(engine.playing);
        for (int i = 0; i < Engine.SLOTS; i++) strips[i].setSelected(i == sel);
        updateGestureTiles();
    }

    private void updateGestureTiles() {
        GestureRecorder g = engine.slots[sel].gestures;
        int st = g.state;
        recGestT.setOn(st != GestureRecorder.IDLE);
        if (st == GestureRecorder.IDLE) barsT.text(String.valueOf(BARS[barsIdx]));
        else if (st == GestureRecorder.ARMED) barsT.text("…");
        else barsT.text(((int) (g.progress * g.bars) + 1) + "/" + g.bars);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateGestureTiles();
            bpmView.invalidate();
            int rm = engine.recordingMode();
            micT.setOn(rm == Engine.REC_INPUT);
            mixT.setOn(rm == Engine.REC_MIX);
            ui.postDelayed(this, 150);
        }
    };

    // ------------------------------------------------------------------ actions

    private void panic() {
        wave.releaseAll();
        engine.post(() -> { for (Slot s : engine.slots) s.stopAll(); });
    }

    private void toggleGestures() {
        Slot s = engine.slots[sel];
        if (s.gestures.state == GestureRecorder.IDLE) {
            engine.armGestures(sel, BARS[barsIdx]);
            toast("Gravando gestos no próximo compasso (" + BARS[barsIdx] + ")");
        } else engine.clearGestures(sel);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/octet-stream"});
        startActivityForResult(i, REQ_FILE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final int target = sel;
        toast("Carregando…");
        new Thread(() -> {
            try {
                Sample s = SampleLoader.load(this, uri);
                engine.loadSample(target, s);
                ui.post(() -> { toast("Slot " + (target + 1) + ": " + s.name); refresh(); });
            } catch (Exception e) {
                ui.post(() -> toast("Erro: " + e.getMessage()));
            }
        }, "gfdrps-load").start();
    }

    private void toggleRecord(int mode) {
        if (engine.recordingMode() != Engine.REC_OFF) { finishRecording(); return; }
        if (mode == Engine.REC_INPUT && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicMode = mode;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        beginRecording(mode);
    }

    private void beginRecording(int mode) {
        engine.startRecording(sel, mode);
        if (mode == Engine.REC_INPUT && !mic.start()) {
            engine.stopRecording("", engine.rate);
            toast("Não foi possível abrir o microfone");
            return;
        }
        toast(mode == Engine.REC_INPUT ? "Gravando microfone no slot " + (sel + 1) : "Gravando o mix no slot " + (sel + 1));
    }

    private void finishRecording() {
        int mode = engine.recordingMode();
        mic.stop();
        boolean ok = engine.stopRecording(mode == Engine.REC_MIX ? "mix" : "rec", engine.rate);
        toast(ok ? "Gravação salva no slot " + (sel + 1) : "Nada gravado");
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) beginRecording(pendingMicMode);
            else toast("Permissão do microfone negada");
        }
    }

    // ------------------------------------------------------------------ dialogs

    private void mainMenu() {
        String[] items = {"Salvar projeto", "Abrir projeto", "MIDI Learn…", "Limpar mapeamentos MIDI", "Reconectar MIDI", "Sobre"};
        new AlertDialog.Builder(this).setTitle("Gf Drps").setItems(items, (d, w) -> {
            switch (w) {
                case 0: saveProject(); break;
                case 1: openProject(); break;
                case 2: learnMenu(); break;
                case 3: midi.clearBindings(); toast("Mapeamentos apagados"); break;
                case 4: midi.connect(); break;
                default: about(); break;
            }
        }).show();
    }

    private void saveProject() {
        final EditText et = new EditText(this);
        et.setText("meu projeto");
        new AlertDialog.Builder(this).setTitle("Nome do projeto").setView(et).setPositiveButton("Salvar", (d, w) -> {
            try { ProjectStore.save(this, engine, et.getText().toString()); toast("Projeto salvo"); }
            catch (Exception e) { toast("Erro ao salvar: " + e.getMessage()); }
        }).setNegativeButton("Cancelar", null).show();
    }

    private void openProject() {
        final String[] names = ProjectStore.list(this);
        if (names.length == 0) { toast("Nenhum projeto salvo"); return; }
        new AlertDialog.Builder(this).setTitle("Abrir projeto").setItems(names, (d, w) -> {
            try { ProjectStore.load(this, engine, names[w]); refresh(); toast("Projeto carregado"); }
            catch (Exception e) { toast("Erro ao abrir: " + e.getMessage()); }
        }).show();
    }

    private void learnMenu() {
        final String[] names = {"Volume master", "Tempo", "Volume slot 1", "Volume slot 2", "Volume slot 3", "Volume slot 4", "Volume slot 5", "Volume slot 6",
                "Efeito X (selecionado)", "Efeito Y (selecionado)"};
        new AlertDialog.Builder(this).setTitle("Mover um controle MIDI para mapear").setItems(names, (d, w) -> {
            midi.startLearn(w);
            toast("Gire um knob/fader do controlador…");
        }).show();
    }

    private void slotMenu(final int s) {
        final Slot sl = engine.slots[s];
        String[] items = {"Carregar arquivo…", sl.muted ? "Reativar slot" : "Silenciar slot", "Duração do loop: dobrar (" + (int) sl.sampleBeats + " batidas)",
                "Duração do loop: metade", "Recarregar som de demonstração"};
        new AlertDialog.Builder(this).setTitle("Slot " + (s + 1) + (sl.sample != null ? " – " + sl.sample.name : "")).setItems(items, (d, w) -> {
            switch (w) {
                case 0: pickFile(); break;
                case 1: sl.muted = !sl.muted; break;
                case 2: sl.sampleBeats = Math.min(256, sl.sampleBeats * 2); break;
                case 3: sl.sampleBeats = Math.max(1, sl.sampleBeats / 2); break;
                default: engine.loadSample(s, com.gfdrps.core.DemoSynth.make(s, engine.rate, engine.bpm)); break;
            }
            refresh();
        }).show();
    }

    private void help() {
        String t = "Slicer – toque numa fatia; altura = volume.\n"
                + "Looper – dois dedos definem o loop.\n"
                + "Bow – segure: um grão do som vira textura contínua.\n"
                + "Tape – arraste p/ direita acelera, p/ esquerda inverte; soltar = tape stop.\n"
                + "Scratch – arraste o dedo como um vinil.\n"
                + "Arpeggiator – segure dedos: dispara trechos no ritmo do BPM (dado = aleatório).\n"
                + "Keyboard – teclas cromáticas (∞ sustenta, curva = legato).\n"
                + "Loop Player – toque liga/desliga o loop sincronizado.\n\n"
                + "Sliders: volume · attack · release · pan.\n"
                + "● grava seus gestos por N compassos e repete em loop.\n"
                + "Rodapé: toque num slot para escolher, segure para o menu; mic grava no slot; círculos duplos gravam o mix (resample).";
        new AlertDialog.Builder(this).setTitle("Como tocar").setMessage(t).setPositiveButton("OK", null).show();
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Gf Drps").setMessage("Instrumento multitoque de samples para Android.\nTaxa de áudio: " + engine.rate + " Hz\nMIDI mapeado: " + midi.bindingCount())
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ BPM control

    /** Drag up/down to change tempo; quick taps set tempo by tapping. */
    private static final class BpmView extends View {
        private final Engine engine;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastY, startY;
        private long lastTap;
        private double tapAvg;

        BpmView(android.content.Context c, Engine e) { super(c); engine = e; setClickable(true); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lastY = startY = e.getY(); return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = lastY - e.getY();
                    lastY = e.getY();
                    engine.bpm = Math.max(40.0, Math.min(240.0, engine.bpm + dy * 0.1));
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (Math.abs(e.getY() - startY) < 12) {
                        long now = e.getEventTime();
                        long dt = now - lastTap;
                        if (dt > 250 && dt < 2000) {
                            double b = 60000.0 / dt;
                            tapAvg = tapAvg == 0 || dt > 1500 ? b : tapAvg * 0.5 + b * 0.5;
                            engine.bpm = Math.max(40.0, Math.min(240.0, tapAvg));
                        } else tapAvg = 0;
                        lastTap = now;
                        invalidate();
                    }
                    return true;
                default: return true;
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.TILE);
            c.drawRect(1, 1, w - 1, h - 1, p);
            p.setColor(Theme.TEXT);
            p.setTextSize(h * 0.55f);
            c.drawText(String.format(java.util.Locale.US, "%.1f", engine.bpm), w / 2, h / 2 + h * 0.2f, p);
        }
    }
}
