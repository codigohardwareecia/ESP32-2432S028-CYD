#include <SPI.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <WebServer.h>
#include <TJpg_Decoder.h>

// Configurações do Display
TFT_eSPI tft = TFT_eSPI();

// Configurações do Wi-Fi
const char* ssid = "SUA REDE WIFI";
const char* password = "SUA RSENHADE WIFI";

// Configuração de IP Fixo
IPAddress local_IP(192, 168, 15, 2); 
IPAddress gateway(192, 168, 15, 1);  
IPAddress subnet(255, 255, 255, 0);

// Servidor Web na porta 80
WebServer server(80);

// Buffer estático em RAM
#pragma GCC optimize ("O3") 
#define MAX_JPG_SIZE 46080  
uint8_t bufferImagemFixo[MAX_JPG_SIZE];
int tamanhoAcumulado = 0;
bool uploadValido = true;

// Callback para renderizar blocos JPG na tela
bool tft_output(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t* bitmap) {
  if (y >= tft.height()) return false;
  tft.pushImage(x, y, w, h, bitmap); 
  return true;
}

// Manipulador de upload ultra veloz
void handleUpload() {
  HTTPUpload& upload = server.upload();
  
  if (upload.status == UPLOAD_FILE_START) {
    tamanhoAcumulado = 0;
    uploadValido = true;
  } 
  else if (upload.status == UPLOAD_FILE_WRITE) {
    if (uploadValido) {
      if (tamanhoAcumulado + upload.currentSize < MAX_JPG_SIZE) {
        memcpy(bufferImagemFixo + tamanhoAcumulado, upload.buf, upload.currentSize);
        tamanhoAcumulado += upload.currentSize;
      } else {
        uploadValido = false; 
      }
    }
    yield(); 
  } 
  else if (upload.status == UPLOAD_FILE_END) {
    if (uploadValido && tamanhoAcumulado > 0) {
      // 1. Libera o C# imediatamente
      server.send(200, "text/plain", "OK");
      
      // 2. Desenha na tela por cima do frame anterior
      TJpgDec.drawJpg(0, 0, bufferImagemFixo, tamanhoAcumulado);
    } else {
      server.send(400, "text/plain", "Erro");
    }
    
    // Reseta as variáveis após terminar para evitar lixo no próximo frame
    tamanhoAcumulado = 0;
    uploadValido = true;
  }
} 

void setup() {
  setCpuFrequencyMhz(240); // Overclock ativo

  tft.init();
  tft.setRotation(1); 
  tft.setSwapBytes(true); 
  tft.fillScreen(TFT_BLACK);

  TJpgDec.setJpgScale(1);
  TJpgDec.setCallback(tft_output);

  tft.setTextColor(TFT_YELLOW, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawString("Conectando ao WiFi...", 10, 10, 1);

  WiFi.config(local_IP, gateway, subnet);
  WiFi.begin(ssid, password);
  
  while (WiFi.status() != WL_CONNECTED) {
    delay(200); // Conecta ligeiramente mais rápido diminuindo o passo do loop
  }

  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_GREEN, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawString("Modo Monitor Ativo", 10, 10, 1);
  
  tft.setTextSize(1);
  tft.drawString("IP: " + WiFi.localIP().toString(), 15, 215, 1);

  // Única rota ativa: Velocidade máxima
  server.on("/upload", HTTP_POST, [](){}, handleUpload); 
  server.begin();
}

void loop() {
  server.handleClient();
  yield(); 
}