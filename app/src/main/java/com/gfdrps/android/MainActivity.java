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
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
    private Widgets.Tile revT, ctxT, edT, glideT, infT, recGestT, barsT, metroT, playT, micT, mixT, trValT, masterFxT, latchT, lkT, qT, autoT, shufT, syncT, zoomT, undoT, redoT, toneT, addT, gfT, fileT, stopT, helpT;
    private Widgets.Slider volS, attS, decS, susS, relS, panS, masterS, sizeS;
    private HelpOverlay helpOverlay;
    private BpmView bpmView;
    private int pendingMicMode = Engine.REC_OFF;
    private FrameLayout frame;
    private HorizontalScrollView stripScroll;
    private RecordView recView;
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

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
        gfT = tile().text("Gf v" + versionName()).textScale(0.6f).accent(0xFFFFFFFF).onClick(this::mainMenu);
        r1.addView(gfT, lp(1.3f));
        metroT = tile().icon(Theme.I_METRO).accent(0xFF2FE0C8).onClick(() -> { engine.metronome = !engine.metronome; metroT.setOn(engine.metronome); });
        r1.addView(metroT, lp(0.9f));
        bpmView = new BpmView(this, engine);
        r1.addView(bpmView, lp(2.2f));
        masterS = new Widgets.Slider(this, false).accent(0xFFE9EDF2).onValue(v -> engine.masterVolume = v);
        masterS.set(engine.masterVolume);
        r1.addView(masterS, lp(4f));
        stopT = tile().icon(Theme.I_STOP).accent(0xFFFF4D5E).onClick(this::panic);
        r1.addView(stopT, lp(1f));
        helpT = tile().icon(Theme.I_HELP).onClick(this::help).onLong(this::helpText);
        r1.addView(helpT, lp(1f));
        left.addView(r1, lpv(1f));

        // row 2: file, gate, 8 modes
        LinearLayout r2 = row();
        fileT = tile().icon(Theme.I_FILE).accent(0xFFFFFFFF).onClick(this::pickFile);
        r2.addView(fileT, lp(1f));
        revT = tile().icon(Theme.I_REVERSE).accent(0xFF3DD6FF).onClick(() -> { engine.reverseSample(sel); ui.postDelayed(this::refresh, 120); });
        r2.addView(revT, lp(1f));
        undoT = tile().text("undo").textScale(0.32f).accent(0xFFFF9A2E).onClick(() -> {
            if (engine.undo(sel)) { toast("Desfeito"); ui.postDelayed(this::refresh, 120); } else toast("Nada para desfazer"); });
        r2.addView(undoT, lp(1f));
        redoT = tile().text("redo").textScale(0.32f).accent(0xFF4FD8A0).onClick(() -> {
            if (engine.redo(sel)) { toast("Refeito"); ui.postDelayed(this::refresh, 120); } else toast("Nada para refazer"); });
        r2.addView(redoT, lp(1f));
        int[] modeIcons = {Theme.I_SLICER, Theme.I_LOOPER, Theme.I_BOW, Theme.I_TAPE, Theme.I_SCRATCH, Theme.I_ARP, Theme.I_KEYS, Theme.I_LOOPPLAY};
        for (int m = 0; m < 8; m++) {
            final int mm = m;
            modeTiles[m] = tile().icon(modeIcons[m]).accent(Theme.MODE[m]).onClick(() -> { engine.setMode(sel, mm); refresh(); });
            r2.addView(modeTiles[m], lp(1f));
        }
        left.addView(r2, lpv(1f));

        // row 3: sliders | controls
        LinearLayout r3 = row();
        volS = new Widgets.Slider(this, true).icon(Theme.I_VOL).accent(0xFFE9EDF2).onValue(v -> engine.slots[sel].volume = v);
        attS = new Widgets.Slider(this, true).icon(Theme.I_ATT).accent(0xFFFF9A2E).onValue(v -> engine.slots[sel].attack = v * v * 2f);
        decS = new Widgets.Slider(this, true).icon(Theme.I_DEC).accent(0xFFFFD23F).onValue(v -> engine.slots[sel].decay = 0.02f + v * v * 3f);
        susS = new Widgets.Slider(this, true).icon(Theme.I_SUS).accent(0xFF7CFF6B).onValue(v -> engine.slots[sel].sustain = v);
        relS = new Widgets.Slider(this, true).icon(Theme.I_REL).accent(0xFF3DD6FF).onValue(v -> engine.slots[sel].release = 0.01f + v * v * 3f);
        panS = new Widgets.Slider(this, true).icon(Theme.I_PAN).accent(0xFFB46CFF).onValue(v -> engine.slots[sel].pan = v);
        sizeS = new Widgets.Slider(this, true).icon(Theme.I_GRAIN).accent(0xFF7CC4FF).onValue(v -> engine.slots[sel].bowSize = v);
        r3.addView(volS, lp(0.7f)); r3.addView(attS, lp(0.7f)); r3.addView(decS, lp(0.7f)); r3.addView(susS, lp(0.7f)); r3.addView(relS, lp(0.7f)); r3.addView(panS, lp(0.7f)); r3.addView(sizeS, lp(0.7f));

        LinearLayout mid = col();
        // div row
        LinearLayout dr = row();
        for (int k = 0; k < Slot.DIVS.length; k++) {
            final int kk = k;
            divTiles[k] = tile().text(String.valueOf(Slot.DIVS[k])).accent(0xFFFFFFFF).onClick(() -> { engine.slots[sel].div = Slot.DIVS[kk]; refresh(); })
                    .onDrag(slideSteps(DIV_LIST, () -> engine.slots[sel].div, v -> { engine.slots[sel].div = v; refresh(); }, 22f));
            dr.addView(divTiles[k], lp(1f));
        }
        ctxT = tile().icon(Theme.I_DICE).accent(0xFFFF4FA3).onClick(this::contextAction);   // Slicer: auto-slice · Arp: random
        dr.addView(ctxT, lp(1f));
        edT = tile().icon(Theme.I_MARK).accent(0xFFFFD23F).onClick(() -> {
                    Slot sl = engine.slots[sel];
                    if (sl.mode == Slot.SLICER || sl.mode == Slot.ARP || sl.mode == Slot.KEYBOARD) wave.setEditSlices(!wave.isEditSlices());
                    refresh(); })
                .onLong(() -> {
                    Slot sl = engine.slots[sel];
                    if (sl.mode == Slot.KEYBOARD) { engine.pushUndo(sel); engine.setRegion(sel, 0f, 1f); toast("Região: sample inteiro"); }
                    else { engine.pushUndo(sel); engine.setMarkers(sel, null); toast("Fatias voltaram para a grade"); }
                });
        dr.addView(edT, lp(1f));
        mid.addView(dr, lpv(1f));
        // transpose row
        LinearLayout tr = row();
        tr.addView(tile().text("-12").onClick(() -> transpose(-12)), lp(1f));
        tr.addView(tile().text("-1").onClick(() -> transpose(-1)), lp(1f));
        trValT = tile().text("0").accent(0xFFFFFFFF).textScale(0.6f).longPressMs(2000)
                .onClick(() -> { engine.slots[sel].transpose = 0; engine.slots[sel].fine = 0f; refresh(); })
                .onLong(this::pitchDialog)
                .onDrag(new Widgets.Tile.Drag() {          // slide right = bend up, left = bend down; lets go = springs back
                    @Override public void drag(float total, float delta) {
                        bendHeld = true;
                        float semis = Math.max(-12f, Math.min(12f, total / dp(14)));
                        engine.slots[sel].bend = semis;
                        trValT.text((semis >= 0 ? "▲" : "▼") + String.format(java.util.Locale.US, "%.1f", Math.abs(semis)));
                    }
                    @Override public void end() { bendHeld = false; ui.post(bendBack); }
                });
        tr.addView(trValT, lp(1.4f));
        tr.addView(tile().text("+1").onClick(() -> transpose(1)), lp(1f));
        tr.addView(tile().text("+12").onClick(() -> transpose(12)), lp(1f));
        glideT = tile().icon(Theme.I_GLIDE).accent(0xFF7C6CFF).onClick(() -> { Slot s = engine.slots[sel]; s.legato = !s.legato; refresh(); });
        tr.addView(glideT, lp(1f));
        mid.addView(tr, lpv(1f));
        // gesture row
        LinearLayout gr = row();
        recGestT = tile().icon(Theme.I_REC).accent(0xFFFF3B4E).onClick(this::toggleGestures).onLong(this::gestureMenu);
        gr.addView(recGestT, lp(1.2f));
        barsT = tile().text("4").accent(0xFFFFFFFF).textScale(0.5f).onClick(() -> { barsIdx = (barsIdx + 1) % BARS.length; refresh(); });
        gr.addView(barsT, lp(1.6f));
        gr.addView(tile().icon(Theme.I_CLEAR).onClick(() -> engine.clearGestures(sel)), lp(1f));
        infT = tile().icon(Theme.I_LOOPINF).accent(0xFF2FE0C8).onClick(() -> { Slot s = engine.slots[sel]; s.infinite = !s.infinite; refresh(); });
        gr.addView(infT, lp(1.2f));
        mid.addView(gr, lpv(1f));
        // play-mode row: latch · quantize · slicer auto-play · shuffle
        LinearLayout xr = row();
        lkT = tile().text("latch").textScale(0.34f).accent(0xFF52D273).onClick(() -> {
            Slot s = engine.slots[sel]; boolean on = !s.latch; engine.setLatch(sel, on); s.latch = on;
            toast(on ? "Latch: o som continua ao soltar ou trocar de modo" : "Latch desligado");
            refresh(); });
        xr.addView(lkT, lp(1.3f));
        qT = tile().text("Q").textScale(0.34f).accent(0xFFFFD23F)
                .onClick(() -> { Slot s = engine.slots[sel];
                    if (s.mode == Slot.ARP) { s.arpRate = ARP_RATES[(rateIndex(s.arpRate) + 1) % ARP_RATES.length]; toast("Taxa " + rateName(s.arpRate)); refresh(); return; }
                    s.quantize = !s.quantize; toast(s.quantize ? "Quantizar: toques esperam a grade (" + rateName(s.arpRate) + ") — deslize para mudar" : "Quantizar desligado"); refresh(); })
                .onDrag(slideSteps(ARP_RATES, () -> engine.slots[sel].arpRate, v -> { engine.slots[sel].arpRate = v; refresh(); }, 22f))
                .onLong(() -> { Slot s = engine.slots[sel]; s.arpRate = ARP_RATES[(rateIndex(s.arpRate) + 1) % ARP_RATES.length]; toast("Grade " + rateName(s.arpRate)); refresh(); });
        xr.addView(qT, lp(1.3f));
        autoT = tile().icon(Theme.I_PLAY).accent(0xFF3DD6FF).onClick(() -> {
            Slot s = engine.slots[sel]; if (s.mode != Slot.SLICER) { toast("Auto-play é do modo Slicer"); return; }
            s.autoPlay = !s.autoPlay; toast(s.autoPlay ? "Slicer auto-play: fatias da esquerda para a direita" : "Auto-play parado"); refresh(); });
        xr.addView(autoT, lp(1f));
        shufT = tile().icon(Theme.I_DICE).accent(0xFFFF4FA3).onClick(() -> {
            Slot s = engine.slots[sel]; s.shuffle = !s.shuffle; toast(s.shuffle ? "Shuffle ligado" : "Shuffle desligado"); refresh(); });
        xr.addView(shufT, lp(1f));
        zoomT = tile().text("1x").textScale(0.4f).accent(0xFF7CC4FF).onClick(() -> { wave.cycleZoom(); refresh(); }).onLong(() -> { wave.resetZoom(); refresh(); })
                .onDrag(new Widgets.Tile.Drag() {          // slide right = zoom in, left = zoom out (smooth, doubles every 60 dp)
                    float base = -1f;
                    @Override public void drag(float total, float delta) {
                        if (base < 0f) base = wave.zoom();
                        wave.setZoom((float) (base * Math.pow(2.0, total / dp(60))));
                        zoomT.text(zoomLabel(wave.zoom()));
                    }
                    @Override public void end() { base = -1f; refresh(); }
                    @Override public void vertical(float total, float delta) { wave.panBy(-delta / dp(90)); }     // slide up = the view moves right along the track
                });
        xr.addView(zoomT, lp(1f));
        mid.addView(xr, lpv(1f));
        r3.addView(mid, lp(5.6f));
        left.addView(r3, lpv(2.7f));

        // right: XY pad + fx column
        pad = new XYPadView(this, engine);
        right.addView(pad, lp(5.6f));
        LinearLayout fxCol = col();
        int[] fxIcons = {Theme.I_DIST, Theme.I_FILTER, Theme.I_AMP, Theme.I_DELAY, Theme.I_REVERB};
        for (int f = 0; f < Fx.COUNT; f++) {
            final int ff = f;
            fxTiles[f] = tile().icon(fxIcons[f]).accent(Theme.FX[f]).onClick(() -> {
                long now = android.os.SystemClock.uptimeMillis();
                boolean dbl = lastFxTap == ff && now - lastFxTapAt < 400;          // two taps on the same effect open its options
                lastFxTap = ff; lastFxTapAt = now;
                fxSel = ff; pad.select(sel, fxSel, masterFx); midi.selectedFx = ff; refresh();
                if (dbl) { lastFxTap = -1; fxMenu(ff); }
            });
            fxCol.addView(fxTiles[f], lpv(1f));
        }
        latchT = tile().text("hold").textScale(0.3f).accent(0xFFFFFFFF).onClick(() -> { pad.setLatch(!pad.isLatch()); latchT.setOn(pad.isLatch()); });
        fxCol.addView(latchT, lpv(0.7f));
        syncT = tile().text("sync").textScale(0.3f).accent(0xFF3DD6FF).onClick(() -> {
            Fx.Effect ef = masterFx ? engine.master[Fx.DELAY] : engine.slots[sel].fx[Fx.DELAY];
            Fx.Delay d = (Fx.Delay) ef; d.sync = !d.sync;
            if (fxSel != Fx.DELAY) { fxSel = Fx.DELAY; midi.selectedFx = fxSel; pad.select(sel, fxSel, masterFx); }
            toast(d.sync ? "Delay sincronizado com o BPM (notas 1/32 a 1 compasso)" : "Delay livre: 10 ms a 4 s");
            refresh(); });
        fxCol.addView(syncT, lpv(0.7f));
        toneT = tile().text("LP/HP").textScale(0.28f).accent(0xFF7CC4FF)
                .onClick(() -> {
                    Fx.Tone tn = toneOf();
                    if (tn == null) {                                     // pick the delay first (LP/HP lives on delay and reverb)
                        fxSel = Fx.DELAY; midi.selectedFx = fxSel; pad.select(sel, fxSel, masterFx);
                        tn = toneOf();
                    }
                    tn.mode = (tn.mode + 1) % 3;
                    toast(tn.mode == 0 ? "Filtro do efeito desligado" : (tn.mode == 1 ? "Passa-baixas no efeito — deslize o botão para o corte" : "Passa-altas no efeito — deslize o botão para o corte"));
                    refresh(); })
                .onDrag(new Widgets.Tile.Drag() {
                    float base = -1f;
                    @Override public void drag(float total, float delta) {
                        Fx.Tone tn = toneOf(); if (tn == null) return;
                        if (base < 0f) base = tn.cut;
                        if (tn.mode == 0) tn.mode = 1;
                        tn.cut = Math.max(0f, Math.min(1f, base + total / dp(220)));
                        refreshTone(); pad.invalidate();
                    }
                    @Override public void end() { base = -1f; refresh(); }
                });
        fxCol.addView(toneT, lpv(0.8f));
        masterFxT = tile().text("M").accent(0xFFFFD23F).onClick(() -> { masterFx = !masterFx; pad.select(sel, fxSel, masterFx); refresh(); });
        fxCol.addView(masterFxT, lpv(0.8f));
        right.addView(fxCol, lp(1f));

        root.addView(top, lpv(5.9f));

        // ---- waveform
        wave = new WaveView(this, engine);
        root.addView(wave, lpv(4.6f));

        // ---- bottom strip
        LinearLayout strip = row();
        playT = tile().icon(Theme.I_PLAY).accent(0xFFE9EDF2).onClick(() -> {
            if (engine.playing) { wave.releaseAll(); engine.stopAll(); } else engine.playing = true;
            ui.postDelayed(this::refresh, 100);
        });
        strip.addView(playT, lp(1.2f));
        LinearLayout stripRow = row();
        stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        stripScroll.setFillViewport(true);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int stripW = Math.round(screenW * 2f / 15.2f);          // six tracks fill the bar; more tracks scroll
        for (int i = 0; i < Engine.SLOTS; i++) {
            strips[i] = new SlotStrip(this, engine, i, new SlotStrip.Callbacks() {
                @Override public void onSelect(int s) { select(s); }
                @Override public void onMenu(int s) { select(s); slotMenu(s); }
            });
            stripRow.addView(strips[i], new LinearLayout.LayoutParams(stripW, ViewGroup.LayoutParams.MATCH_PARENT));
            strips[i].setVisibility(i < engine.slotCount ? View.VISIBLE : View.GONE);
        }
        addT = tile().text("+").textScale(0.7f).accent(0xFF52D273).onClick(this::addTrack);
        stripRow.addView(addT, new LinearLayout.LayoutParams(Math.round(stripW * 0.5f), ViewGroup.LayoutParams.MATCH_PARENT));
        stripScroll.addView(stripRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        strip.addView(stripScroll, lp(12f));
        micT = tile().icon(Theme.I_MIC).accent(0xFFFF3B4E).onClick(() -> toggleRecord(Engine.REC_INPUT));
        mixT = tile().icon(Theme.I_MIX).accent(0xFFFFFFFF).onClick(() -> toggleRecord(Engine.REC_MIX));
        strip.addView(micT, lp(1f));
        strip.addView(mixT, lp(1f));
        root.addView(strip, lpv(1.5f));
        frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
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
        revT.setOn(s.reversed);
        if (s.mode == Slot.SLICER) { ctxT.icon(Theme.I_AUTO).accent(Theme.MODE[0]); ctxT.setOn(false); ctxT.setDim(false); }
        else if (s.mode == Slot.ARP) { ctxT.text(ARP_DIR[s.arpDir]).accent(0xFFFF4FA3); ctxT.setOn(true); ctxT.setDim(false); }
        else if (s.mode == Slot.LOOPER) { ctxT.text("P-P").accent(Theme.MODE[1]); ctxT.setOn(s.pingPong); ctxT.setDim(false); }
        else if (s.mode == Slot.LOOPPLAYER) { ctxT.text("STR").accent(Theme.MODE[7]); ctxT.setOn(s.stretch); ctxT.setDim(false); }
        else { ctxT.icon(Theme.I_DICE); ctxT.setOn(false); ctxT.setDim(true); }
        if (s.mode != Slot.SLICER && s.mode != Slot.ARP && s.mode != Slot.KEYBOARD && wave.isEditSlices()) wave.setEditSlices(false);
        boolean divOk = s.mode == Slot.SLICER || s.mode == Slot.LOOPER || s.mode == Slot.ARP || s.mode == Slot.TAPE || s.mode == Slot.SCRATCH;
        zoomT.text(zoomLabel(wave.zoom()));
        undoT.setDim(!engine.canUndo(sel));
        redoT.setDim(!engine.canRedo(sel));
        refreshTone();
        for (int i = 0; i < Engine.SLOTS; i++) strips[i].setVisibility(i < engine.slotCount ? View.VISIBLE : View.GONE);
        addT.setVisibility(engine.slotCount < Engine.SLOTS ? View.VISIBLE : View.GONE);
        edT.icon(Theme.I_MARK).accent(0xFFFFD23F); edT.setOn(wave.isEditSlices());
        show(edT, s.mode == Slot.SLICER || s.mode == Slot.ARP || s.mode == Slot.KEYBOARD);     // controls that do nothing in this mode disappear
        show(glideT, s.mode == Slot.KEYBOARD || s.mode == Slot.SLICER);
        show(ctxT, s.mode == Slot.SLICER || s.mode == Slot.ARP || s.mode == Slot.LOOPER || s.mode == Slot.LOOPPLAYER);
        for (int k = 0; k < Slot.DIVS.length; k++) show(divTiles[k], divOk);
        show(autoT, s.mode == Slot.SLICER); show(shufT, s.mode == Slot.SLICER);
        sizeS.set(s.bowSize);
        // one single Filter module: its icon shows whether it is low-pass or high-pass
        Fx.Filter flt = (Fx.Filter) (masterFx ? engine.master[Fx.FILTER] : s.fx[Fx.FILTER]);
        fxTiles[Fx.FILTER].icon(flt.highPass ? Theme.I_FILTER_HP : Theme.I_FILTER);
        pad.invalidate();
        glideT.setOn(s.legato); infT.setOn(s.infinite);
        masterFxT.setOn(masterFx);
        if (!bendHeld) trValT.text(s.fine != 0f ? String.format(java.util.Locale.US, "%+.1f", s.transpose + s.fine) : (s.transpose > 0 ? "+" : "") + s.transpose);
        lkT.setOn(s.latch);
        boolean qOk = s.mode == Slot.SLICER || s.mode == Slot.KEYBOARD;
        show(qT, qOk || s.mode == Slot.ARP);
        if (s.mode == Slot.ARP) { qT.text(rateName(s.arpRate)); qT.setOn(true); }       // in the arpeggiator this button is the note rate
        else { qT.text("Q " + rateName(s.arpRate)); qT.setOn(s.quantize && qOk); }
        autoT.setOn(s.autoPlay);
        shufT.setOn(s.shuffle);
        Fx.Delay dly = (Fx.Delay) (masterFx ? engine.master[Fx.DELAY] : s.fx[Fx.DELAY]);
        syncT.setOn(dly.sync);
        volS.set(s.volume);
        attS.set((float) Math.sqrt(s.attack / 2f));
        decS.set((float) Math.sqrt(Math.max(0f, (s.decay - 0.02f) / 3f)));
        susS.set(s.sustain);
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
        else if (st == GestureRecorder.OVERDUB) barsT.text("OD " + ((int) (g.progress * g.bars) + 1) + "/" + g.bars);
        else barsT.text(((int) (g.progress * g.bars) + 1) + "/" + g.bars);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateGestureTiles();
            playT.icon(engine.playing ? Theme.I_STOP : Theme.I_PLAY);
            playT.setOn(engine.playing);
            bpmView.invalidate();
            pad.invalidate();
            int rm = engine.recordingMode();
            if (rm == Engine.REC_OFF && recView != null) hideRecordUi();
            micT.setOn(rm == Engine.REC_INPUT);
            mixT.setOn(rm == Engine.REC_MIX);
            ui.postDelayed(this, 90);
        }
    };

    // ------------------------------------------------------------------ actions

    private static final String[] ARP_DIR = {"UP", "DN", "U-D", "RND", "ORD"};
    /** Notes per bar: 1/1 .. 1/64 with triplets (3 = 1/2T, 6 = 1/4T, 12 = 1/8T, 24 = 1/16T, 48 = 1/32T). */
    private static final int[] ARP_RATES = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] DIV_LIST = Slot.DIVS;

    static String rateName(int r) { return r % 3 == 0 ? "1/" + (r * 2 / 3) + "T" : "1/" + r; }
    private static int rateIndex(int r) {
        int best = 0;
        for (int i = 0; i < ARP_RATES.length; i++) if (Math.abs(ARP_RATES[i] - r) < Math.abs(ARP_RATES[best] - r)) best = i;
        return best;
    }
    private static String zoomLabel(float z) { return z < 9.95f && Math.abs(z - Math.round(z)) > 0.05f ? String.format(java.util.Locale.US, "%.1fx", z) : Math.round(z) + "x"; }

    /** Horizontal slide on a tile steps through a list of values: right = next, left = previous, one step per `stepDp`. */
    private Widgets.Tile.Drag slideSteps(final int[] list, final java.util.function.IntSupplier get, final java.util.function.IntConsumer set, final float stepDp) {
        return new Widgets.Tile.Drag() {
            int base = -1, last = -1;
            @Override public void drag(float total, float delta) {
                if (base < 0) {
                    int cur = get.getAsInt(), bi = 0;
                    for (int i = 0; i < list.length; i++) if (Math.abs(list[i] - cur) < Math.abs(list[bi] - cur)) bi = i;
                    base = bi; last = bi;
                }
                int idx = Math.max(0, Math.min(list.length - 1, base + Math.round(total / dp(stepDp))));
                if (idx != last) { last = idx; set.accept(list[idx]); }
            }
            @Override public void end() { base = -1; last = -1; }
        };
    }

    private Fx.Tone toneOf() {
        if (fxSel != Fx.DELAY && fxSel != Fx.REVERB) return null;
        return Fx.toneOf(masterFx ? engine.master[fxSel] : engine.slots[sel].fx[fxSel]);
    }

    private void refreshTone() {
        Fx.Tone tn = toneOf();
        if (tn == null) { toneT.text("LP/HP"); toneT.setOn(false); toneT.setDim(true); return; }
        toneT.setDim(false);
        toneT.setOn(tn.mode != 0);
        toneT.text(tn.mode == 0 ? "LP/HP" : Fx.Tone.NAMES[tn.mode] + " " + XYPadView.fmtHz(tn.cutoffHz()));
    }

    private void addTrack() {
        int n = engine.addSlot();
        if (n < 0) { toast("Máximo de " + Engine.SLOTS + " tracks"); return; }
        refresh();
        select(n);
        stripScroll.post(() -> stripScroll.fullScroll(View.FOCUS_RIGHT));
        toast("Track " + (n + 1) + " adicionada — carregue um som ou grave");
    }
    private int lastFxTap = -1; private long lastFxTapAt;

    /** Options of one effect (opens with a double tap on its button). */
    private void fxMenu(final int f) {
        final Fx.Effect ef = masterFx ? engine.master[f] : engine.slots[sel].fx[f];
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        final java.util.ArrayList<Runnable> acts = new java.util.ArrayList<>();
        final String[] fxNames = {"Distorção", "Filtro", "Amp mod / Gate", "Delay", "Reverb"};
        switch (f) {
            case Fx.FILTER: {
                final Fx.Filter fl = (Fx.Filter) ef;
                names.add((fl.highPass ? "  " : "● ") + "Passa-baixas (LP)"); acts.add(() -> { fl.highPass = false; });
                names.add((fl.highPass ? "● " : "  ") + "Passa-altas (HP)"); acts.add(() -> { fl.highPass = true; });
                break;
            }
            case Fx.AMP: {
                final Fx.AmpMod am = (Fx.AmpMod) ef;
                for (int i = 0; i < Fx.AmpMod.SHAPES.length; i++) { final int sh = i; names.add((am.shape == i ? "● " : "  ") + "Forma: " + Fx.AmpMod.SHAPES[i]); acts.add(() -> am.shape = sh); }
                names.add((am.sync ? "● " : "  ") + "Sincronizar a taxa com o BPM (X escolhe a nota)"); acts.add(() -> am.sync = !am.sync);
                break;
            }
            case Fx.DELAY: {
                final Fx.Delay dl = (Fx.Delay) ef;
                names.add((dl.sync ? "● " : "  ") + "Sincronizar com o BPM (1/64 a 1 compasso, com tercinas)"); acts.add(() -> dl.sync = !dl.sync);
                names.add((dl.follow ? "● " : "  ") + "Seguir a grade do slot (taxa do Arp / Q)"); acts.add(() -> { dl.follow = !dl.follow; if (dl.follow) dl.sync = true; });
                addToneItems(dl.tone, names, acts);
                break;
            }
            case Fx.REVERB: addToneItems(((Fx.Reverb) ef).tone, names, acts); break;
            default: break;
        }
        names.add("Voltar o pad para o centro"); acts.add(() -> ef.setXY(0.5f, 0.5f));
        new AlertDialog.Builder(this).setTitle(fxNames[f] + (masterFx ? " (master)" : "")).setItems(names.toArray(new String[0]), (d, w) -> { acts.get(w).run(); refresh(); }).show();
    }

    private void addToneItems(final Fx.Tone tn, java.util.List<String> names, java.util.List<Runnable> acts) {
        names.add((tn.mode == 0 ? "● " : "  ") + "Filtro do efeito: desligado"); acts.add(() -> tn.mode = 0);
        names.add((tn.mode == 1 ? "● " : "  ") + "Filtro do efeito: passa-baixas (deslize no botão LP/HP: corte)"); acts.add(() -> tn.mode = 1);
        names.add((tn.mode == 2 ? "● " : "  ") + "Filtro do efeito: passa-altas (deslize no botão LP/HP: corte)"); acts.add(() -> tn.mode = 2);
    }

    /** Master bus compressor and limiter. */
    private void dynamicsDialog() {
        final com.gfdrps.core.Dynamics dy = engine.dyn;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(dp(16)); box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.CheckBox cc = new android.widget.CheckBox(this); cc.setText("Compressor"); cc.setChecked(dy.compOn);
        final android.widget.TextView ca = new TextView(this); ca.setText("Intensidade");
        final android.widget.SeekBar cs = new android.widget.SeekBar(this); cs.setMax(100); cs.setProgress(Math.round(dy.amount * 100));
        final android.widget.CheckBox lc = new android.widget.CheckBox(this); lc.setText("Limiter"); lc.setChecked(dy.limOn);
        final android.widget.TextView la = new TextView(this); la.setText("Teto: " + String.format(java.util.Locale.US, "%.1f dB", dy.ceilingDb));
        final android.widget.SeekBar ls = new android.widget.SeekBar(this); ls.setMax(60); ls.setProgress(Math.round((dy.ceilingDb + 6f) * 10f));
        cc.setOnCheckedChangeListener((b, on) -> dy.compOn = on);
        lc.setOnCheckedChangeListener((b, on) -> dy.limOn = on);
        cs.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean u) { dy.amount = v / 100f; }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        ls.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean u) { dy.ceilingDb = v / 10f - 6f; la.setText("Teto: " + String.format(java.util.Locale.US, "%.1f dB", dy.ceilingDb)); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        box.addView(cc); box.addView(ca); box.addView(cs); box.addView(lc); box.addView(la); box.addView(ls);
        new AlertDialog.Builder(this).setTitle("Master: compressor e limiter").setView(box).setPositiveButton("OK", null).show();
    }

    private boolean bendHeld;
    private final Runnable bendBack = new Runnable() {
        @Override public void run() {
            if (bendHeld) return;
            boolean more = false;
            for (int i = 0; i < Engine.SLOTS; i++) {
                Slot sl = engine.slots[i];
                float b = sl.bend;
                if (b != 0f) { b = Math.abs(b) < 0.05f ? 0f : b * 0.6f; sl.bend = b; more |= b != 0f; }
            }
            if (more) ui.postDelayed(this, 16); else refresh();
        }
    };

    private void pitchDialog() {
        final Slot sl = engine.slots[sel];
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setText(String.format(java.util.Locale.US, "%.2f", sl.transpose + sl.fine));
        new AlertDialog.Builder(this).setTitle("Pitch (semitons, aceita decimais: 3.5 = 3 semitons e meio)").setView(et)
                .setPositiveButton("Aplicar", (d, w) -> {
                    try {
                        float v = Math.max(-24f, Math.min(24f, Float.parseFloat(et.getText().toString().trim().replace(',', '.'))));
                        int whole = Math.round(v);
                        sl.transpose = whole; sl.fine = v - whole;
                        refresh();
                    } catch (NumberFormatException ex) { toast("Valor inválido"); }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private static void show(Widgets.Tile t, boolean visible) { t.setVisibility(visible ? View.VISIBLE : View.GONE); }

    private float autoSens = 0.5f;

    /** Slicer: detect hits and place markers. Arpeggiator: toggle random order. */
    private void contextAction() {
        Slot s = engine.slots[sel];
        if (s.mode == Slot.ARP) { s.arpRandom = false; s.arpDir = (s.arpDir + 1) % 5; refresh(); return; }
        if (s.mode == Slot.LOOPER) { s.pingPong = !s.pingPong; toast(s.pingPong ? "Ping-pong: o loop vai e volta" : "Ping-pong desligado"); refresh(); return; }
        if (s.mode == Slot.LOOPPLAYER) {
            s.stretch = !s.stretch;
            toast(s.stretch ? "Stretch: o BPM muda o tempo sem mudar a altura (toque de novo no loop)" : "Stretch desligado: o BPM muda tempo e altura");
            refresh(); return;
        }
        if (s.mode != Slot.SLICER) return;
        engine.pushUndo(sel);
        final int target = sel;
        final float sens = autoSens;
        autoSens = autoSens >= 0.8f ? 0.2f : autoSens + 0.3f;       // tap again for a different sensitivity
        new Thread(() -> {
            int n = engine.autoSlice(target, 32, sens);
            ui.post(() -> toast(n == 0 ? "Nenhuma batida detectada" : n + " fatias detectadas (toque de novo p/ outra sensibilidade)"));
        }, "gfdrps-slice").start();
    }

    private void toggleFilterType() {
        if (fxSel != Fx.FILTER) { fxSel = Fx.FILTER; midi.selectedFx = fxSel; pad.select(sel, fxSel, masterFx); }
        Fx.Filter f = (Fx.Filter) (masterFx ? engine.master[Fx.FILTER] : engine.slots[sel].fx[Fx.FILTER]);
        f.highPass = !f.highPass;
        toast(f.highPass ? "Filtro: passa-altas (HP)" : "Filtro: passa-baixas (LP)");
        refresh();
    }

    private void panic() {
        wave.releaseAll();
        engine.stopAll();
        ui.postDelayed(this::refresh, 100);
    }

    private void toggleGestures() {
        switch (engine.gestureButton(sel, BARS[barsIdx])) {
            case 0: toast("Gesto armado: toque na waveform ou no pad de efeito para começar (" + BARS[barsIdx] + " compassos)"); break;
            case 1: toast("Gesto finalizado: ele toca em loop (● de novo = overdub, segure ● = menu)"); break;
            case 2: toast("Overdub: o que você tocar agora é somado ao gesto (undo remove)"); break;
            default: toast("Overdub finalizado"); break;
        }
        ui.postDelayed(this::refresh, 150);
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
        // ask which part of the file to import (start + length) before decoding anything
        new Thread(() -> {
            final double dur = SampleLoader.durationSec(this, uri);
            ui.post(() -> importDialog(uri, target, dur));
        }, "gfdrps-probe").start();
    }

    private static double parseTime(String t, double def) {
        try {
            t = t.trim().replace(',', '.');
            if (t.isEmpty()) return def;
            if (t.contains(":")) { String[] p = t.split(":"); double v = 0; for (String q : p) v = v * 60 + Double.parseDouble(q.trim()); return v; }
            return Double.parseDouble(t);
        } catch (Exception e) { return def; }
    }

    private static String fmtTime(double s) { int t = (int) Math.round(s); return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60); }

    private void importDialog(final Uri uri, final int target, final double dur) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(dp(16));
        box.setPadding(pad, pad / 2, pad, 0);
        TextView info = new TextView(this);
        info.setText(dur > 0 ? "Duração do arquivo: " + fmtTime(dur) + " (" + Math.round(dur) + " s). Use segundos ou min:seg." : "Duração desconhecida. Use segundos ou min:seg.");
        box.addView(info);
        final EditText st = new EditText(this); st.setHint("Início (ex.: 0 ou 1:30)"); st.setText("0");
        final EditText ln = new EditText(this); ln.setHint("Tamanho do trecho (vazio = tudo, máx. 4 min)");
        if (dur > 0) ln.setText(String.valueOf(Math.round(Math.min(dur, 240))));
        st.setInputType(android.text.InputType.TYPE_CLASS_TEXT); ln.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(st); box.addView(ln);
        new AlertDialog.Builder(this).setTitle("Importar trecho").setView(box)
                .setPositiveButton("Importar", (d, w) -> {
                    final double a = Math.max(0, parseTime(st.getText().toString(), 0));
                    double l = parseTime(ln.getText().toString(), 0);
                    final double len = l > 0 ? Math.min(l, 240) : 0;
                    toast("Carregando…");
                    new Thread(() -> {
                        try {
                            Sample s = SampleLoader.load(this, uri, a, len);
                            engine.loadSample(target, s);
                            ui.post(() -> { toast("Track " + (target + 1) + ": " + s.name + " (" + fmtTime(s.length() / (double) s.sampleRate) + ")"); refresh(); });
                        } catch (OutOfMemoryError oom) {
                            ui.post(() -> toast("Arquivo grande demais para a memória do aparelho. Use um trecho mais curto."));
                        } catch (Throwable e) {
                            final String m = e.getMessage();
                            ui.post(() -> toast("Erro: " + m));
                        }
                    }, "gfdrps-load").start();
                })
                .setNegativeButton("Cancelar", null).show();
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
        showRecordUi(mode);
    }

    private void showRecordUi(int mode) {
        hideRecordUi();
        final boolean full = mode == Engine.REC_INPUT;
        String title = (full ? "GRAVANDO MICROFONE" : "GRAVANDO O MIX") + " → TRACK " + (sel + 1);
        recView = new RecordView(this, engine, full, title, new RecordView.Listener() {
            @Override public void onSave() { finishRecording(); }
            @Override public void onCancel() { cancelRecording(); }
        });
        FrameLayout.LayoutParams lp;
        if (full) lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        else {      // resample: only a bar over the track strip, so the rest of the screen stays playable
            int hgt = Math.round(frame.getHeight() * 0.2f);
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(hgt, Math.round(dp(64))), Gravity.BOTTOM);
        }
        frame.addView(recView, lp);
    }

    private void hideRecordUi() {
        if (recView != null) { frame.removeView(recView); recView = null; }
    }

    private void cancelRecording() {
        mic.stop();
        engine.cancelRecording();
        hideRecordUi();
        toast("Gravação cancelada");
    }

    private void finishRecording() {
        int mode = engine.recordingMode();
        mic.stop();
        boolean ok = engine.stopRecording(mode == Engine.REC_MIX ? "mix" : "rec", engine.rate);
        hideRecordUi();
        ui.postDelayed(this::refresh, 150);
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
        String[] items = {"Salvar projeto", "Abrir projeto", "MIDI Learn…", "Limpar mapeamentos MIDI", "Reconectar MIDI",
                "Clock MIDI externo: " + (engine.midiSync ? "ligado" : "desligado"),
                "Master: compressor e limiter…",
                engine.isExporting() ? "Parar e salvar gravação do mix (WAV)" : "Gravar o que está tocando em WAV (Music/GfDrps)", "Sobre"};
        new AlertDialog.Builder(this).setTitle("Gf Drps").setItems(items, (d, w) -> {
            switch (w) {
                case 0: saveProject(); break;
                case 1: openProject(); break;
                case 2: learnMenu(); break;
                case 3: midi.clearBindings(); toast("Mapeamentos apagados"); break;
                case 4: midi.connect(); break;
                case 5: engine.midiSync = !engine.midiSync; toast(engine.midiSync ? "O tempo segue o clock MIDI externo (start/stop também)" : "Clock MIDI externo desligado"); break;
                case 6: dynamicsDialog(); break;
                case 7: exportMix(); break;
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
            try { ProjectStore.load(this, engine, names[w]); engine.clearUndo(); refresh(); toast("Projeto carregado"); }
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
        String[] items = {"Carregar arquivo…", sl.muted ? "Reativar slot" : "Silenciar slot",
                "Modo gate (só toca segurando): " + (sl.gate ? "ligado" : "desligado"),
                "Duração do loop: dobrar (" + (int) sl.sampleBeats + " batidas)", "Duração do loop: metade",
                "Recarregar som de demonstração",
                "Cortar o sample para início/fim (região do Keyboard)", "Exportar este slot em WAV"};
        new AlertDialog.Builder(this).setTitle("Slot " + (s + 1) + (sl.sample != null ? " – " + sl.sample.name : "")).setItems(items, (d, w) -> {
            switch (w) {
                case 0: pickFile(); break;
                case 1: sl.muted = !sl.muted; break;
                case 2: sl.gate = !sl.gate; break;
                case 3: sl.sampleBeats = Math.min(256, sl.sampleBeats * 2); break;
                case 4: sl.sampleBeats = Math.max(1, sl.sampleBeats / 2); break;
                case 5: engine.loadSample(s, com.gfdrps.core.DemoSynth.make(s, engine.rate, engine.bpm)); break;
                case 6:
                    if (sl.regStart <= 0f && sl.regEnd >= 1f) toast("Defina início e fim no modo Keyboard (botão de marcador) e volte aqui");
                    else { engine.trim(s); toast("Sample cortado (pode desfazer)"); }
                    break;
                default: exportSlot(s); break;
            }
            refresh();
        }).show();
    }

    private void gestureMenu() {
        final Slot s = engine.slots[sel];
        final boolean has = s.gestures.state == GestureRecorder.PLAYING;
        String[] items = {"Salvar como preset de gesto…", "Carregar preset de gesto…", "Apagar um preset…",
                "Quantizar na grade 1/16", "Quantizar na grade 1/8", "Quantizar na grade 1/4",
                "Deslocar tudo ◀ 1/16", "Deslocar tudo ▶ 1/16",
                "Apagar só os movimentos do pad de efeito", "Apagar só os toques da waveform", "Apagar o gesto inteiro"};
        new AlertDialog.Builder(this).setTitle("Gesto gravado").setItems(items, (d, w) -> {
            if (w == 1) { loadGesturePreset(); return; }
            if (w == 2) { deleteGesturePreset(); return; }
            if (!has) { toast("Grave um gesto primeiro (●)"); return; }
            switch (w) {
                case 0: saveGesturePreset(); return;
                case 3: engine.quantizeGestures(sel, 0.25); break;
                case 4: engine.quantizeGestures(sel, 0.5); break;
                case 5: engine.quantizeGestures(sel, 1.0); break;
                case 6: engine.nudgeGestures(sel, -0.25); break;
                case 7: engine.nudgeGestures(sel, 0.25); break;
                case 8: engine.removeGestureKind(sel, true); break;
                case 9: engine.removeGestureKind(sel, false); break;
                default: engine.clearGestures(sel); break;
            }
            toast("Gesto editado (undo desfaz)");
        }).show();
    }

    private void saveGesturePreset() {
        final EditText et = new EditText(this);
        et.setText("gesto " + (ProjectStore.listGesturePresets(this).length + 1));
        new AlertDialog.Builder(this).setTitle("Nome do preset de gesto").setView(et).setPositiveButton("Salvar", (d, w) -> {
            try { ProjectStore.saveGesturePreset(this, engine.slots[sel].gestures, et.getText().toString()); toast("Preset salvo"); }
            catch (Exception e) { toast("Erro: " + e.getMessage()); }
        }).setNegativeButton("Cancelar", null).show();
    }

    private void loadGesturePreset() {
        final String[] names = ProjectStore.listGesturePresets(this);
        if (names.length == 0) { toast("Nenhum preset de gesto salvo"); return; }
        new AlertDialog.Builder(this).setTitle("Carregar preset de gesto").setItems(names, (d, w) -> {
            try {
                engine.pushUndo(sel);
                ProjectStore.loadGesturePreset(this, engine, sel, names[w]);
                toast("Preset carregado na track " + (sel + 1) + " — toca no loop");
                ui.postDelayed(this::refresh, 150);
            } catch (Exception e) { toast("Erro: " + e.getMessage()); }
        }).show();
    }

    private void deleteGesturePreset() {
        final String[] names = ProjectStore.listGesturePresets(this);
        if (names.length == 0) { toast("Nenhum preset de gesto salvo"); return; }
        new AlertDialog.Builder(this).setTitle("Apagar preset de gesto").setItems(names, (d, w) -> {
            ProjectStore.deleteGesturePreset(this, names[w]); toast("Preset apagado");
        }).show();
    }

    private void exportMix() {
        if (!engine.isExporting()) { engine.startExport(); toast("Gravando o que você ouve… abra o menu Gf de novo para parar e salvar"); return; }
        final short[] pcm = engine.stopExport();
        if (pcm == null) { toast("Nada gravado"); return; }
        final String name = "GfDrps-mix-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        new Thread(() -> {
            try { String where = Exporter.saveWav(this, name, pcm, engine.rate, 2); ui.post(() -> toast("Salvo em " + where)); }
            catch (Throwable e) { final String m = e.getMessage(); ui.post(() -> toast("Erro ao exportar: " + m)); }
        }, "gfdrps-export").start();
    }

    private void exportSlot(int slot) {
        final com.gfdrps.core.Sample sm = engine.slots[slot].sample;
        if (sm == null) { toast("Slot vazio"); return; }
        final String name = "GfDrps-slot" + (slot + 1) + "-" + new java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(new java.util.Date());
        new Thread(() -> {
            try { String where = Exporter.saveSample(this, name, sm.data, sm.sampleRate); ui.post(() -> toast("Salvo em " + where)); }
            catch (Throwable e) { final String m = e.getMessage(); ui.post(() -> toast("Erro ao exportar: " + m)); }
        }, "gfdrps-export").start();
    }

    /** "?" shows the overlay with numbered controls (segure o "?" para o texto completo). */
    private void help() {
        if (helpOverlay != null) { closeHelp(); return; }
        Slot s = engine.slots[sel];
        helpOverlay = new HelpOverlay(this).legendIn(wave).onClose(this::closeHelp)
                .heading("Gf Drps v" + versionName() + " — " + Slot.MODE_NAMES[s.mode]);
        helpOverlay.add(gfT, "Menu: projetos, MIDI, clock, exportar").add(metroT, "Metrônomo").add(bpmView, "BPM: arraste na horizontal ou toque o ritmo")
                .add(masterS, "Volume master").add(stopT, "Para tudo (vozes, loops, gestos)").add(helpT, "Ajuda (segure: texto)")
                .add(fileT, "Carregar arquivo na track").add(revT, "Inverter o sample").add(undoT, "Desfazer (sample, fatias, região, gestos)").add(redoT, "Refazer o que foi desfeito")
                .add(modeTiles[0], "Modos: Slicer Looper Bow Tape Scratch Arp Keys Loop")
                .add(volS, "Volume do slot").add(attS, "Attack").add(decS, "Decay").add(susS, "Sustain").add(relS, "Release").add(panS, "Pan").add(sizeS, "Tamanho do grão (Bow)")
                .add(divTiles[0], "Fatias / grade (deslize p/ mudar)").add(ctxT, "Ação do modo: auto-fatiar/direção/P-P/stretch")
                .add(edT, "Editar fatias · região (Keys) · taxa do Arp (deslize)").add(trValT, "Transposição (deslize = pitch bend, 2 s = valor)").add(glideT, "Legato (Keys)")
                .add(recGestT, "Gestos: gravar · overdub (segure: menu/presets)").add(barsT, "Compassos do gesto").add(infT, "Sustentar (∞)")
                .add(lkT, "Latch: som continua ao soltar").add(qT, "Quantizar: deslize para mudar a grade")
                .add(autoT, "Slicer: toca as fatias sozinho").add(shufT, "Shuffle do auto-play").add(zoomT, "Zoom: toque ou deslize (barra no topo rola)")
                .add(pad, "Pad XY do efeito (entra nos gestos)").add(fxTiles[0], "Efeitos (2 toques = opções do efeito)")
                .add(latchT, "Hold: efeito fica ligado").add(syncT, "Delay sincronizado ao BPM").add(toneT, "LP/HP do Delay/Reverb (deslize: corte)").add(masterFxT, "M: efeito no master")
                .add(playT, "Play / Stop").add(strips[0], "Tracks (segure: menu)").add(addT, "Adicionar track (até 12)").add(micT, "Gravar (tela de gravação)").add(mixT, "Resample do mix");
        ((ViewGroup) getWindow().getDecorView()).addView(helpOverlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeHelp() {
        if (helpOverlay == null) return;
        ((ViewGroup) getWindow().getDecorView()).removeView(helpOverlay);
        helpOverlay = null;
    }

    private void helpText() {
        String t = "Slicer – toque numa fatia; altura = volume. Botão de marcador: editar fatias (toque = novo marcador, arraste = mover, toque no marcador = apagar, segurar o botão = voltar à grade). A estrela acha as batidas sozinha.\n"
                + "Looper – cada par de dedos define um loop (até 4 loops ao mesmo tempo); cruzar os dedos de um par toca em reverse; P-P liga o ping-pong (vai e volta); 4/8/16/32 prende os marcadores na grade.\n"
                + "Bow – segure: um grão do som vira textura contínua; posição = x, volume = altura do dedo, slider azul = tamanho do grão; até 4 notas.\n"
                + "Tape – arraste p/ direita acelera, p/ esquerda inverte; soltar = tape stop. Com 4/8/16/32 a velocidade anda em degraus.\n"
                + "Scratch – arraste o dedo como um vinil.\n"
                + "Arpeggiator – segure dedos: dispara as fatias sob os dedos no ritmo do BPM; botão rosa = direção (UP, DN, U-D, RND, ORD), botão amarelo = taxa: toque para trocar ou deslize na horizontal (1/1 a 1/64, com tercinas); os números = nº de fatias (também deslizam).\n"
                + "Keyboard – teclas cromáticas (∞ sustenta, curva = legato); o botão de marcador edita início/fim do sample (use o zoom).\n"
                + "Loop Player – o dedo é a agulha: tocar/arrastar leva o playhead para aquele ponto (o primeiro toque liga o loop sincronizado; use Stop para parar); STR (stretch) deixa o BPM mudar o tempo sem mudar a altura.\n\n"
                + "◀| (esquerda) inverte o sample. Filtro: toque de novo no ícone do filtro para alternar passa-baixas/passa-altas (o ícone muda).\n■ para tudo: vozes, loops e gestos; ▶ retoma.\n"
                + "Efeitos: toque 2x em um efeito para abrir suas opções (forma do amp mod/gate, sync e grade do delay, LP/HP...). Master: menu Gf → compressor e limiter. Gestos: ● grava; ● de novo termina e mantém; ● com o gesto tocando = overdub; segure ● para presets/editar. Transposição: deslize no valor = pitch bend; segure 2 s para digitar o pitch. Zoom: deslizar para cima/baixo move a faixa. Slicer com legato: deslizar entre fatias faz glissando. Arp: botão ✎ edita as fatias, o botão amarelo é a taxa. Botões que não servem ao modo somem. Importar: escolha início e tamanho do trecho.\nRefazer (redo) fica ao lado do desfazer. Zoom: toque ou deslize o dedo no botão. LP/HP: botão do Delay/Reverb liga o filtro do efeito; deslize para mudar o corte. + adiciona tracks (até 12). ● mic abre a tela de gravação; ■ SALVAR grava no slot.\nSliders: volume · attack · decay · sustain · release · pan.\n"
                + "● arma a gravação de gestos: ela começa sozinha no primeiro toque (waveform ou pad de efeito), dura N compassos e repete em loop.\n"
                + "Latch: o som continua ao soltar e ao trocar de modo (um novo gesto ou desligar o latch solta). Q: quantiza os toques do Slicer/Keyboard na grade do BPM (deslize no botão para mudar a grade). ▶ (Slicer): toca as fatias sozinho, 🎲 embaralha. sync: delay travado no BPM. Arrastar o BPM: horizontal.\nRodapé: toque num slot para escolher, segure para o menu; mic grava no slot; círculos duplos gravam o mix (resample).";
        new AlertDialog.Builder(this).setTitle("Como tocar").setMessage(t).setPositiveButton("OK", null).show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    @SuppressWarnings("deprecation")
    private long versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Gf Drps v" + versionName()).setMessage("Versão " + versionName() + " (build " + versionCode() + ")\nInstrumento multitoque de samples para Android.\nTaxa de áudio: " + engine.rate + " Hz\nMIDI mapeado: " + midi.bindingCount())
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ BPM control

    /** Drag up/down to change tempo; quick taps set tempo by tapping. */
    private static final class BpmView extends View {
        private final Engine engine;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, startX;
        private long lastTap;
        private double tapAvg;

        BpmView(android.content.Context c, Engine e) { super(c); engine = e; setClickable(true); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lastX = startX = e.getX(); getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lastX;               // horizontal: right = faster (the top of the screen is the edge)
                    lastX = e.getX();
                    engine.bpm = Math.max(40.0, Math.min(240.0, engine.bpm + dx * 0.2));
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (Math.abs(e.getX() - startX) < 12) {
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
            p.setTextSize(h * 0.3f);
            p.setColor(0x66FFFFFF);
            c.drawText("\u25C0", h * 0.3f, h / 2 + h * 0.1f, p);
            c.drawText("\u25B6", w - h * 0.3f, h / 2 + h * 0.1f, p);
        }
    }
}
