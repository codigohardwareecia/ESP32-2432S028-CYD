using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace esp32_cyd_desktop
{
    public partial class VuMeterControl : UserControl
    {
        // Estado visual interpolado (Current)
        private float[] currentBands = new float[8];
        private float[] peakBands = new float[8];
        private float currentLeft = 0;
        private float currentRight = 0;
        private float currentMic = 0;
        private float currentNeedle = 0;

        // Alvos recebidos (Target)
        private int[] targetBands = new int[8];
        private float targetLeft = 0;
        private float targetRight = 0;
        private float targetMic = 0;

        // Fator de amplificação idêntico ao ESP32
        private const float AUDIO_BOOST = 1.5f;

        public VuMeterControl()
        {
            InitializeComponent();

            this.SetStyle(ControlStyles.AllPaintingInWmPaint |
                           ControlStyles.UserPaint |
                           ControlStyles.DoubleBuffer |
                           ControlStyles.OptimizedDoubleBuffer |
                           ControlStyles.ResizeRedraw, true);
            this.UpdateStyles();
            this.BackColor = Color.FromArgb(10, 15, 10);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            this.Invalidate();
        }

        /// <summary>
        /// Atualiza os alvos (Targets) e calcula a física de movimento EXATAMENTE como o ESP32.
        /// </summary>
        public void UpdateValues(int[] newBands, float newMic)
        {
            // 1. DADOS DE ENTRADA E CURVAS (EQUIVALENTE AO ESP32 processJsonData)
            if (newBands != null && newBands.Length == 8)
            {
                int sumL = 0, sumR = 0;
                for (int i = 0; i < 8; i++)
                {
                    float norm = Math.Min(1.0f, Math.Max(0.0f, newBands[i] / 100.0f));
                    float boost = (float)Math.Pow(norm, 0.4f) * 100.0f * AUDIO_BOOST;
                    this.targetBands[i] = Math.Min(100, (int)boost);

                    if (i < 4) sumL += this.targetBands[i];
                    else sumR += this.targetBands[i];
                }

                this.targetLeft = Math.Min(100, (int)((sumL / 4.0f) * 1.8f));
                this.targetRight = Math.Min(100, (int)((sumR / 4.0f) * 2.2f));
            }
            else
            {
                float normM = Math.Min(1.0f, Math.Max(0.0f, newMic / 100.0f));
                this.targetLeft = Math.Min(100, (int)((float)Math.Pow(normM, 0.35f) * 100.0f * 1.5f));
                this.targetRight = Math.Min(100, (int)((float)Math.Pow(normM, 0.35f) * 100.0f * 2.2f));

                for (int i = 0; i < 4; i++) this.targetBands[i] = (int)this.targetLeft;
                for (int i = 4; i < 8; i++) this.targetBands[i] = (int)this.targetRight;
            }

            float normMicRaw = Math.Min(1.0f, Math.Max(0.0f, newMic / 100.0f));
            this.targetMic = Math.Min(100, (int)((float)Math.Pow(normMicRaw, 0.4f) * 100.0f * 1.6f));

            // 2. FÍSICA E INTERPOLAÇÃO (EQUIVALENTE AO ESP32 renderFrame)
            float targetMax = Math.Max(this.targetLeft, this.targetRight);

            // Agulha: Ataque instantâneo (0.85) e Decaimento suave (0.15)
            if (targetMax > this.currentNeedle)
                this.currentNeedle += (targetMax - this.currentNeedle) * 0.85f;
            else
                this.currentNeedle += (targetMax - this.currentNeedle) * 0.15f;

            // Canais Digitais e Barras (Suavização de 0.50f)
            this.currentLeft += (this.targetLeft - this.currentLeft) * 0.50f;
            this.currentRight += (this.targetRight - this.currentRight) * 0.50f;
            this.currentMic += (this.targetMic - this.currentMic) * 0.50f;

            for (int i = 0; i < 8; i++)
            {
                this.currentBands[i] += (this.targetBands[i] - this.currentBands[i]) * 0.50f;

                // Gestão de Picos das Barras Verticais
                if (this.currentBands[i] > this.peakBands[i])
                    this.peakBands[i] = this.currentBands[i];
                else
                {
                    this.peakBands[i] -= 2.0f;
                    if (this.peakBands[i] < 0) this.peakBands[i] = 0;
                }
            }

            this.Invalidate(); // Força pintura imediata
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

            int w = this.ClientSize.Width;
            int h = this.ClientSize.Height;

            if (w <= 10 || h <= 10) return;

            // --- 1. CABEÇALHO / STATUS (L E R DIGITAL) ---
            using (Font fontStatus = new Font("Segoe UI", Math.Max(8f, h * 0.03f), FontStyle.Bold))
            {
                StringFormat rightAlign = new StringFormat { Alignment = StringAlignment.Far };
                int textX = w - 15;
                g.DrawString($"L:{(int)this.currentLeft,3}%", fontStatus, Brushes.LawnGreen, textX, 8, rightAlign);
                g.DrawString($"R:{(int)this.currentRight,3}%", fontStatus, Brushes.DeepSkyBlue, textX, 8 + (int)(h * 0.04f), rightAlign);
            }

            // --- 2. MEDIDOR ANALÓGICO (VU 210° a 330°) ---
            Point arcCenter = new Point(w / 2, (int)(h * 0.55f));

            int maxRadiusWidth = (w / 2) - 40;
            int maxRadiusHeight = (int)(h * 0.38f);
            int arcRadius = Math.Max(20, Math.Min(maxRadiusWidth, maxRadiusHeight));

            float startAngleDeg = -150f;
            float totalSweepDeg = 120f;

            for (int i = 0; i <= 100; i += 2)
            {
                float angle = startAngleDeg + (i * totalSweepDeg / 100f);
                float angleRad = (float)(angle * Math.PI / 180f);

                Point pStart = new Point(
                    arcCenter.X + (int)(arcRadius * Math.Cos(angleRad)),
                    arcCenter.Y + (int)(arcRadius * Math.Sin(angleRad))
                );

                int tickLen = (i % 10 == 0) ? Math.Max(6, (int)(arcRadius * 0.08f)) : Math.Max(3, (int)(arcRadius * 0.04f));
                Point pEnd = new Point(
                    arcCenter.X + (int)((arcRadius - tickLen) * Math.Cos(angleRad)),
                    arcCenter.Y + (int)((arcRadius - tickLen) * Math.Sin(angleRad))
                );

                Color tickColor;
                if (i < 50) tickColor = Color.LawnGreen;
                else if (i < 80) tickColor = Color.Yellow;
                else tickColor = Color.Red;

                using (Pen penTick = new Pen(tickColor, (i % 10 == 0) ? 2f : 1f))
                {
                    g.DrawLine(penTick, pStart, pEnd);
                }

                if (i == 0 || i == 30 || i == 50 || i == 70 || i == 100)
                {
                    using (Font fontTick = new Font("Segoe UI", Math.Max(7f, arcRadius * 0.075f), FontStyle.Bold))
                    {
                        int textDistance = arcRadius - (tickLen + 12);
                        Point pText = new Point(
                            arcCenter.X + (int)(textDistance * Math.Cos(angleRad)),
                            arcCenter.Y + (int)(textDistance * Math.Sin(angleRad))
                        );
                        StringFormat centerFormat = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                        g.DrawString(i.ToString(), fontTick, new SolidBrush(tickColor), pText, centerFormat);
                    }
                }
            }

            // Agulha / Ponteiro
            float pointerAngle = startAngleDeg + ((this.currentNeedle / 100.0f) * totalSweepDeg);
            float pointerAngleRad = (float)(pointerAngle * Math.PI / 180f);
            Point pointerEnd = new Point(
                arcCenter.X + (int)((arcRadius - 5) * Math.Cos(pointerAngleRad)),
                arcCenter.Y + (int)((arcRadius - 5) * Math.Sin(pointerAngleRad))
            );

            using (Pen penPointer = new Pen(Color.Red, Math.Max(2f, arcRadius * 0.02f)))
            {
                g.DrawLine(penPointer, arcCenter, pointerEnd);
            }

            // Pivot Central
            int pivotSize = Math.Max(6, (int)(arcRadius * 0.08f));
            g.FillEllipse(Brushes.White, arcCenter.X - pivotSize / 2, arcCenter.Y - pivotSize / 2, pivotSize, pivotSize);
            g.DrawEllipse(Pens.Black, arcCenter.X - pivotSize / 2, arcCenter.Y - pivotSize / 2, pivotSize, pivotSize);

            // --- 3. EQUALIZADOR SPECTRUM (8 BARRAS COM RETENÇÃO DE PICO) ---
            int eqY = arcCenter.Y + (pivotSize / 2) + 10;
            int eqWidth = (int)(w * 0.65f);
            int eqHeight = Math.Max(15, (int)(h * 0.10f));
            int barWidth = Math.Max(2, (eqWidth / 8) - 3);
            int startX = w / 2 - (8 * (barWidth + 3)) / 2;

            for (int i = 0; i < 8; i++)
            {
                int x = startX + i * (barWidth + 3);
                float val = this.currentBands[i];
                float peakVal = this.peakBands[i];

                int currentBarHeight = (int)((val / 100.0f) * eqHeight);
                int peakBarHeight = (int)((peakVal / 100.0f) * eqHeight);

                int y = eqY + (eqHeight - currentBarHeight);
                int peakY = eqY + (eqHeight - peakBarHeight);

                // Desenha a Barra Principal
                if (currentBarHeight > 0)
                {
                    Rectangle barRect = new Rectangle(x, y, barWidth, currentBarHeight);
                    Color barColor = Color.LawnGreen;
                    if (val > 60) barColor = Color.Yellow;
                    if (val > 85) barColor = Color.Red;

                    using (SolidBrush brushBar = new SolidBrush(barColor))
                    {
                        g.FillRectangle(brushBar, barRect);
                    }
                }

                // Desenha o Marcador de Pico (Peak Hold)
                if (peakBarHeight > 2)
                {
                    g.FillRectangle(Brushes.White, x, peakY, barWidth, 2);
                }

                using (Pen borderPen = new Pen(Color.FromArgb(35, Color.LawnGreen)))
                {
                    g.DrawRectangle(borderPen, x, eqY, barWidth, eqHeight);
                }
            }

            // --- 4. BARRAS HORIZONTAIS INFERIORES (L, R, M) ---
            int barH_Y = eqY + eqHeight + 12;
            int barH_Width = Math.Max(50, w - 60);
            int barH_Height = Math.Max(4, (int)(h * 0.02f));
            int spacing = barH_Height + 4;

            DrawHorizontalBar(g, "L", (int)this.currentLeft, new Point(35, barH_Y), barH_Width, barH_Height, Color.LawnGreen, Color.Red);
            DrawHorizontalBar(g, "R", (int)this.currentRight, new Point(35, barH_Y + spacing), barH_Width, barH_Height, Color.DeepSkyBlue, Color.Yellow);
            DrawHorizontalBar(g, "M", (int)this.currentMic, new Point(35, barH_Y + (spacing * 2)), barH_Width, barH_Height, Color.Yellow, Color.Red);
        }

        private void DrawHorizontalBar(Graphics g, string label, int value, Point location, int width, int height, Color startColor, Color endColor)
        {
            value = Math.Min(100, Math.Max(0, value));

            using (Font fontLabel = new Font("Segoe UI", Math.Max(7.5f, height * 0.85f), FontStyle.Bold))
            {
                g.DrawString(label, fontLabel, new SolidBrush(startColor), location.X - 22, location.Y - 2);
            }

            int numSegments = 26; // Identico ao ESP32
            int segmentWidth = Math.Max(1, (width / numSegments) - 1);

            for (int i = 0; i < numSegments; i++)
            {
                int segX = location.X + i * (segmentWidth + 1);

                Color segColor;
                float pos = (float)i / numSegments;
                if (pos < 0.69f) segColor = startColor;
                else if (pos < 0.88f) segColor = Color.Yellow;
                else segColor = endColor;

                if (i > (value * numSegments / 100))
                {
                    segColor = Color.FromArgb(20, segColor);
                }

                Rectangle segRect = new Rectangle(segX, location.Y, segmentWidth, height);
                using (SolidBrush segBrush = new SolidBrush(segColor))
                {
                    g.FillRectangle(segBrush, segRect);
                }
            }
        }
    }
}