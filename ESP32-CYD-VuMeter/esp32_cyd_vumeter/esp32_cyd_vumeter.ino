#include <BluetoothSerial.h>
#include <ArduinoJson.h>
#include <TFT_eSPI.h>

// =========================================================================
// CONFIGURAÇÃO DO BLUETOOTH SERIAL
// =========================================================================
BluetoothSerial SerialBT;
const char* btDeviceName = "ESP32-VU-Meter";
bool btConnected = false; // Controle do estado de conexão

// =========================================================================
// DISPLAY E CORES
// =========================================================================
TFT_eSPI tft = TFT_eSPI();
TFT_eSprite mainSpr = TFT_eSprite(&tft);

#define COLOR_BG          TFT_BLACK    
#define COLOR_SCALE_GREEN 0x07E0       
#define COLOR_SCALE_RED   0xF800       
#define COLOR_NEEDLE      0xF800       
#define COLOR_PIVOT       0xD69A       
#define COLOR_SEG_OFF     0x1823       

// Variáveis Globais
volatile int targetLeft  = 0;
volatile int targetRight = 0;
volatile int targetMic   = 0;
volatile int targetBands[8] = {0};

float currentNeedle = 0.0;
float currentLeft   = 0.0;
float currentRight  = 0.0;
float currentMic    = 0.0;
float currentBands[8] = {0.0};
float peakBands[8]    = {0.0};

// Dimensões
const int SPR_W = 310;
const int SPR_H = 165;
const int SPR_CX = 155; 
const int SPR_CY = 150; 
const int NEEDLE_LENGTH = 110; 

// Framerate (60 FPS)
unsigned long lastFrameTime = 0;
const int FRAME_DELAY = 1000 / 60;

// Buffers Estáticos (Evita Heap Fragmentation)
#define MAX_JSON_LEN 256
char btBuffer[MAX_JSON_LEN];
int btIndex = 0;

char serialBuffer[MAX_JSON_LEN];
int serialIndex = 0;

// Protótipos
void drawDarkDialUI();
void renderFrame();
void drawSpectrumBars();
void drawEmbeddedBar(int x, int y, int totalW, int h, float percent, uint16_t activeColor);
void processJsonData(const char* jsonString);
void resetTargets();

// =========================================================================
// SETUP
// =========================================================================
void setup() {
  Serial.begin(115200);

  tft.init();
  tft.setRotation(1); // Paisagem (320x240)
  tft.fillScreen(COLOR_BG);

  mainSpr.createSprite(SPR_W, SPR_H);
  SerialBT.begin(btDeviceName);

  drawDarkDialUI();
  lastFrameTime = millis();
}

// =========================================================================
// LOOP PRINCIPAL (COM TRATAMENTO DE RECONEXÃO AUTOMÁTICA)
// =========================================================================
void loop() {
  // --- GERENCIAMENTO DE ESTADO DA CONEXÃO BLUETOOTH ---
  bool isNowConnected = SerialBT.hasClient();

  // Se perdeu a conexão
  if (!isNowConnected && btConnected) {
    btConnected = false;
    btIndex = 0;       // Limpa o buffer residual
    resetTargets();    // Zera os ponteiros e agulhas suavemente
  } 
  // Se reconectou
  else if (isNowConnected && !btConnected) {
    btConnected = true;
    btIndex = 0;       // Garante buffer limpo para o novo fluxo
  }

  // 1. Leitura não-bloqueante do Bluetooth
  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\n') {
      btBuffer[btIndex] = '\0';
      if (btIndex > 0) processJsonData(btBuffer);
      btIndex = 0;
    } else if (c != '\r' && btIndex < MAX_JSON_LEN - 1) {
      btBuffer[btIndex++] = c;
    }
  }

  // 2. Leitura não-bloqueante da Serial USB
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      serialBuffer[serialIndex] = '\0';
      if (serialIndex > 0) processJsonData(serialBuffer);
      serialIndex = 0;
    } else if (c != '\r' && serialIndex < MAX_JSON_LEN - 1) {
      serialBuffer[serialIndex++] = c;
    }
  }

  // 3. Renderização a 60 FPS
  unsigned long now = millis();
  if (now - lastFrameTime >= FRAME_DELAY) {
    lastFrameTime = now;
    renderFrame();
  }
}

// Zera os valores visuais quando o Bluetooth desconecta
void resetTargets() {
  targetLeft = 0;
  targetRight = 0;
  targetMic = 0;
  for (int i = 0; i < 8; i++) {
    targetBands[i] = 0;
  }
}

