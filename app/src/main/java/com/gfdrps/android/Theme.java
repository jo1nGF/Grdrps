package com.gfdrps.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Colors and hand-drawn vector icons (no image assets needed). */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xFF0B0D10;
    public static final int PANEL = 0xFF181B20;
    public static final int TILE = 0xFF22262D;
    public static final int TILE_ON = 0xFF343A44;
    public static final int LINE = 0xFF0B0D10;
    public static final int TEXT = 0xFFE9EDF2;
    public static final int DIM = 0xFF6C7480;
    public static final int WAVE_BG = 0xFF14171B;
    /** Accent per play mode: slicer, looper, bow, tape, scratch, arp, keyboard, loop player. */
    public static final int[] MODE = {
            0xFF3D8BFF, 0xFFFF4D5E, 0xFF52D273, 0xFFFF9A2E, 0xFFFFD23F, 0xFFFF4FA3, 0xFF7C6CFF, 0xFF2FE0C8};
    public static final int[] FX = {0xFFFF6B3D, 0xFF3DD6FF, 0xFFB46CFF, 0xFF6CFF9E, 0xFFFFD23F};

    public static final int I_SLICER = 0, I_LOOPER = 1, I_BOW = 2, I_TAPE = 3, I_SCRATCH = 4, I_ARP = 5, I_KEYS = 6,
            I_LOOPPLAY = 7, I_DIST = 8, I_FILTER = 9, I_AMP = 10, I_DELAY = 11, I_REVERB = 12, I_FILE = 13,
            I_STOP = 14, I_PLAY = 15, I_REC = 16, I_METRO = 17, I_HELP = 18, I_GATE = 19, I_GLIDE = 20, I_DICE = 21,
            I_LOOPINF = 22, I_CLEAR = 23, I_MIX = 24, I_MIC = 25, I_REVERSE = 26, I_MARK = 27, I_AUTO = 28;

    private static final Path P = new Path();
    private static final RectF R = new RectF();

    /** Draws icon id centered at cx,cy with half-size r. Uses the paint's color; resets style to its own needs. */
    public static void icon(Canvas c, int id, float cx, float cy, float r, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, r * 0.14f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        P.reset();
        switch (id) {
            case I_SLICER:
                for (int i = -1; i <= 1; i++) c.drawLine(cx + i * r * 0.7f, cy - r * 0.8f, cx + i * r * 0.7f, cy + r * 0.8f, p);
                c.drawLine(cx - r, cy, cx + r, cy, p);
                break;
            case I_LOOPER:
                c.drawLine(cx - r * 0.7f, cy - r * 0.9f, cx - r * 0.7f, cy + r * 0.9f, p);
                c.drawLine(cx + r * 0.7f, cy - r * 0.9f, cx + r * 0.7f, cy + r * 0.9f, p);
                c.drawLine(cx - r * 0.7f, cy, cx + r * 0.7f, cy, p);
                c.drawLine(cx + r * 0.3f, cy - r * 0.35f, cx + r * 0.7f, cy, p);
                c.drawLine(cx + r * 0.3f, cy + r * 0.35f, cx + r * 0.7f, cy, p);
                break;
            case I_BOW:
                R.set(cx - r, cy - r * 0.2f, cx + r, cy + r * 1.8f);
                c.drawArc(R, 200, 140, false, p);
                c.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.6f, p);
                c.drawCircle(cx, cy - r * 0.9f, r * 0.12f, p);
                break;
            case I_TAPE:
                c.drawCircle(cx - r * 0.55f, cy, r * 0.42f, p);
                c.drawCircle(cx + r * 0.55f, cy, r * 0.42f, p);
                c.drawLine(cx - r * 0.55f, cy + r * 0.42f, cx + r * 0.55f, cy + r * 0.42f, p);
                break;
            case I_SCRATCH:
                c.drawCircle(cx, cy, r * 0.85f, p);
                c.drawCircle(cx, cy, r * 0.2f, p);
                R.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f);
                c.drawArc(R, -30, 70, false, p);
                break;
            case I_ARP:
                P.moveTo(cx - r, cy + r * 0.8f);
                P.lineTo(cx - r, cy + r * 0.3f); P.lineTo(cx - r * 0.4f, cy + r * 0.3f);
                P.lineTo(cx - r * 0.4f, cy - r * 0.2f); P.lineTo(cx + r * 0.2f, cy - r * 0.2f);
                P.lineTo(cx + r * 0.2f, cy - r * 0.7f); P.lineTo(cx + r, cy - r * 0.7f);
                c.drawPath(P, p);
                break;
            case I_KEYS:
                R.set(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f);
                c.drawRoundRect(R, r * 0.1f, r * 0.1f, p);
                for (int i = -1; i <= 1; i++) c.drawLine(cx + i * r * 0.5f, cy - r * 0.7f + (i == 0 ? 0 : 0), cx + i * r * 0.5f, cy + r * 0.7f, p);
                break;
            case I_LOOPPLAY:
                R.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f);
                c.drawArc(R, -60, 300, false, p);
                c.drawLine(cx + r * 0.4f, cy - r * 0.95f, cx + r * 0.4f, cy - r * 0.45f, p);
                c.drawLine(cx + r * 0.4f, cy - r * 0.45f, cx + r * 0.9f, cy - r * 0.45f, p);
                break;
            case I_DIST:
                P.moveTo(cx - r, cy + r * 0.5f); P.lineTo(cx - r * 0.4f, cy - r * 0.6f); P.lineTo(cx + r * 0.1f, cy + r * 0.6f);
                P.lineTo(cx + r * 0.5f, cy - r * 0.6f); P.lineTo(cx + r, cy);
                c.drawPath(P, p);
                break;
            case I_FILTER:
                P.moveTo(cx - r, cy - r * 0.3f); P.lineTo(cx - r * 0.1f, cy - r * 0.3f);
                P.quadTo(cx + r * 0.2f, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.2f);
                P.lineTo(cx + r, cy + r * 0.8f);
                c.drawPath(P, p);
                break;
            case I_AMP:
                P.moveTo(cx - r, cy);
                for (int i = 0; i <= 20; i++) {
                    float t = i / 20f;
                    P.lineTo(cx - r + t * 2 * r, cy - (float) Math.sin(t * Math.PI * 3) * r * 0.6f * (0.3f + 0.7f * (1 - Math.abs(2 * t - 1))));
                }
                c.drawPath(P, p);
                break;
            case I_DELAY:
                for (int i = 0; i < 4; i++) {
                    float h = r * (1.4f - i * 0.38f);
                    c.drawLine(cx - r * 0.9f + i * r * 0.6f, cy - h / 2, cx - r * 0.9f + i * r * 0.6f, cy + h / 2, p);
                }
                break;
            case I_REVERB:
                c.drawCircle(cx, cy, r * 0.25f, p);
                c.drawCircle(cx, cy, r * 0.6f, p);
                c.drawCircle(cx, cy, r * 0.95f, p);
                break;
            case I_FILE:
                P.moveTo(cx - r * 0.6f, cy - r); P.lineTo(cx + r * 0.2f, cy - r); P.lineTo(cx + r * 0.6f, cy - r * 0.6f);
                P.lineTo(cx + r * 0.6f, cy + r); P.lineTo(cx - r * 0.6f, cy + r); P.close();
                c.drawPath(P, p);
                c.drawLine(cx - r * 0.25f, cy + r * 0.1f, cx + r * 0.25f, cy + r * 0.1f, p);
                c.drawLine(cx, cy - r * 0.35f, cx, cy + r * 0.55f, p);
                break;
            case I_STOP:
                p.setStyle(Paint.Style.FILL);
                c.drawRect(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, p);
                break;
            case I_PLAY:
                p.setStyle(Paint.Style.FILL);
                P.moveTo(cx - r * 0.5f, cy - r * 0.7f); P.lineTo(cx + r * 0.7f, cy); P.lineTo(cx - r * 0.5f, cy + r * 0.7f); P.close();
                c.drawPath(P, p);
                break;
            case I_REC:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r * 0.6f, p);
                break;
            case I_METRO:
                P.moveTo(cx - r * 0.7f, cy + r); P.lineTo(cx - r * 0.3f, cy - r); P.lineTo(cx + r * 0.3f, cy - r); P.lineTo(cx + r * 0.7f, cy + r); P.close();
                c.drawPath(P, p);
                c.drawLine(cx, cy + r * 0.6f, cx + r * 0.4f, cy - r * 0.6f, p);
                break;
            case I_HELP:
                R.set(cx - r * 0.4f, cy - r * 0.9f, cx + r * 0.4f, cy - r * 0.1f);
                c.drawArc(R, 180, 270, false, p);
                c.drawLine(cx, cy - r * 0.1f, cx, cy + r * 0.2f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + r * 0.7f, r * 0.1f, p);
                break;
            case I_GATE:
                P.moveTo(cx - r, cy + r * 0.6f); P.lineTo(cx - r * 0.3f, cy + r * 0.6f); P.lineTo(cx - r * 0.3f, cy - r * 0.6f);
                P.lineTo(cx + r * 0.4f, cy - r * 0.6f); P.lineTo(cx + r * 0.4f, cy + r * 0.6f); P.lineTo(cx + r, cy + r * 0.6f);
                c.drawPath(P, p);
                break;
            case I_GLIDE:
                P.moveTo(cx - r, cy + r * 0.6f);
                P.cubicTo(cx - r * 0.1f, cy + r * 0.6f, cx - r * 0.1f, cy - r * 0.6f, cx + r, cy - r * 0.6f);
                c.drawPath(P, p);
                break;
            case I_DICE:
                R.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f);
                c.drawRoundRect(R, r * 0.25f, r * 0.25f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - r * 0.35f, cy - r * 0.35f, r * 0.1f, p);
                c.drawCircle(cx + r * 0.35f, cy + r * 0.35f, r * 0.1f, p);
                c.drawCircle(cx, cy, r * 0.1f, p);
                break;
            case I_LOOPINF:
                R.set(cx - r, cy - r * 0.45f, cx, cy + r * 0.45f);
                c.drawArc(R, 0, 360, false, p);
                R.set(cx, cy - r * 0.45f, cx + r, cy + r * 0.45f);
                c.drawArc(R, 0, 360, false, p);
                break;
            case I_CLEAR:
                c.drawLine(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, p);
                c.drawLine(cx + r * 0.6f, cy - r * 0.6f, cx - r * 0.6f, cy + r * 0.6f, p);
                break;
            case I_MIX:
                c.drawCircle(cx - r * 0.35f, cy, r * 0.55f, p);
                c.drawCircle(cx + r * 0.35f, cy, r * 0.55f, p);
                break;
            case I_MIC:
                R.set(cx - r * 0.3f, cy - r * 0.9f, cx + r * 0.3f, cy + r * 0.2f);
                c.drawRoundRect(R, r * 0.3f, r * 0.3f, p);
                R.set(cx - r * 0.6f, cy - r * 0.4f, cx + r * 0.6f, cy + r * 0.6f);
                c.drawArc(R, 0, 180, false, p);
                c.drawLine(cx, cy + r * 0.6f, cx, cy + r * 0.95f, p);
                break;
            case I_REVERSE:
                c.drawLine(cx + r, cy, cx - r * 0.9f, cy, p);
                c.drawLine(cx - r * 0.9f, cy, cx - r * 0.35f, cy - r * 0.55f, p);
                c.drawLine(cx - r * 0.9f, cy, cx - r * 0.35f, cy + r * 0.55f, p);
                c.drawLine(cx + r * 0.9f, cy - r * 0.6f, cx + r * 0.9f, cy + r * 0.6f, p);
                break;
            case I_MARK:
                c.drawLine(cx, cy - r * 0.3f, cx, cy + r, p);
                P.moveTo(cx - r * 0.45f, cy - r); P.lineTo(cx + r * 0.45f, cy - r); P.lineTo(cx, cy - r * 0.35f); P.close();
                c.drawPath(P, p);
                c.drawLine(cx - r, cy + r * 0.35f, cx - r * 0.5f, cy + r * 0.35f, p);
                c.drawLine(cx + r * 0.5f, cy + r * 0.35f, cx + r, cy + r * 0.35f, p);
                break;
            case I_AUTO:
                c.drawLine(cx, cy - r, cx, cy + r, p);
                c.drawLine(cx - r, cy, cx + r, cy, p);
                c.drawLine(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f, p);
                c.drawLine(cx - r * 0.55f, cy + r * 0.55f, cx + r * 0.55f, cy - r * 0.55f, p);
                break;
            default: break;
        }
        p.setStyle(Paint.Style.FILL);
    }
}
