package com.seunome.esp32vumeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class VuMeterView extends View {

    // Valores atuais e alvos (Interpolação)
    private float targetLeft = 0f;
    private float targetRight = 0f;
    private float targetMic = 0f;
    private final float[] targetBands = new float[8];

    private float currentLeft = 0f;
    private float currentRight = 0f;
    private float currentMic = 0f;
    private float currentNeedle = 0f;
    private final float[] currentBands = new float[8];
    private final float[] peakBands = new float[8];

    // Tintas para renderização
    private final Paint paintLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Ganho idêntico ao ESP32/C#
    private final float audioBoost = 1.5f;

    public VuMeterView(Context context) {
        super(context);
    }

    public VuMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VuMeterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Atualiza os valores e agenda a nova renderização de forma segura.     */
    public void updateValues(int[] bands, float mic) {
        if (bands != null && bands.length == 8) {
            float sumL = 0f;
            float sumR = 0f;
            for (int i = 0; i < 8; i++) {
                float norm = Math.max(0f, Math.min(1f, bands[i] / 100f));
                float boost = (float) Math.pow(norm, 0.4f) * 100f * audioBoost;
                targetBands[i] = Math.min(100f, boost);

                if (i < 4) sumL += targetBands[i];
                else sumR += targetBands[i];
            }
            targetLeft = Math.min(100f, (sumL / 4f) * 1.8f);
            targetRight = Math.min(100f, (sumR / 4f) * 2.2f);
        } else {
            float normM = Math.max(0f, Math.min(1f, mic / 100f));
            targetLeft = Math.min(100f, (float) Math.pow(normM, 0.35f) * 100f * 1.5f);
            targetRight = Math.min(100f, (float) Math.pow(normM, 0.35f) * 100f * 2.2f);

            for (int i = 0; i < 4; i++) targetBands[i] = targetLeft;
            for (int i = 4; i < 8; i++) targetBands[i] = targetRight;
        }

        float normMicRaw = Math.min(1.0f, Math.max(0.0f, mic / 100.0f));
        targetMic = Math.min(100f, (float) Math.pow(normMicRaw, 0.4f) * 100f * 1.6f);

        // Dispara o redesenho apenas uma vez por pacote de dados recebido
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 10 || h <= 10) return;

        // 1. CALCULOS DA FÍSICA
        float targetMax = Math.max(targetLeft, targetRight);
        if (targetMax > currentNeedle) {
            currentNeedle += (targetMax - currentNeedle) * 0.85f;
        } else {
            currentNeedle += (targetMax - currentNeedle) * 0.15f;
        }

        currentLeft += (targetLeft - currentLeft) * 0.50f;
        currentRight += (targetRight - currentRight) * 0.50f;
        currentMic += (targetMic - currentMic) * 0.50f;

        for (int i = 0; i < 8; i++) {
            currentBands[i] += (targetBands[i] - currentBands[i]) * 0.50f;
            if (currentBands[i] > peakBands[i]) {
                peakBands[i] = currentBands[i];
            } else {
                peakBands[i] = Math.max(0f, peakBands[i] - 2.0f);
            }
        }

        // 2. DESENHAR TEXTO DE STATUS
        paintText.setTextSize(Math.max(24f, h * 0.035f));
        paintText.setFakeBoldText(true);
        paintText.setTextAlign(Paint.Align.RIGHT);

        paintText.setColor(Color.GREEN);
        canvas.drawText("L: " + (int) currentLeft + "%", w - 20f, 40f, paintText);

        paintText.setColor(Color.CYAN);
        canvas.drawText("R: " + (int) currentRight + "%", w - 20f, 85f, paintText);

        // 3. DESENHAR MEDIDOR ANALÓGICO
        float cx = w / 2f;
        float cy = h * 0.52f;
        float maxRadiusW = (w / 2f) - 40f;
        float maxRadiusH = h * 0.38f;
        float radius = Math.max(20f, Math.min(maxRadiusW, maxRadiusH));

        float startAngleDeg = -150f;
        float totalSweepDeg = 120f;

        for (int i = 0; i <= 100; i += 2) {
            float angle = startAngleDeg + (i * totalSweepDeg / 100f);
            double rad = Math.toRadians(angle);

            float x1 = cx + (float) (radius * Math.cos(rad));
            float y1 = cy + (float) (radius * Math.sin(rad));

            float tickLen = (i % 10 == 0) ? Math.max(12f, radius * 0.08f) : Math.max(6f, radius * 0.04f);
            float x2 = cx + (float) ((radius - tickLen) * Math.cos(rad));
            float y2 = cy + (float) ((radius - tickLen) * Math.sin(rad));

            int tickColor;
            if (i < 50) tickColor = Color.GREEN;
            else if (i < 80) tickColor = Color.YELLOW;
            else tickColor = Color.RED;

            paintLine.setColor(tickColor);
            paintLine.setStrokeWidth((i % 10 == 0) ? 4f : 2f);
            canvas.drawLine(x1, y1, x2, y2, paintLine);

            if (i == 0 || i == 30 || i == 50 || i == 70 || i == 100) {
                paintText.setTextSize(Math.max(20f, radius * 0.08f));
                paintText.setColor(tickColor);
                paintText.setTextAlign(Paint.Align.CENTER);
                float textDist = radius - (tickLen + 20f);
                float tx = cx + (float) (textDist * Math.cos(rad));
                float ty = cy + (float) (textDist * Math.sin(rad));
                canvas.drawText(String.valueOf(i), tx, ty, paintText);
            }
        }

        // Agulha
        float pointerAngle = startAngleDeg + ((currentNeedle / 100f) * totalSweepDeg);
        double pointerRad = Math.toRadians(pointerAngle);
        float px = cx + (float) ((radius - 10f) * Math.cos(pointerRad));
        float py = cy + (float) ((radius - 10f) * Math.sin(pointerRad));

        paintLine.setColor(Color.RED);
        paintLine.setStrokeWidth(Math.max(5f, radius * 0.025f));
        canvas.drawLine(cx, cy, px, py, paintLine);

        // Pivot Central
        float pivotSize = Math.max(12f, radius * 0.08f);
        paintFill.setColor(Color.WHITE);
        paintFill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, pivotSize / 2f, paintFill);

        // 4. EQUALIZADOR SPECTRUM (8 BARRAS)
        float eqY = cy + (pivotSize / 2f) + 15f;
        float eqW = w * 0.65f;
        float eqH = Math.max(20f, h * 0.10f);
        float barW = Math.max(4f, (eqW / 8f) - 6f);
        float startX = (w / 2f) - ((8 * (barW + 6f)) / 2f);

        for (int i = 0; i < 8; i++) {
            float x = startX + i * (barW + 6f);
            float valB = currentBands[i];
            float peakVal = peakBands[i];

            float curBarH = (valB / 100f) * eqH;
            float peakBarH = (peakVal / 100f) * eqH;

            float barY = eqY + (eqH - curBarH);
            float peakY = eqY + (eqH - peakBarH);

            if (curBarH > 0) {
                if (valB > 85f) paintFill.setColor(Color.RED);
                else if (valB > 60f) paintFill.setColor(Color.YELLOW);
                else paintFill.setColor(Color.GREEN);

                canvas.drawRect(x, barY, x + barW, eqY + eqH, paintFill);
            }

            if (peakBarH > 2f) {
                paintFill.setColor(Color.WHITE);
                canvas.drawRect(x, peakY, x + barW, peakY + 3f, paintFill);
            }
        }

        // 5. BARRAS HORIZONTAIS INFERIORES (L, R, M)
        float barHY = eqY + eqH + 20f;
        float barHW = Math.max(100f, w - 80f);
        float barHH = Math.max(10f, h * 0.025f);
        float spacing = barHH + 10f;

        drawHorizontalSegmentedBar(canvas, "L", currentLeft, 40f, barHY, barHW, barHH, Color.GREEN, Color.RED);
        drawHorizontalSegmentedBar(canvas, "R", currentRight, 40f, barHY + spacing, barHW, barHH, Color.CYAN, Color.YELLOW);
        drawHorizontalSegmentedBar(canvas, "M", currentMic, 40f, barHY + (spacing * 2), barHW, barHH, Color.YELLOW, Color.RED);
    }

    private void drawHorizontalSegmentedBar(Canvas canvas, String label, float value, float x, float y, float width, float height, int startColor, int endColor) {
        paintText.setTextSize(Math.max(20f, height * 0.85f));
        paintText.setColor(startColor);
        paintText.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(label, x - 10f, y + height - 2f, paintText);

        int numSegments = 26;
        float segW = Math.max(2f, (width / numSegments) - 2f);
        int activeSegs = (int) ((value / 100f) * numSegments);

        for (int i = 0; i < numSegments; i++) {
            float segX = x + i * (segW + 2f);
            float pos = (float) i / numSegments;

            int color;
            if (pos >= 0.88f) color = Color.RED;
            else if (pos >= 0.69f) color = Color.YELLOW;
            else color = startColor;

            if (i < activeSegs) {
                paintFill.setColor(color);
            } else {
                paintFill.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)));
            }

            canvas.drawRect(segX, y, segX + segW, y + height, paintFill);
        }
    }
}