// =========================================================================
// PROCESSA DADOS DO JSON
// =========================================================================
void processJsonData(const char* jsonString) {
  StaticJsonDocument<256> doc;
  DeserializationError error = deserializeJson(doc, jsonString);

  if (!error) {
    int tempLeft = 0;
    int tempRight = 0;

    // 1. MODO EQUALIZADOR FFT (C#)
    if (doc.containsKey("b")) {
      JsonArray b = doc["b"];
      if (!b.isNull() && b.size() >= 8) {
        int sumL = 0, sumR = 0;
        for (int i = 0; i < 8; i++) {
          int val = b[i] | 0;
          
          float norm = val / 100.0f;
          float boost = pow(norm, 0.4f) * 100.0f * 1.5f;
          targetBands[i] = min(100, (int)boost);

          if (i < 4) sumL += targetBands[i];
          else sumR += targetBands[i];
        }

        tempLeft  = min(100, (int)((sumL / 4.0f) * 1.8f));
        tempRight = min(100, (int)((sumR / 4.0f) * 2.2f));
      }
    } 
    // 2. MODO CLÁSSICO (Android ou C# Simples)
    else {
      int rawL = doc["l"] | 0;
      int rawR = doc["r"] | 0;

      float normL = rawL / 100.0f;
      float normR = rawR / 100.0f;

      tempLeft  = min(100, (int)(pow(normL, 0.35f) * 100.0f * 1.5f));
      tempRight = min(100, (int)(pow(normR, 0.35f) * 100.0f * 2.2f)); 

      for (int i = 0; i < 4; i++) targetBands[i] = tempLeft;
      for (int i = 4; i < 8; i++) targetBands[i] = tempRight;
    }

    // Microfone
    int rawM = doc["m"] | 0;
    float normM = rawM / 100.0f;

    // Atualização atômica das globais
    targetLeft = tempLeft;
    targetRight = tempRight;
    targetMic = min(100, (int)(pow(normM, 0.4f) * 100.0f * 1.6f));
  }
}

// =========================================================================
// INTERFACE DE FUNDO FIXA (MOSTRADORES)
// =========================================================================
void drawDarkDialUI() {
  tft.fillScreen(COLOR_BG);

  tft.drawRoundRect(3, 3, 314, 234, 10, 0x52AA);
  tft.drawRoundRect(4, 4, 312, 232, 9, 0x2104);

  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(0x4208, COLOR_BG); 
  tft.drawString("L", 18, 187, 2);
  tft.drawString("R", 18, 202, 2);
  tft.drawString("M", 18, 217, 2);
}

