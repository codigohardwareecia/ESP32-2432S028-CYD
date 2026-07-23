# Configuração do ESP32-CYD
### Passo 1: Preparar a Arduino IDE

1. **Baixe e instale** a versão mais recente da [Arduino IDE](https://www.arduino.cc/en/software) se ainda não tiver.
    
2. **Adicione o suporte ao ESP32**:
    
    - Abra a IDE e vá em **File > Preferences** (Arquivo > Preferências).
        
    - No campo _Additional Boards Manager URLs_, cole o link oficial da Espressif: 
	    https://espressif.github.io/arduino-esp32/package_esp32_index.json
        
    - Clique em **OK**.
        
3. **Instale a placa**:
    
    - Vá no menu lateral em **Boards Manager** (Gerenciador de Placas) ou pressione `Ctrl + Shift + B`.
    - Digite **ESP32** e instale o pacote da _Espressif Systems_.
	- Em Tools > Boards > Esp32 selecione ESP32 Dev Module

4. **Conecte o dispositivo:**

	- Em Tools> Ports localize a porta USB onde foi conectada o dispositivo
	- Envie um codigo vazio para teste de comunicação
	- Se a comunicação ocorreu corretamente ao gravar o codigo vazio vamos para o proximo passo.

## Passo 2: Instalar as Bibliotecas

Para controlar o display TFT e o Touch, você precisará de duas bibliotecas principais:

1. Vá em **Sketch > Include Library > Manage Libraries...** (ou `Ctrl + Shift + I`) e instale:
2. **TFT_eSPI** (por Bodmer) -> Essa é a biblioteca que desenha os gráficos na tela com alta performance.
3. **ArduinoJson** (por Benoit Blanchon) -> Permite serializar dados para o formato json.
4. **XPT2046_Touchscreen** (por Paul Stoffregen) -> Essa controla o toque na tela. (Apenas para ter instalado)    

## Passo 3: O "Pulo do Gato" (Configurar a TFT_eSPI)

A biblioteca `TFT_eSPI` é genérica e serve para dezenas de telas diferentes. Para que ela saiba exatamente quais pinos a sua CYD usa, precisamos editar um arquivo de configuração interno dela.

1. No seu computador, abra a pasta **Documentos > Arduino > libraries > TFT_eSPI**.
2. Vamos criar um arquivo de texto na pasta /User_Setups chamado "Setup252_ESP32_2432S028.h" (poderia ser qualquer nome apenas para não conflitar), adicione o seguinte conteúdo a este arquivo e salve-o:

	```
	#define USER_SETUP_INFO "ESP32_CYD_2432S028"
	
	// Define o driver correto da tela da CYD
	#define ILI9341_2_DRIVER
	
	// Mapeamento real dos pinos do display na placa amarela
	#define TFT_MISO 12
	#define TFT_MOSI 13
	#define TFT_SCLK 14
	#define TFT_CS   15
	#define TFT_DC    2
	#define TFT_RST  -1
	#define TFT_BL   21
	#define TFT_BACKLIGHT_ON HIGH
	
	// Fontes que serão carregadas na memória
	#define LOAD_GLCD
	#define LOAD_FONT2
	#define LOAD_FONT4
	#define LOAD_FONT6
	#define LOAD_FONT7
	#define LOAD_FONT8
	#define LOAD_GFXFF
	
	// Velocidades barramento SPI otimizadas para o ESP32 dessa placa
	#define SPI_FREQUENCY        55000000
	#define SPI_READ_FREQUENCY   20000000
	#define SPI_TOUCH_FREQUENCY  25000000
	```


3. Na seqeucncia Vamos editar o arquivo "libraries/TFT_eSPI/User_Setup_Select.h"
4. Localiza a linha a seguir e comentea com //:

```
//#include <User_Setup.h>	
```

5. Logo abaixo cole a linha a seguir

```
 #include <User_Setups/Setup251_ESP32_Page_ILI9341.h>
```

6. Salve o arquivo User_Setup_Select.h

# ESP32-CYD  - Implementação

Copie e cole esse código no Arduino IDE

```C
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
```

# Aplicação Desktop

## Passo 1: Criando o projeto

1. Abra o Visual Studio 2022
2. Clique em "Create a new Project"
3. Como template selecione "Windows Forms App"
4. Clique em "Next"
5. Informe um nome de projeto, o diretório onde será salvo e o nome da solução
6. Clique em "Next"
7. Na tela de informações adicionais selecione em Framework ".NET 8.0 (Long Term Support)"
8. Clique em "Create"
## Passo 2: Instalando pacotes

1. Clique em Tools > Nuget Package Manager > Manage Packets for Solution...
2. Em "Browse" digite Naudio, selecione by markheath e  depois marque o projeto no checkbox ao lado e clique em "Install"
3. Depois va ser exibida uma caixa de diálodo clique em "Apply"
4. Vá novamente na aba "Browse" e digite "System.IO.Ports", selecione o primeiro by dotnetframwwork, selecione o projeto no checkbox ao lado e clique em "Install"
5. Novamente clique em "Apply"
## Passo 2: Criando a Classe de Streamer

1. Dentro da solution clique com o botão direito selecione Add > Class...
2. Informe o nome da classe "VuMeterStream.cs" e clique em Add

Copie e cole o código abaixo dentro da classe VuMeterStream, se for necessário acerte o nome do namespace logo no inicio da classe

```CSharp
using NAudio.CoreAudioApi;
using NAudio.Dsp;
using NAudio.Wave;
using System;
using System.IO.Ports;
using System.Windows.Forms;

namespace esp32_cyd_desktop
{
    public class VuMeterStream : IDisposable
    {
        private SerialPort serialPort = new SerialPort();
        private WasapiLoopbackCapture systemAudio;
        private WaveInEvent micAudio;

        // Dispositivo e volume master do Windows
        private MMDevice systemAudioDevice;
        private AudioEndpointVolumeNotificationDelegate volumeDelegate;
        private float currentMasterVolume = 1.0f;

        // Array com as 8 bandas do equalizador
        private int[] bands = new int[8];
        private float mic = 0;

        private System.Windows.Forms.Timer reconnectTimer = new System.Windows.Forms.Timer();
        private const string PORT_NAME = "COM10"; // Altere para a sua porta COM
        private const int BAUD_RATE = 115200;

        // Configurações da FFT
        private const int FFT_POINTS = 1024;
        private Complex[] fftBuffer = new Complex[FFT_POINTS];
        private int fftPos = 0;

        // Evento para notificar a interface visual (VuMeterControl)
        public event Action<int[], float> OnAudioDataUpdated;

        public VuMeterStream()
        {
            serialPort.PortName = PORT_NAME;
            serialPort.BaudRate = BAUD_RATE;
            serialPort.WriteTimeout = 500;

            BluetoothConnect();

            reconnectTimer.Interval = 2000;
            reconnectTimer.Tick += (s, a) => BluetoothConnect();
            reconnectTimer.Start();

            InicializarAudioSistemaFFT();
            InicializarMicrofone();

            // Timer de Envio/Atualização (~60 FPS)
            System.Windows.Forms.Timer sendTimer = new System.Windows.Forms.Timer();
            sendTimer.Interval = 16;
            sendTimer.Tick += (s, a) => EnviarEAtualizarData();
            sendTimer.Start();
        }

        private void BluetoothConnect()
        {
            if (serialPort.IsOpen) return;
            try
            {
                serialPort.Open();
            }
            catch
            {
                // Silencia exceção para não fechar a aplicação se o dispositivo não estiver conectado
            }
        }

        private void EnviarEAtualizarData()
        {
            // 1. Notifica o UserControl desktop para atualizar o desenho na tela
            OnAudioDataUpdated?.Invoke(bands, mic);

            // 2. Envia para o ESP32 via Serial caso a porta esteja aberta
            if (!serialPort.IsOpen) return;

            try
            {
                string bandsJson = string.Join(",", bands);
                string json = $"{{\"b\":[{bandsJson}],\"m\":{(int)mic}}}\n";
                serialPort.Write(json);
            }
            catch
            {
                FecharPortaComSeguranca();
            }
        }

        private void InicializarAudioSistemaFFT()
        {
            try
            {
                // 1. Obtém o dispositivo de saída padrão do Windows
                MMDeviceEnumerator enumerator = new MMDeviceEnumerator();
                systemAudioDevice = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);

                // 2. Lê o volume inicial e estado de Mute
                AtualizarVolumeMaster();

                // 3. Cadastra o evento para atualizar o volume dinamicamente apenas quando o usuário alterar
                volumeDelegate = (data) =>
                {
                    currentMasterVolume = data.Muted ? 0.0f : data.MasterVolume;
                };
                systemAudioDevice.AudioEndpointVolume.OnVolumeNotification += volumeDelegate;

                systemAudio = new WasapiLoopbackCapture();
                systemAudio.DataAvailable += (s, a) =>
                {
                    int bytesPerSample = systemAudio.WaveFormat.BitsPerSample / 8;

                    for (int i = 0; i < a.BytesRecorded; i += bytesPerSample * 2)
                    {
                        float rawSample = BitConverter.ToSingle(a.Buffer, i);

                        // Aplica o volume master do sistema
                        float sample = rawSample * currentMasterVolume;

                        // Aplica Janela de Hann
                        float window = (float)FastFourierTransform.HannWindow(fftPos, FFT_POINTS);
                        fftBuffer[fftPos].X = sample * window;
                        fftBuffer[fftPos].Y = 0;
                        fftPos++;

                        if (fftPos >= FFT_POINTS)
                        {
                            fftPos = 0;
                            FastFourierTransform.FFT(true, (int)Math.Log(FFT_POINTS, 2), fftBuffer);
                            ProcessarBandasFFT();
                        }
                    }
                };
                systemAudio.StartRecording();
            }
            catch (Exception ex)
            {
                MessageBox.Show("Erro ao iniciar áudio do sistema: " + ex.Message);
            }
        }

        private void AtualizarVolumeMaster()
        {
            if (systemAudioDevice != null)
            {
                bool isMuted = systemAudioDevice.AudioEndpointVolume.Mute;
                currentMasterVolume = isMuted ? 0.0f : systemAudioDevice.AudioEndpointVolume.MasterVolumeLevelScalar;
            }
        }

        private void ProcessarBandasFFT()
        {
            int[][] binRanges = new int[][]
            {
                new int[] { 1, 2 },     // Sub-Bass
                new int[] { 3, 5 },     // Bass
                new int[] { 6, 10 },    // Low-Mid
                new int[] { 11, 20 },   // Mid
                new int[] { 21, 40 },   // Upper-Mid
                new int[] { 41, 80 },   // High-Mid
                new int[] { 81, 160 },  // Presence
                new int[] { 161, 300 }  // Brilliance
            };

            for (int b = 0; b < 8; b++)
            {
                float sum = 0;
                int count = 0;

                for (int bin = binRanges[b][0]; bin <= binRanges[b][1]; bin++)
                {
                    float mag = (float)Math.Sqrt(fftBuffer[bin].X * fftBuffer[bin].X + fftBuffer[bin].Y * fftBuffer[bin].Y);
                    sum += mag;
                    count++;
                }

                float avg = sum / count;
                float gain = 500.0f * (1.0f + b * 0.3f);
                int val = (int)(avg * gain);

                bands[b] = Math.Min(100, Math.Max(0, val));
            }
        }

        private void InicializarMicrofone()
        {
            try
            {
                if (WaveIn.DeviceCount > 0)
                {
                    micAudio = new WaveInEvent();
                    micAudio.DataAvailable += (s, a) =>
                    {
                        float maxM = 0;
                        for (int i = 0; i < a.BytesRecorded; i += 2)
                        {
                            short sample = BitConverter.ToInt16(a.Buffer, i);
                            float sampleFloat = Math.Abs(sample / 32768f);
                            if (sampleFloat > maxM) maxM = sampleFloat;
                        }
                        mic = Math.Min(100, maxM * 100 * 1.5f);
                    };
                    micAudio.StartRecording();
                }
            }
            catch { }
        }

        private void FecharPortaComSeguranca()
        {
            try { if (serialPort.IsOpen) serialPort.Close(); } catch { }
        }

        public void Dispose()
        {
            reconnectTimer?.Stop();
            FecharPortaComSeguranca();

            try
            {
                if (systemAudioDevice != null && volumeDelegate != null)
                {
                    systemAudioDevice.AudioEndpointVolume.OnVolumeNotification -= volumeDelegate;
                }

                systemAudioDevice?.Dispose();
                systemAudio?.StopRecording();
                systemAudio?.Dispose();
                micAudio?.StopRecording();
                micAudio?.Dispose();
            }
            catch { }
        }
    }
}
```
## Passo 3: Criando o Controle VU

1. Clique com o botão direito e Add
2. Selecione UserControl, coloque o nome VuMeterControl.cs
3. Cole o código a seguir

```CSharp
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
```

## Passo 3: Windows Forms

1. Volte para o formulário Form1.cs em modo de Designer
2. Clique duplo em qualquer parte do form
3. Copie e cole o código abaixo

```CSharp
namespace esp32_cyd_desktop
{
    public partial class Form1 : Form
    {
        VuMeterStream vuMeterStream;
        private VuMeterControl vuControl;

        public Form1()
        {
            InitializeComponent();
            vuMeterStream = new VuMeterStream();
            vuControl = new VuMeterControl
            {
                Dock = DockStyle.Fill
            };
            this.Controls.Add(vuControl);
        }

        private void Form1_Load(object sender, EventArgs e)
        {
            vuMeterStream.OnAudioDataUpdated += (bands, mic) =>
            {
                // Garante a execução na Thread de UI
                if (this.IsHandleCreated)
                {
                    this.BeginInvoke((MethodInvoker)delegate
                    {
                        vuControl.UpdateValues(bands, mic);
                    });
                }
            };
        }
    }
}
```

## Passo 4: Conexão Bluetooh com ESP32-CYD

1. Ligue o ESP32-CYD a uma fonte de energia
2. Acesse as configurações Bluetooh do seu Windows no lado do relógio dando duplo clique no icone azul do Bluetooth, as configurações vão se abrir.
3. Clique em Add Bluetooh or other device
4. Em Add Device clique na primeira opção "Bluetooth"
5. Selecione ESP32-VU-Meter, ele via se conectar e depois clique em "Done"
6. Agora precisamos descobrir a porta COM, do lado direito vá em "More Bluetooth Options"
7. Clique na aba "Ports"
8. Localiza o número da porta Outgoint e anote o número
9. Vá ao código do Visual Studio na classe VuMeterStream e altere a variável PORT_NAME para o número da porta

## Passo 5: Testando

1. Clique no icone de "Play" do visual studio e abra um video do Youtube
2. O Vu deve se mover e os dados atualizados.
## Problemas que eu encontrei

Para que de certo o áudio ser capturado e enviado para o ESP32 vc tem que ter instalado no Windows o Stereo Mixer.

Esse carinha á instalado com os drivers da placa de som, se não aparecer pode ser que o Windows instalou o driver nativo que não vem com o Stereo Mix,

Para saber se está instalado clique com o botão direito sobre o icone do alto falante e clique em "Sounds"

Vá na aba "Recording" e verifique se aparece Stereo Mix, se não tiver aparecendo clique com o botão direito e marque Show Disable Devices, se mesmo assim não aparecer entra no cenário do driver da placa que o Windows instalou nativamente, o correto seria o do fabricante

# Aplicação Android

## Passo 1: Criando projeto

1. Abra o Android Studio 
2. Na tela de welcome, clique em New Project 
3. Em templates mantenha a seleção do Template Phone and Tablets
4. Do lado direito selecione o template "Empty View Activities" clique em Next
5. Coloque o nome da aplicação
6. Informe um nome de pacote no formato com.seunome.esp32vumeter
7. A linguagem mantenha Java
8. Minimum SDK selecione API 29 ("Q",Android 10.0)
9. Clique em Finish

## Passo 2: Configurando seu celular

1. Para que o Bluetooth possa funcionar sem problemas vamos usar um dispositivo fisicao, precisamos habilitar as funçoes de desenvolvedor nele
2. Vá para "Config" ou "Configurações" 
3. Ache no menu "Sobre esse telefone" ou "Sobre esse dispositivo"
4. Localize "Informações do software" e depois "Versao do Android"
5. Clique 10 vezes sobre "Versao do Android" ou em  "Numero da compilação" dependendo da versão do seu Android,  até parecer um aviso que vc e desenvolvedor
6. Vai aparecer num novo menu chamado "Opções de desenvolvedor", ao clicar os itens irão aparecer do lado direito, procure por "Depuração USB" e habilite
7. Plugue o cabo USB no computador, irá aparecer um alerta no celular para escolher as opções de USB, selecioen "transferencia de arquivo"
8. O Android vai soliciar a permissão, clique em "Permitir"
9. Em seguida o Android vai reconhecer o dispositvo
10. Para testar clique no botão de "Play"
11. A sua aplicação está vazia, automaticamente será instado no dispositivo
12. Se a aplicação for instalada e executar está tudo certo.
## Passo 3: Criando/Alterando o Layout

1. Por padrão ao criar um novo template um layout é gerado automáticamente,
2. Expanda App > res > layout 
3. Clique duas vezes para abrir em activity_main.xml
4. Apague todo código existente
5. Copie e cole o código abaixo e salbe o arquivo

Layout Activity_Main
```Xml
<?xml version="1.0" encoding="utf-8"?>  
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"  
    android:layout_width="match_parent"  
    android:layout_height="match_parent"  
    android:orientation="vertical"  
    android:padding="16dp"  
    android:background="#0A0F0A">  
  
    <!-- CABEÇALHO -->  
    <TextView  
        android:layout_width="wrap_content"  
        android:layout_height="wrap_content"  
        android:layout_gravity="center_horizontal"  
        android:text="ESP32 VU Meter"  
        android:textColor="#FFFFFF"  
        android:textSize="22sp"  
        android:textStyle="bold" />  
  
    <TextView        android:id="@+id/txtStatus"  
        android:layout_width="match_parent"  
        android:layout_height="wrap_content"  
        android:layout_marginTop="4dp"  
        android:gravity="center"  
        android:text="Verificando dispositivo..."  
        android:textColor="#888888"  
        android:textSize="14sp" />  
  
    <!-- BOTÃO LOGO ACIMA DO GRÁFICO (Fácil Acesso) -->  
    <Button  
        android:id="@+id/btnConnect"  
        android:layout_width="match_parent"  
        android:layout_height="wrap_content"  
        android:layout_marginTop="12dp"  
        android:layout_marginBottom="8dp"  
        android:padding="12dp"  
        android:text="Conectar"  
        android:textSize="16sp" />  
  
    <!-- GRÁFICO VU METER PREENCHENDO O RESTANTE DA TELA -->  
    <com.seunome.esp32vumeter.VuMeterView  
        android:id="@+id/vuMeterView"  
        android:layout_width="match_parent"  
        android:layout_height="0dp"  
        android:layout_weight="1"  
        android:layout_marginTop="8dp" />  
  
</LinearLayout>
```

## Passo 5: Criando a Classe do Controle de VU

1.  Vamos criar uma classe de serviço, vá em app > Kotlin+java > e clique com o botão direito sobre "com.seunome.esp32vumeter"
2. Selecione New > JavaClass
3. Nomeia como VuMeterView e pressione "Enter"
4. Copie e cole o código abaixo:

```Java
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
     * Atualiza os valores e agenda a nova renderização de forma segura.     */    public void updateValues(int[] bands, float mic) {  
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
```

## Passo 4: Criando a Classe de Serviço

1.  Vamos criar uma classe de serviço, vá em app > Kotlin+java > e clique com o botão direito sobre "com.seunome.esp32vumeter"
2. Selecione New > JavaClass
3. Nomeia como VUService e pressione "Enter"
4. Copie e cole o código abaixo:

Classe VU Service

```Java
package com.seunome.esp32vumeter;  
  
import android.annotation.SuppressLint;  
import android.app.Activity;  
import android.app.Notification;  
import android.app.NotificationChannel;  
import android.app.NotificationManager;  
import android.app.Service;  
import android.bluetooth.BluetoothAdapter;  
import android.bluetooth.BluetoothDevice;  
import android.bluetooth.BluetoothSocket;  
import android.content.Context;  
import android.content.Intent;  
import android.content.pm.ServiceInfo;  
import android.media.AudioAttributes;  
import android.media.AudioFormat;  
import android.media.AudioManager;  
import android.media.AudioPlaybackCaptureConfiguration;  
import android.media.AudioRecord;  
import android.media.MediaRecorder;  
import android.media.projection.MediaProjection;  
import android.media.projection.MediaProjectionManager;  
import android.os.Binder;  
import android.os.Build;  
import android.os.Handler;  
import android.os.HandlerThread;  
import android.os.IBinder;  
import android.os.Looper;  
import android.util.Log;  
import android.widget.Toast;  
  
import androidx.core.app.NotificationCompat;  
  
import java.io.OutputStream;  
import java.util.UUID;  
  
public class VUService extends Service {  
  
    private static final String TAG = "VUService";  
    private static final String CHANNEL_ID = "ESP32_VU_CHANNEL_ID";  
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");  
  
    private BluetoothSocket bluetoothSocket;  
    private OutputStream outputStream;  
    private boolean isConnected = false;  
  
    private AudioRecord micAudioRecord;  
    private AudioRecord systemAudioRecord;  
    private MediaProjection mediaProjection;  
    private boolean isRecording = false;  
  
    // Gerenciador de Áudio para ler o volume do sistema  
    private AudioManager audioManager;  
  
    // Dados de envio  
    private final int[] bands = new int[8];  
    private final float[] targetBands = new float[8];  
    private int volumeLeft = 0;  
    private int volumeRight = 0;  
    private float targetVolumeLR = 0;  
    private int volumeMic = 0;  
  
    // Ganhos calibrados para a zona verde  
    private final float[] GAINS = new float[]{0.45f, 0.40f, 0.35f, 0.32f, 0.30f, 0.30f, 0.35f, 0.40f};  
  
    // Thread dedicada para envio Bluetooth sem travar a UI/Processamento  
    private HandlerThread btWorkerThread;  
    private Handler btHandler;  
  
    // --- BINDING & CALLBACK DA MAINACTIVITY ---  
    public interface OnVuDataListener {  
        void onVuDataUpdated(int[] bands, float mic);  
    }  
  
    private OnVuDataListener vuDataListener;  
  
    public class LocalBinder extends Binder {  
        public VUService getService() {  
            return VUService.this;  
        }  
    }  
  
    private final IBinder binder = new LocalBinder();  
  
    public void setOnVuDataListener(OnVuDataListener listener) {  
        this.vuDataListener = listener;  
    }  
  
    private final Runnable sendRunnable = new Runnable() {  
        @Override  
        public void run() {  
            if (isConnected && outputStream != null) {  
                suavizarEEnviar();  
            }  
            if (isRecording) {  
                btHandler.postDelayed(this, 33); // Taxa estável de 30 FPS para Bluetooth  
            }  
        }  
    };  
  
    @Override  
    public void onCreate() {  
        super.onCreate();  
        createNotificationChannel();  
  
        // Inicializa o AudioManager  
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);  
  
        // Inicializa thread secundária dedicada para o Bluetooth  
        btWorkerThread = new HandlerThread("BT_WorkerThread");  
        btWorkerThread.start();  
        btHandler = new Handler(btWorkerThread.getLooper());  
    }  
  
    @Override  
    public int onStartCommand(Intent intent, int flags, int startId) {  
        String deviceAddress = intent != null ? intent.getStringExtra("DEVICE_ADDRESS") : null;  
  
        try {  
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)  
                    .setContentTitle("ESP32 VU Meter")  
                    .setContentText("Transmitindo áudio em tempo real...")  
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)  
                    .setPriority(NotificationCompat.PRIORITY_LOW)  
                    .setOngoing(true)  
                    .build();  
  
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  
                int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE  
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE  
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;  
                startForeground(1, notification, serviceType);  
            } else {  
                startForeground(1, notification);  
            }  
        } catch (Exception e) {  
            Log.e(TAG, "Erro Foreground Service: " + e.getMessage());  
        }  
  
        if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  
            int resultCode = intent.getIntExtra("MEDIA_PROJECTION_RESULT_CODE", Activity.RESULT_CANCELED);  
            Intent projectionData = intent.getParcelableExtra("MEDIA_PROJECTION_DATA");  
  
            if (resultCode == Activity.RESULT_OK && projectionData != null) {  
                MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);  
                if (projectionManager != null) {  
                    mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData);  
                }  
            }  
        }  
  
        if (deviceAddress != null && !isConnected) {  
            connectToBluetooth(deviceAddress);  
        }  
  
        return START_STICKY;  
    }  
  
    @SuppressLint("MissingPermission")  
    private void connectToBluetooth(String address) {  
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();  
        if (adapter == null) return;  
  
        new Thread(() -> {  
            try {  
                BluetoothDevice device = adapter.getRemoteDevice(address);  
                if (adapter.isDiscovering()) adapter.cancelDiscovery();  
  
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);  
                bluetoothSocket.connect();  
                outputStream = bluetoothSocket.getOutputStream();  
  
                isConnected = true;  
                showToast("Conectado ao ESP32-VU-Meter!");  
  
                startAudioCaptures();  
                btHandler.post(sendRunnable);  
  
            } catch (Exception e) {  
                Log.e(TAG, "Erro na Conexão BT: " + e.getMessage());  
                showToast("Erro na conexão Bluetooth!");  
                stopSelf();  
            }  
        }).start();  
    }  
  
    @SuppressLint("MissingPermission")  
    private void startAudioCaptures() {  
        isRecording = true;  
  
        // 1. ÁUDIO DO SISTEMA  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {  
            new Thread(() -> {  
                try {  
                    AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)  
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)  
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)  
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)  
                            .build();  
  
                    int sampleRate = 44100;  
                    int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);  
                    if (bufferSize < 2048) bufferSize = 2048;  
  
                    systemAudioRecord = new AudioRecord.Builder()  
                            .setAudioFormat(new AudioFormat.Builder()  
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)  
                                    .setSampleRate(sampleRate)  
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)  
                                    .build())  
                            .setBufferSizeInBytes(bufferSize)  
                            .setAudioPlaybackCaptureConfig(config)  
                            .build();  
  
                    if (systemAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {  
                        systemAudioRecord.startRecording();  
                        short[] buffer = new short[bufferSize / 2];  
  
                        while (isRecording) {  
                            int read = systemAudioRecord.read(buffer, 0, buffer.length);  
                            if (read > 0) {  
                                processarAudioBuffer(buffer, read);  
                            }  
                        }  
                    }  
                } catch (Exception e) {  
                    Log.e(TAG, "Erro no Áudio do Sistema: " + e.getMessage());  
                }  
            }).start();  
        }  
  
        // 2. MICROFONE  
        new Thread(() -> {  
            try {  
                int sampleRate = 44100;  
                int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);  
  
                micAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,  
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);  
  
                if (micAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {  
                    micAudioRecord.startRecording();  
                    short[] buffer = new short[bufferSize / 2];  
  
                    while (isRecording) {  
                        int read = micAudioRecord.read(buffer, 0, buffer.length);  
                        if (read > 0) {  
                            float maxM = 0;  
                            for (int i = 0; i < read; i++) {  
                                float sampleFloat = Math.abs(buffer[i] / 32768.0f);  
                                if (sampleFloat > maxM) maxM = sampleFloat;  
                            }  
                            volumeMic = (int) Math.min(100, maxM * 100.0f * 0.5f);  
                        }  
                    }  
                }  
            } catch (Exception e) {  
                Log.e(TAG, "Erro no Microfone: " + e.getMessage());  
            }  
        }).start();  
    }  
  
    private void processarAudioBuffer(short[] buffer, int length) {  
        // Obter fator do volume do sistema (de 0.0 a 1.0)  
        float volumeFactor = 1.0f;  
        if (audioManager != null) {  
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);  
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);  
            if (maxVolume > 0) {  
                volumeFactor = (float) currentVolume / maxVolume;  
            }  
        }  
  
        // --- 1. VOLUME GLOBAL (L/R) ---  
        double globalSum = 0;  
        for (int i = 0; i < length; i++) {  
            float sample = buffer[i] / 32768.0f;  
            globalSum += (sample * sample);  
        }  
        float globalRms = (float) Math.sqrt(globalSum / length);  
  
        // Aplica o fator de volume do celular no valor alvo  
        float calculatedNeedleVal = (float) (Math.pow(globalRms * 0.45f, 1.3) * 100.0f * 0.7f) * volumeFactor;  
        targetVolumeLR = Math.min(100f, Math.max(0f, calculatedNeedleVal));  
  
        // --- 2. 8 BANDAS DE FREQUÊNCIA ---  
        int chunkSize = length / 8;  
        if (chunkSize < 1) return;  
  
        for (int b = 0; b < 8; b++) {  
            double sum = 0;  
            int start = b * chunkSize;  
            int end = Math.min(start + chunkSize, length);  
  
            for (int i = start; i < end; i++) {  
                float sample = buffer[i] / 32768.0f;  
                sum += (sample * sample);  
            }  
  
            float rms = (float) Math.sqrt(sum / (end - start));  
  
            // Aplica o fator de volume do celular nas bandas  
            float val = (float) (Math.pow(rms * 0.85f, 1.25) * 100.0f * GAINS[b]) * volumeFactor;  
  
            targetBands[b] = Math.min(100f, Math.max(0f, val));  
        }  
    }  
  
    private void suavizarEEnviar() {  
        // Suavização L/R  
        if (targetVolumeLR > volumeLeft) {  
            volumeLeft = (int) targetVolumeLR;  
        } else {  
            volumeLeft = (int) (volumeLeft * 0.80f);  
        }  
        volumeRight = volumeLeft;  
  
        // Suavização Bandas  
        for (int i = 0; i < 8; i++) {  
            if (targetBands[i] > bands[i]) {  
                bands[i] = (int) targetBands[i];  
            } else {  
                bands[i] = (int) (bands[i] * 0.75f);  
            }  
        }  
  
        // --- NOTIFICA A MAINACTIVITY VIA MEMÓRIA RAM ---  
        if (vuDataListener != null) {  
            final int[] currentBands = bands.clone();  
            final float currentMic = volumeMic;  
  
            new Handler(Looper.getMainLooper()).post(() -> {  
                if (vuDataListener != null) {  
                    vuDataListener.onVuDataUpdated(currentBands, currentMic);  
                }  
            });  
        }  
  
        // Transmissão direta sem alocar novas Threads  
        try {  
            StringBuilder sb = new StringBuilder(64);  
            sb.append("{\"l\":").append(volumeLeft)  
                    .append(",\"r\":").append(volumeRight)  
                    .append(",\"b\":[");  
            for (int i = 0; i < bands.length; i++) {  
                sb.append(bands[i]);  
                if (i < bands.length - 1) sb.append(",");  
            }  
            sb.append("],\"m\":").append(volumeMic).append("}\n");  
  
            outputStream.write(sb.toString().getBytes());  
            outputStream.flush();  
        } catch (Exception e) {  
            Log.e(TAG, "Falha no envio Bluetooth: " + e.getMessage());  
            stopSelf();  
        }  
    }  
  
    private void showToast(String message) {  
        new Handler(Looper.getMainLooper()).post(() ->  
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()  
        );  
    }  
  
    private void createNotificationChannel() {  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {  
            try {  
                NotificationChannel channel = new NotificationChannel(  
                        CHANNEL_ID,  
                        "ESP32 VU Service",  
                        NotificationManager.IMPORTANCE_LOW  
                );  
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);  
                if (manager != null) manager.createNotificationChannel(channel);  
            } catch (Exception ignored) {}  
        }  
    }  
  
    @Override  
    public void onDestroy() {  
        super.onDestroy();  
        isConnected = false;  
        isRecording = false;  
  
        if (btHandler != null) {  
            btHandler.removeCallbacks(sendRunnable);  
        }  
        if (btWorkerThread != null) {  
            btWorkerThread.quitSafely();  
        }  
  
        try {  
            if (mediaProjection != null) {  
                mediaProjection.stop();  
            }  
            if (systemAudioRecord != null) {  
                systemAudioRecord.stop();  
                systemAudioRecord.release();  
            }  
            if (micAudioRecord != null) {  
                micAudioRecord.stop();  
                micAudioRecord.release();  
            }  
            if (outputStream != null) outputStream.close();  
            if (bluetoothSocket != null) bluetoothSocket.close();  
        } catch (Exception ignored) {}  
    }  
  
    @Override  
    public IBinder onBind(Intent intent) {  
        return binder;  
    }  
}
```

## Passo 5: Alterando a MainActivity

1. Agora vamos alterar a MainAcitivy, acesse app > Kotlin+Java > com.seunome.esp32vumeter e clique duas vezes em MainActivity
2. Apague o código existente
3. Copie e cole o código a seguir

Main Activity
```Java
package com.seunome.esp32vumeter;  
  
import android.Manifest;  
import android.annotation.SuppressLint;  
import android.app.Activity;  
import android.bluetooth.BluetoothAdapter;  
import android.bluetooth.BluetoothDevice;  
import android.content.ComponentName;  
import android.content.Context;  
import android.content.Intent;  
import android.content.ServiceConnection;  
import android.content.pm.PackageManager;  
import android.media.projection.MediaProjectionManager;  
import android.os.Build;  
import android.os.Bundle;  
import android.os.IBinder;  
import android.util.Log;  
import android.widget.Button;  
import android.widget.TextView;  
import android.widget.Toast;  
  
import androidx.activity.result.ActivityResultLauncher;  
import androidx.activity.result.contract.ActivityResultContracts;  
import androidx.annotation.NonNull;  
import androidx.appcompat.app.AppCompatActivity;  
import androidx.core.app.ActivityCompat;  
import androidx.core.content.ContextCompat;  
  
import java.util.ArrayList;  
import java.util.List;  
import java.util.Set;  
  
public class MainActivity extends AppCompatActivity implements VUService.OnVuDataListener {  
  
    private static final String TAG = "MainActivity";  
    private static final int PERMISSION_REQ_CODE = 1001;  
    private static final String TARGET_DEVICE_NAME = "ESP32-VU-Meter";  
  
    private Button btnConnect;  
    private TextView txtStatus;  
    private BluetoothAdapter bluetoothAdapter;  
    private BluetoothDevice esp32Device = null;  
    private boolean isServiceRunning = false;  
  
    private ActivityResultLauncher<Intent> mediaProjectionLauncher;  
    private VuMeterView vuMeterView;  
  
    // Comunicação direta por memória via Binder  
    private VUService vuService;  
    private boolean isBound = false;  
  
    private final ServiceConnection serviceConnection = new ServiceConnection() {  
        @Override  
        public void onServiceConnected(ComponentName name, IBinder service) {  
            VUService.LocalBinder binder = (VUService.LocalBinder) service;  
            vuService = binder.getService();  
            if (vuService != null) {  
                vuService.setOnVuDataListener(MainActivity.this); // Assina a atualização  
            }  
            isBound = true;  
        }  
  
        @Override  
        public void onServiceDisconnected(ComponentName name) {  
            isBound = false;  
            vuService = null;  
        }  
    };  
  
    @Override  
    protected void onCreate(Bundle savedInstanceState) {  
        super.onCreate(savedInstanceState);  
        setContentView(R.layout.activity_main);  
  
        vuMeterView = findViewById(R.id.vuMeterView);  
        btnConnect = findViewById(R.id.btnConnect);  
        txtStatus = findViewById(R.id.txtStatus);  
  
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();  
  
        setupMediaProjectionLauncher();  
  
        btnConnect.setOnClickListener(v -> {  
            if (!isServiceRunning) {  
                if (hasAllPermissions()) {  
                    requestMediaProjectionPermission();  
                } else {  
                    requestAppPermissions();  
                }  
            } else {  
                stopVUService();  
            }  
        });  
  
        if (!hasAllPermissions()) {  
            requestAppPermissions();  
        } else {  
            findEsp32Device();  
        }  
    }  
  
    // CORREÇÃO CRÍTICA 1: Força a atualização da CustomView na UI Thread  
    @Override  
    public void onVuDataUpdated(int[] bands, float mic) {  
        runOnUiThread(() -> {  
            if (vuMeterView != null) {  
                vuMeterView.updateValues(bands, mic);  
            }  
        });  
    }  
  
    private void setupMediaProjectionLauncher() {  
        mediaProjectionLauncher = registerForActivityResult(  
                new ActivityResultContracts.StartActivityForResult(),  
                result -> {  
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {  
                        startVUServiceWithProjection(result.getResultCode(), result.getData());  
                    } else {  
                        Toast.makeText(this, "Permissão de gravação de áudio do sistema negada!", Toast.LENGTH_LONG).show();  
                    }  
                }  
        );  
    }  
  
    private void requestMediaProjectionPermission() {  
        if (esp32Device == null) {  
            findEsp32Device();  
        }  
  
        if (esp32Device == null) {  
            Toast.makeText(this, "Pareie o " + TARGET_DEVICE_NAME + " no Bluetooth antes de conectar!", Toast.LENGTH_LONG).show();  
            return;  
        }  
  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);  
            if (projectionManager != null) {  
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent());  
            }  
        } else {  
            startVUServiceWithProjection(Activity.RESULT_OK, null);  
        }  
    }  
  
    private void startVUServiceWithProjection(int resultCode, Intent data) {  
        Intent serviceIntent = new Intent(this, VUService.class);  
        serviceIntent.putExtra("DEVICE_ADDRESS", esp32Device.getAddress());  
        serviceIntent.putExtra("MEDIA_PROJECTION_RESULT_CODE", resultCode);  
        if (data != null) {  
            serviceIntent.putExtra("MEDIA_PROJECTION_DATA", data);  
        }  
  
        try {  
            // CORREÇÃO CRÍTICA 2: Iniciação segura do serviço  
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {  
                startForegroundService(serviceIntent);  
            } else {  
                startService(serviceIntent);  
            }  
  
            // Conecta a Activity ao Serviço de forma protegida  
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);  
  
            isServiceRunning = true;  
            btnConnect.setText("Desconectar / Parar Background");  
            updateStatus("Conectando ao " + TARGET_DEVICE_NAME + "...");  
        } catch (Exception e) {  
            Log.e(TAG, "Erro ao iniciar VUService: " + e.getMessage());  
            Toast.makeText(this, "Erro ao iniciar serviço: " + e.getMessage(), Toast.LENGTH_LONG).show();  
        }  
    }  
  
    private boolean hasAllPermissions() {  
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;  
  
        boolean btConnect = true;  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {  
            btConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;  
        }  
  
        return mic && btConnect;  
    }  
  
    private void requestAppPermissions() {  
        List<String> permissions = new ArrayList<>();  
        permissions.add(Manifest.permission.RECORD_AUDIO);  
        permissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);  
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);  
  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {  
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);  
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);  
        }  
  
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQ_CODE);  
    }  
  
    @Override  
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {  
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);  
  
        if (requestCode == PERMISSION_REQ_CODE) {  
            if (hasAllPermissions()) {  
                findEsp32Device();  
            } else {  
                Toast.makeText(this, "Permissões de Microfone e Bluetooth são OBRIGATÓRIAS!", Toast.LENGTH_LONG).show();  
            }  
        }  
    }  
  
    @SuppressLint("MissingPermission")  
    private void findEsp32Device() {  
        if (bluetoothAdapter == null) {  
            updateStatus("Bluetooth não suportado neste dispositivo.");  
            return;  
        }  
  
        if (!bluetoothAdapter.isEnabled()) {  
            updateStatus("Ative o Bluetooth do celular!");  
            Toast.makeText(this, "Por favor, ative o Bluetooth!", Toast.LENGTH_LONG).show();  
            return;  
        }  
  
        try {  
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();  
            esp32Device = null;  
  
            if (pairedDevices != null) {  
                for (BluetoothDevice device : pairedDevices) {  
                    if (TARGET_DEVICE_NAME.equals(device.getName())) {  
                        esp32Device = device;  
                        break;  
                    }  
                }  
            }  
  
            if (esp32Device != null) {  
                updateStatus("ESP32 Encontrado!\n" + esp32Device.getAddress());  
            } else {  
                updateStatus("ESP32-VU-Meter NÃO pareado!\nPareie nas configurações do Android.");  
            }  
  
        } catch (SecurityException e) {  
            updateStatus("Erro de permissão no Bluetooth!");  
        }  
    }  
  
    private void stopVUService() {  
        if (isBound) {  
            if (vuService != null) {  
                vuService.setOnVuDataListener(null);  
            }  
            try {  
                unbindService(serviceConnection);  
            } catch (Exception ignored) {}  
            isBound = false;  
        }  
  
        Intent serviceIntent = new Intent(this, VUService.class);  
        stopService(serviceIntent);  
  
        isServiceRunning = false;  
        btnConnect.setText("Conectar");  
        updateStatus("Desconectado.");  
  
        if (vuMeterView != null) {  
            vuMeterView.updateValues(new int[8], 0f);  
        }  
  
        Toast.makeText(this, "Serviço parado!", Toast.LENGTH_SHORT).show();  
    }  
  
    @Override  
    protected void onDestroy() {  
        super.onDestroy();  
        if (isBound) {  
            if (vuService != null) {  
                vuService.setOnVuDataListener(null);  
            }  
            try {  
                unbindService(serviceConnection);  
            } catch (Exception ignored) {}  
            isBound = false;  
        }  
    }  
  
    private void updateStatus(String msg) {  
        if (txtStatus != null) {  
            txtStatus.setText(msg);  
        }  
    }  
}
```

## Passo 6: Arquivo de Manifesto

1. Precisamos informar ao Android as permissões que vamos precisar e a declaração do nosso serviço, vá em app > manifests > AndroidManifest.xml 
2. Apague o conteúdo do arquivo e cole o conteúdo a abaixo:

Android Manifest
```Xml
<?xml version="1.0" encoding="utf-8"?>  
<manifest xmlns:android="http://schemas.android.com/apk/res/android"  
    package="com.seunome.esp32vumeter">  
  
    <!-- Permissões genéricas de Foreground Service -->  
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />  
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />  
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />  
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />  
  
    <!-- Permissões específicas dos recursos usados -->  
    <uses-permission android:name="android.permission.RECORD_AUDIO" />  
    <uses-permission        android:name="android.permission.BLUETOOTH"  
        android:maxSdkVersion="30" />  
    <uses-permission        android:name="android.permission.BLUETOOTH_ADMIN"  
        android:maxSdkVersion="30" />  
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />  
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />  
  
    <application        android:allowBackup="true"  
        android:icon="@mipmap/ic_launcher"  
        android:label="ESP32 VU Box"  
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">  
  
        <activity            android:name=".MainActivity"  
            android:exported="true">  
            <intent-filter>                <action android:name="android.intent.action.MAIN" />  
  
                <category android:name="android.intent.category.LAUNCHER" />  
            </intent-filter>        </activity>  
        <!-- Serviço de captura de áudio e streaming Bluetooth -->  
        <service  
            android:name=".VUService"  
            android:enabled="true"  
            android:exported="false"  
            android:foregroundServiceType="connectedDevice|microphone|mediaProjection" />  
    </application>  
</manifest>
```
## Passo 7: Testando

1. Alguns androids vão solicitar permissão de otimização de bateria, se e mansagem aparecer clique em "Permitir"
2. Depois o Android vai solicitar rodas as permissões conceda as permissões
3. 

