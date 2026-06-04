package com.suraksha.ai.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * SplashView — enhanced Canvas animation for the Suraksha AI splash screen.
 *
 * Phases:
 *   Phase 1 (0 – 1 100 ms) : Multi-layer electric arc traces clockwise; bright comet
 *                             tip with spark particles; shield scales in with overshoot.
 *   Phase 2 (600 – 2 300 ms): Radiant burst — alternating spokes + concentric rings
 *                             expand with staggered fade; background deepens.
 *   Phase 3 (1 800 – 2 600 ms): Scanline sweep over the shield (holographic read effect).
 *   Phase 4 (2 000 – 3 400 ms): "Suraksha AI" types in character-by-character with a
 *                             blinking cursor; ring settles into a slow pulse.
 *
 * Total ≈ 3 500 ms.
 */
public class SplashView extends View {

    // ── Durations ─────────────────────────────────────────────────────────────
    private static final long TOTAL_MS       = 3_500L;
    private static final long ARC_END_MS     = 1_100L;
    private static final long SHIELD_END_MS  = 1_050L;
    private static final long BURST_START_MS =   600L;
    private static final long BURST_END_MS   = 2_300L;
    private static final long SCAN_START_MS  = 1_800L;
    private static final long SCAN_END_MS    = 2_600L;
    private static final long TEXT_START_MS  = 2_000L;
    private static final long TEXT_END_MS    = 3_200L;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int BG_TOP       = Color.parseColor("#020b16");
    private static final int BG_BOT       = Color.parseColor("#061322");
    private static final int CYAN_BRIGHT  = Color.parseColor("#00e5ff");
    private static final int CYAN_MID     = Color.parseColor("#00bcd4");
    private static final int CYAN_DARK    = Color.parseColor("#0097a7");
    private static final int WHITE        = Color.parseColor("#ffffff");
    private static final int SHIELD_L     = Color.parseColor("#006080");
    private static final int SHIELD_R     = Color.parseColor("#00e5ff");
    private static final int SHIELD_SHINE = Color.parseColor("#aaffffff");

    // ── Layout state ──────────────────────────────────────────────────────────
    private float cx, cy, ringR, shieldH;
    private float progress = 0f;

    // ── Arc noise (stable random offsets for lightning effect) ────────────────
    private static final int SEGS = 120;           // 3° per segment
    private final float[] arcNoise = new float[SEGS];   // ±1 radial offset multiplier
    private final float[] arcJitter = new float[SEGS];  // secondary noise layer

    // ── Spark particles ───────────────────────────────────────────────────────
    private static final int SPARK_COUNT = 22;
    private final float[] sparkAngle  = new float[SPARK_COUNT];
    private final float[] sparkSpeed  = new float[SPARK_COUNT];
    private final float[] sparkLen    = new float[SPARK_COUNT];
    private final float[] sparkOff    = new float[SPARK_COUNT]; // radial offset

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint bgPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcGlowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spokePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Reusable geometry ─────────────────────────────────────────────────────
    private final RectF arcOval    = new RectF();
    private final Path  shieldPath = new Path();

    // ── Animator ──────────────────────────────────────────────────────────────
    private ValueAnimator animator;
    private Runnable completionCallback;

    // ── Constructors ──────────────────────────────────────────────────────────

    public SplashView(Context context) { super(context); init(); }