// =========================================================================
// RENDERIZAÇÃO UNIFICADA (60 FPS)
// =========================================================================
void renderFrame() {
  mainSpr.fillSprite(COLOR_BG);

  // Pega o maior canal para movimentar a agulha com ataque instantâneo
  int targetMax = max(targetLeft, targetRight);
  if (targetMax > currentNeedle) currentNeedle += (targetMax - currentNeedle) * 0.85f;
  else currentNeedle += (targetMax - currentNeedle) * 0.15f;

  currentLeft   += (targetLeft   - currentLeft)   * 0.50f;
  currentRight  += (targetRight  - currentRight)  * 0.50f;
  currentMic    += (targetMic    - currentMic)    * 0.50f;

  // 1. DESENHA O EQUALIZADOR SPECTRUM NO FUNDO
  drawSpectrumBars();

  // 2. ESCALA DO VU
  mainSpr.setTextDatum(MC_DATUM);
  mainSpr.setTextColor(0x7BE0, COLOR_BG); 
  mainSpr.drawString("0", SPR_CX - 120, SPR_CY - 70, 2);
  mainSpr.drawString("30", SPR_CX - 30,  SPR_CY - 115, 2);
  mainSpr.drawString("50", SPR_CX + 30,  SPR_CY - 115, 2);

  mainSpr.setTextColor(COLOR_SCALE_RED, COLOR_BG);
  mainSpr.drawString("70", SPR_CX + 100, SPR_CY - 85, 2);

  for (int a = 210; a <= 330; a += 5) {
    float rRad = a * DEG_TO_RAD;
    bool isMajor = (a % 15 == 0 || a == 300);
    int tickLen = isMajor ? 12 : 6;

    int x1 = SPR_CX + NEEDLE_LENGTH * cos(rRad);
    int y1 = SPR_CY + NEEDLE_LENGTH * sin(rRad);
    int x2 = SPR_CX + (NEEDLE_LENGTH - tickLen) * cos(rRad);
    int y2 = SPR_CY + (NEEDLE_LENGTH - tickLen) * sin(rRad);

    uint16_t tickColor = (a >= 300) ? COLOR_SCALE_RED : COLOR_SCALE_GREEN;
    mainSpr.drawLine(x1, y1, x2, y2, tickColor);
  }

  // 3. AGULHA
  float norm = currentNeedle / 100.0f;
  if (norm < 0.0f) norm = 0.0f;
  if (norm > 1.0f) norm = 1.0f;

  float angle = 210.0f + norm * 120.0f;
  float rad = angle * DEG_TO_RAD;

  int xEnd = SPR_CX + (int)(NEEDLE_LENGTH * cos(rad));
  int yEnd = SPR_CY + (int)(NEEDLE_LENGTH * sin(rad));

  mainSpr.drawLine(SPR_CX, SPR_CY, xEnd, yEnd, COLOR_NEEDLE);
  mainSpr.drawLine(SPR_CX - 1, SPR_CY, xEnd - 1, yEnd, COLOR_NEEDLE);

  mainSpr.fillCircle(SPR_CX, SPR_CY, 8, COLOR_PIVOT);
  mainSpr.fillCircle(SPR_CX, SPR_CY, 3, TFT_BLACK);

  // READOUT DIGITAL (CANAL L E CANAL R)
  mainSpr.setTextDatum(TR_DATUM);
  char bufL[10], bufR[10];
  snprintf(bufL, sizeof(bufL), "L:%3d%%", (int)currentLeft);
  snprintf(bufR, sizeof(bufR), "R:%3d%%", (int)currentRight);

  mainSpr.setTextColor(TFT_GREEN, COLOR_BG);
  mainSpr.drawString(bufL, SPR_W - 10, 8, 2);
  mainSpr.setTextColor(TFT_CYAN, COLOR_BG);
  mainSpr.drawString(bufR, SPR_W - 10, 24, 2);

  // Transferência sem tremor para a tela
  mainSpr.pushSprite(5, 5);

  // 4. BARRAS HORIZONTAIS INFERIORES (L, R e M)
  drawEmbeddedBar(28, 183, 280, 8, currentLeft / 100.0f, TFT_GREEN);
  drawEmbeddedBar(28, 198, 280, 8, currentRight / 100.0f, TFT_CYAN);
  drawEmbeddedBar(28, 213, 280, 8, currentMic / 100.0f, TFT_YELLOW);
}

// =========================================================================
// EQUALIZADOR SPECTRUM (8 BARRAS VERTICAIS)
// =========================================================================
void drawSpectrumBars() {
  const int numBands = 8;
  const int barWidth = 14;
  const int gap = 16;
  const int startX = 38;
  const int baseY = 140; 
  const int maxBarHeight = 45; 

  for (int i = 0; i < numBands; i++) {
    currentBands[i] += (targetBands[i] - currentBands[i]) * 0.50f;

    if (currentBands[i] > peakBands[i]) peakBands[i] = currentBands[i];
    else {
      peakBands[i] -= 2.0f;
      if (peakBands[i] < 0) peakBands[i] = 0;
    }

    int h = (int)((currentBands[i] / 100.0f) * maxBarHeight);
    int peakH = (int)((peakBands[i] / 100.0f) * maxBarHeight);
    int x = startX + i * (barWidth + gap);

    if (h > 0) {
      uint16_t color = TFT_GREEN;
      if (h > 25) color = TFT_YELLOW;
      if (h > 38) color = TFT_RED;
      mainSpr.fillRect(x, baseY - h, barWidth, h, color);
    }

    if (peakH > 2) {
      mainSpr.fillRect(x, baseY - peakH, barWidth, 2, TFT_WHITE);
    }
  }
}

// =========================================================================
// BARRAS HORIZONTAIS DE SEGMENTO (CANAL L, R, M)
// =========================================================================
void drawEmbeddedBar(int x, int y, int totalW, int h, float percent, uint16_t activeColor) {
  const int totalSegments = 26; 
  int segWidth = (totalW / totalSegments) - 2; 
  int activeSegments = (int)(percent * totalSegments);

  for (int i = 0; i < totalSegments; i++) {
    int segX = x + i * (segWidth + 2);
    
    uint16_t color = activeColor;
    if (i >= 18) color = TFT_YELLOW;
    if (i >= 23) color = TFT_RED;

    if (i < activeSegments) {
      tft.fillRect(segX, y, segWidth, h, color);
    } else {
      tft.fillRect(segX, y, segWidth, h, COLOR_SEG_OFF);
    }
  }
}