    public SplashView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); init();
    }

    public SplashView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void init() {
        Random rng = new Random(7);

        // Arc noise — two independent layers for more chaotic lightning
        for (int i = 0; i < SEGS; i++) {
            arcNoise[i]  = (rng.nextFloat() - 0.5f) * 2f;   // ±1
            arcJitter[i] = (rng.nextFloat() - 0.5f) * 2f;
        }

        // Sparks — fixed random trajectory, animated by arc progress
        for (int i = 0; i < SPARK_COUNT; i++) {
            sparkAngle[i] = rng.nextFloat() * 360f;
            sparkSpeed[i] = 0.6f + rng.nextFloat() * 1.4f;
            sparkLen[i]   = 0.04f + rng.nextFloat() * 0.09f;
            sparkOff[i]   = (rng.nextFloat() - 0.5f) * 0.12f;
        }

        ringPaint.setStyle(Paint.Style.STROKE);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeJoin(Paint.Join.ROUND);

        arcGlowPaint.setStyle(Paint.Style.STROKE);
        arcGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        spokePaint.setStyle(Paint.Style.STROKE);
        spokePaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.FILL);
        sparkPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);
        scanPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setLetterSpacing(0.08f);

        textGlowPaint.setTextAlign(Paint.Align.CENTER);
        textGlowPaint.setFakeBoldText(true);
        textGlowPaint.setLetterSpacing(0.08f);

        cursorPaint.setStyle(Paint.Style.FILL);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void startAnimation(Runnable onComplete) {
        completionCallback = onComplete;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(TOTAL_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (completionCallback != null) completionCallback.run();
            }
        });
        animator.start();
    }

    public void stopAnimation() {
        if (animator != null) animator.cancel();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        cx = w * 0.5f;
        cy = h * 0.46f;
        ringR  = Math.min(w, h) * 0.32f;
        shieldH = ringR * 0.74f;

        bgPaint.setShader(new LinearGradient(cx, 0, cx, h,
                BG_TOP, BG_BOT, Shader.TileMode.CLAMP));

        textPaint.setTextSize(w * 0.082f);
        textGlowPaint.setTextSize(w * 0.082f);
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (ringR <= 0f) return;

        long ms = (long)(progress * TOTAL_MS);

        drawBackground(canvas, ms);
        drawAmbientGlow(canvas, ms);

        if (ms < ARC_END_MS + 300)  drawElectricArc(canvas, ms);
        if (ms >= BURST_START_MS)   drawBurst(canvas, ms);

        drawRing(canvas, ms);
        drawShield(canvas, ms);

        if (ms >= SCAN_START_MS && ms <= SCAN_END_MS + 100) drawScanline(canvas, ms);
        if (ms >= TEXT_START_MS)    drawText(canvas, ms);
    }

    // ── Background ───────────────────────────────────────────────────────────

    private void drawBackground(Canvas canvas, long ms) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        // Subtle vignette — dark corners
        float vR = Math.max(getWidth(), getHeight()) * 0.85f;
        if (vR > 0f) {
            glowPaint.setShader(new RadialGradient(cx, cy, vR,
                    new int[]{Color.TRANSPARENT, Color.parseColor("#66000000")},
                    new float[]{0.4f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), glowPaint);
        }
    }

    // ── Ambient glow ─────────────────────────────────────────────────────────

    private void drawAmbientGlow(Canvas canvas, long ms) {
        float burst  = burstFraction(ms);
        float pulse  = 0.7f + 0.3f * (float) Math.sin(ms * 0.003f);
        float radius = ringR * (1.2f + burst * 0.9f) * pulse;
        if (radius <= 0f) return;

        glowPaint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{
                        applyAlpha(CYAN_DARK, (0.18f + burst * 0.28f) * pulse),
                        applyAlpha(CYAN_DARK, 0.06f),
                        Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, glowPaint);
    }

    // ── Electric arc ─────────────────────────────────────────────────────────

    private void drawElectricArc(Canvas canvas, long ms) {
        float t = clamp01((float) ms / ARC_END_MS);
        if (t <= 0f) return;

        int visSegs = (int)(t * SEGS);
        if (visSegs < 1) return;

        float noiseAmp  = ringR * 0.055f * (1f - t * 0.7f);
        float noiseAmp2 = ringR * 0.022f * (1f - t * 0.5f);

        // ── Outer glow pass (wide, soft) ─────────────────────────────────────
        Path glowPath = buildArcPath(visSegs, noiseAmp * 0.4f, 0);
        arcGlowPaint.setStrokeWidth(ringR * 0.055f);
        arcGlowPaint.setColor(applyAlpha(CYAN_DARK, 0.35f));
        canvas.drawPath(glowPath, arcGlowPaint);

        // ── Mid glow pass ────────────────────────────────────────────────────
        Path midPath = buildArcPath(visSegs, noiseAmp, 0);
        arcGlowPaint.setStrokeWidth(ringR * 0.025f);
        arcGlowPaint.setColor(applyAlpha(CYAN_MID, 0.55f));
        canvas.drawPath(midPath, arcGlowPaint);

        // ── Sharp core arc ────────────────────────────────────────────────────
        Path corePath = buildArcPath(visSegs, noiseAmp, noiseAmp2);
        arcPaint.setStrokeWidth(ringR * 0.012f);
        arcPaint.setColor(applyAlpha(WHITE, 0.9f));
        canvas.drawPath(corePath, arcPaint);

        // ── Comet tip: bright bloom + white core ──────────────────────────────
        float tipAngle = (float) Math.toRadians(-90.0 + visSegs * (360.0 / SEGS));
        float tipX = cx + (float) Math.cos(tipAngle) * ringR;
        float tipY = cy + (float) Math.sin(tipAngle) * ringR;

        float bloomR = ringR * 0.22f;
        if (bloomR > 0f) {
            glowPaint.setShader(new RadialGradient(tipX, tipY, bloomR,
                    new int[]{applyAlpha(WHITE, 0.9f), applyAlpha(CYAN_BRIGHT, 0.6f), Color.TRANSPARENT},
                    new float[]{0f, 0.35f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(tipX, tipY, bloomR, glowPaint);
        }

        // ── Spark particles flying off the tip ───────────────────────────────
        drawSparks(canvas, ms, t, tipX, tipY);
    }

    private Path buildArcPath(int visSegs, float amp1, float amp2) {
        Path p = new Path();
        boolean first = true;
        for (int i = 0; i <= visSegs && i < SEGS; i++) {
            float angle = (float) Math.toRadians(-90.0 + i * (360.0 / SEGS));
            float disp  = arcNoise[i] * amp1 + arcJitter[i] * amp2;
            float r     = ringR + disp;
            float px    = cx + (float) Math.cos(angle) * r;
            float py    = cy + (float) Math.sin(angle) * r;
            if (first) { p.moveTo(px, py); first = false; }
            else        p.lineTo(px, py);
        }
        return p;
    }

    // ── Sparks ────────────────────────────────────────────────────────────────

    private void drawSparks(Canvas canvas, long ms, float arcT, float tipX, float tipY) {
        // Sparks are most dense at arc peak (arcT ~ 0.4–0.8)
        float density = (float) Math.sin(arcT * Math.PI);
        if (density <= 0f) return;

        // Use ms as a pseudo-time seed so they animate
        float time = ms / 300f;

        sparkPaint.setStrokeWidth(ringR * 0.009f);
        for (int i = 0; i < SPARK_COUNT; i++) {
            // Each spark has its own phase offset
            float phase = (time * sparkSpeed[i] + i * 0.37f) % 1f;
            if (phase > 0.55f) continue;   // only alive for first 55% of cycle

            float lifeAlpha = 1f - phase / 0.55f;
            float sparkR = ringR * (sparkLen[i] + phase * 0.18f);

            double dir = Math.toRadians(sparkAngle[i] + phase * 25f * sparkSpeed[i]);
            float x1 = tipX + (float) Math.cos(dir) * ringR * sparkOff[i];
            float y1 = tipY + (float) Math.sin(dir) * ringR * sparkOff[i];
            float x2 = x1  + (float) Math.cos(dir) * sparkR;
            float y2 = y1  + (float) Math.sin(dir) * sparkR;

            sparkPaint.setColor(applyAlpha(CYAN_BRIGHT, density * lifeAlpha * 0.8f));
            canvas.drawLine(x1, y1, x2, y2, sparkPaint);
        }
    }

    // ── Ring ─────────────────────────────────────────────────────────────────

    private void drawRing(Canvas canvas, long ms) {
        float fadeIn = clamp01((float)(ms - (ARC_END_MS - 400)) / 550f);
        if (fadeIn <= 0f) return;

        // Breathing pulse — frequency slows down after burst
        float freq   = (ms < BURST_END_MS) ? 0.005f : 0.0025f;
        float pulse  = 0.82f + 0.18f * (float) Math.sin(ms * freq);
        float alpha  = fadeIn * pulse;

        arcOval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);

        // Outermost soft halo
        ringPaint.setStrokeWidth(ringR * 0.06f);
        ringPaint.setColor(applyAlpha(CYAN_DARK, alpha * 0.2f));
        canvas.drawOval(arcOval, ringPaint);

        // Mid glow ring
        ringPaint.setStrokeWidth(ringR * 0.028f);
        ringPaint.setColor(applyAlpha(CYAN_MID, alpha * 0.45f));
        canvas.drawOval(arcOval, ringPaint);

        // Sharp bright ring
        ringPaint.setStrokeWidth(ringR * 0.01f);
        ringPaint.setColor(applyAlpha(CYAN_BRIGHT, alpha * 0.95f));
        canvas.drawOval(arcOval, ringPaint);
    }

    // ── Burst ────────────────────────────────────────────────────────────────

    private void drawBurst(Canvas canvas, long ms) {
        float t = burstFraction(ms);
        if (t <= 0f) return;

        int spokeCount = 40;
        float spokeMinR = ringR * 1.04f;
        float spokeMaxR = ringR * (1.6f + t * 0.8f);

        // Draw spokes in two passes: dim outer → bright inner
        for (int pass = 0; pass < 2; pass++) {
            float widthFrac = (pass == 0) ? 0.016f : 0.007f;
            float alphaFrac = (pass == 0) ? 0.3f   : 0.7f;
            spokePaint.setStrokeWidth(ringR * widthFrac);

            for (int i = 0; i < spokeCount; i++) {
                float angle   = (float) Math.toRadians(i * (360.0 / spokeCount));
                float cos     = (float) Math.cos(angle);
                float sin     = (float) Math.sin(angle);
                float lenFrac = (i % 3 == 0) ? 1f : (i % 3 == 1) ? 0.65f : 0.4f;
                float r2      = spokeMinR + (spokeMaxR - spokeMinR) * lenFrac;
                float alpha   = t * alphaFrac * lenFrac;
                spokePaint.setColor(applyAlpha(CYAN_BRIGHT, alpha));
                canvas.drawLine(cx + cos * spokeMinR, cy + sin * spokeMinR,
                        cx + cos * r2,        cy + sin * r2, spokePaint);
            }
        }

        // Concentric expanding rings (5 staggered)
        for (int ring = 0; ring < 5; ring++) {
            float offset = ring / 5f;
            float phase  = (t + offset) % 1f;
            float r      = ringR * (1.02f + phase * 1.4f);
            float a      = (1f - phase) * t * 0.45f;
            if (a <= 0.01f || r <= 0f) continue;
            ringPaint.setStrokeWidth(ringR * 0.007f);
            ringPaint.setColor(applyAlpha(CYAN_MID, a));
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            canvas.drawOval(oval, ringPaint);
        }
    }

    // ── Shield ────────────────────────────────────────────────────────────────

    private void drawShield(Canvas canvas, long ms) {
        if (shieldH <= 0f) return;

        float scaleT = clamp01((float) ms / SHIELD_END_MS);
        float scale  = easeOutBack(scaleT);
        if (scale <= 0f) return;

        // Subtle idle breathe after fully revealed
        float breathe = 1f;
        if (ms > SHIELD_END_MS) {
            breathe = 1f + 0.012f * (float) Math.sin(ms * 0.0028f);
        }
        scale *= breathe;

        float sh = shieldH * scale;
        float sw = sh * 0.80f;

        float top    = cy - sh * 0.56f;
        float bottom = cy + sh * 0.44f;
        float left   = cx - sw * 0.5f;
        float right  = cx + sw * 0.5f;

        shieldPath.reset();
        shieldPath.moveTo(cx, top);
        shieldPath.lineTo(right, top + sh * 0.17f);
        shieldPath.lineTo(right, top + sh * 0.57f);
        shieldPath.quadTo(right, bottom, cx, bottom);
        shieldPath.quadTo(left,  bottom, left, top + sh * 0.57f);
        shieldPath.lineTo(left,  top + sh * 0.17f);
        shieldPath.close();

        // Glow behind shield (drawn first)
        float glowR = sh * 0.7f;
        if (glowR > 0f) {
            glowPaint.setShader(new RadialGradient(cx, cy, glowR,
                    new int[]{applyAlpha(CYAN_BRIGHT, 0.38f * scale), applyAlpha(CYAN_DARK, 0.1f), Color.TRANSPARENT},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, glowR, glowPaint);
        }

        // Left half fill
        shieldPaint.setShader(new LinearGradient(left, top, cx, bottom,
                SHIELD_L, CYAN_DARK, Shader.TileMode.CLAMP));
        canvas.save(); canvas.clipRect(left - 1, top - 1, cx + 1, bottom + sh * 0.1f + 1);
        canvas.drawPath(shieldPath, shieldPaint); canvas.restore();

        // Right half fill
        shieldPaint.setShader(new LinearGradient(cx, top, right, bottom,
                CYAN_MID, SHIELD_R, Shader.TileMode.CLAMP));
        canvas.save(); canvas.clipRect(cx - 1, top - 1, right + 1, bottom + sh * 0.1f + 1);
        canvas.drawPath(shieldPath, shieldPaint); canvas.restore();

        // Top-left diagonal shine
        shieldPaint.setShader(new LinearGradient(left, top, cx * 0.88f, cy - sh * 0.1f,
                SHIELD_SHINE, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        shieldPaint.setAlpha(55);
        canvas.save(); canvas.clipPath(shieldPath);
        canvas.drawPath(shieldPath, shieldPaint); canvas.restore();
        shieldPaint.setAlpha(255);

        // Shield outline (thin cyan rim)
        shieldPaint.setShader(null);
        shieldPaint.setStyle(Paint.Style.STROKE);
        shieldPaint.setStrokeWidth(ringR * 0.014f);
        shieldPaint.setColor(applyAlpha(CYAN_BRIGHT, 0.55f * scale));
        canvas.drawPath(shieldPath, shieldPaint);
        shieldPaint.setStyle(Paint.Style.FILL);
    }

    // ── Scanline ─────────────────────────────────────────────────────────────

    private void drawScanline(Canvas canvas, long ms) {
        float t = clamp01((float)(ms - SCAN_START_MS) / (SCAN_END_MS - SCAN_START_MS));
        // Scan from top of shield to bottom
        float sh     = shieldH;   // use full size
        float top    = cy - sh * 0.56f;
        float bottom = cy + sh * 0.44f;
        float scanY  = top + (bottom - top) * t;

        float lineH  = sh * 0.06f;
        float alpha  = (float) Math.sin(t * Math.PI) * 0.55f;  // fade in + out

        // Clip to shield shape so line doesn't bleed outside
        canvas.save();
        canvas.clipPath(shieldPath);

        // Horizontal scan band
        scanPaint.setShader(new LinearGradient(cx - shieldH, scanY - lineH,
                cx + shieldH, scanY + lineH,
                new int[]{Color.TRANSPARENT, applyAlpha(CYAN_BRIGHT, alpha),
                        applyAlpha(WHITE, alpha * 0.6f),
                        applyAlpha(CYAN_BRIGHT, alpha), Color.TRANSPARENT},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(cx - shieldH, scanY - lineH, cx + shieldH, scanY + lineH, scanPaint);
        canvas.restore();
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private void drawText(Canvas canvas, long ms) {
        float t     = clamp01((float)(ms - TEXT_START_MS) / (TEXT_END_MS - TEXT_START_MS));
        float alpha = easeOut(t);

        String full   = "Suraksha AI";
        // Typewriter: reveal one character at a time
        int charsVisible = Math.min(full.length(), (int)(t * (full.length() + 2)));
        String visible   = full.substring(0, Math.min(charsVisible, full.length()));

        float slideY = (1f - alpha) * getHeight() * 0.035f;
        float textY  = cy + ringR + shieldH * 0.18f + slideY + textPaint.getTextSize() * 1.05f;

        // Glow layer
        textGlowPaint.setColor(applyAlpha(CYAN_BRIGHT, alpha * 0.35f));
        textGlowPaint.setTextSize(textPaint.getTextSize());
        canvas.drawText(visible, cx, textY + 2f, textGlowPaint);

        // Main text with top-to-bottom gradient
        textPaint.setShader(new LinearGradient(
                cx, textY - textPaint.getTextSize(),
                cx, textY,
                WHITE, CYAN_MID, Shader.TileMode.CLAMP));
        textPaint.setAlpha((int)(alpha * 255));
        canvas.drawText(visible, cx, textY, textPaint);
        textPaint.setAlpha(255);

        // Blinking cursor — blink once every 500 ms, stops at full reveal
        if (charsVisible <= full.length()) {
            boolean cursorOn = ((ms / 420) % 2) == 0;
            if (cursorOn) {
                float cursorX = cx + textPaint.measureText(visible) * 0.5f + ringR * 0.025f;
                float cursorTop    = textY - textPaint.getTextSize() * 0.82f;
                float cursorBottom = textY + textPaint.getTextSize() * 0.08f;
                cursorPaint.setColor(applyAlpha(CYAN_BRIGHT, alpha * 0.9f));
                canvas.drawRect(cursorX, cursorTop,
                        cursorX + ringR * 0.018f, cursorBottom, cursorPaint);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** 0 → 1 → 0 sine bell for the burst window. */
    private float burstFraction(long ms) {
        if (ms < BURST_START_MS || ms > BURST_END_MS) return 0f;
        float t = (float)(ms - BURST_START_MS) / (BURST_END_MS - BURST_START_MS);
        return (float) Math.sin(t * Math.PI);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static float easeOut(float t) { return 1f - (1f - t) * (1f - t); }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, tm1 = t - 1f;
        return 1f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1;
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, (int)(alpha * 255)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}