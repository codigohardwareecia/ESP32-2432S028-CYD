#include <WiFi.h>
#include <TFT_eSPI.h>
#include <TJpg_Decoder.h>

const char* ssid     = "Esp32-Cam-Video";
const char* password = "123456789";
const char* host     = "192.168.4.1"; 

TFT_eSPI tft = TFT_eSPI();
WiFiClient client;

#define BUFFER_SIZE 25000 
uint8_t jpg_buffer[BUFFER_SIZE];
uint32_t jpg_len = 0;

// Estados para a máquina de busca do JPEG
enum StreamState { SEARCH_START, CAPTURING };
StreamState currentState = SEARCH_START;
uint8_t last_byte = 0x00;

// Função de saída ultra rápida para desenhar na tela
bool tft_output(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t* bitmap) {
  if (y >= tft.height()) return false;
  tft.pushImage(x, y, w, h, bitmap);
  return true;
}

void setup() {
  Serial.begin(115200);
  setCpuFrequencyMhz(240); // Overclock ativo
  tft.init();
  tft.setRotation(1); 
  tft.setSwapBytes(true); 
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE);
  tft.setTextSize(2);

  TJpgDec.setJpgScale(1);
  TJpgDec.setCallback(tft_output);

  tft.println("Conectando na Camera...");
  WiFi.begin(ssid, password);
  WiFi.setSleep(false); // Mantém o rádio acordado (essencial para FPS alto)

  while (WiFi.status() != WL_CONNECTED) {
    delay(200);
    Serial.print(".");
  }
  
  tft.fillScreen(TFT_BLACK);
  Serial.println("\nConectado!");
}

void loop() {
  // Conecta ou reconecta se cair
  if (!client.connected()) {
    Serial.println("Conectando ao stream...");
    if (!client.connect(host, 80)) {
      delay(500);
      return;
    }
    client.setNoDelay(true); 
    
    // Requisição HTTP padrão para MJPEG
    client.println("GET / HTTP/1.1");
    client.print("Host: ");
    client.println(host);
    client.println("Connection: keep-alive");
    client.println();
    
    currentState = SEARCH_START; // Reseta o estado ao conectar
    last_byte = 0x00;
  }

  // Processa os dados disponíveis no buffer de rede
  int available_bytes = client.available();
  if (available_bytes > 0) {
    // Cria um buffer local temporário para esvaziar o buffer do chip Wi-Fi rapidamente
    uint8_t temp_buf[256]; 
    size_t to_read = (available_bytes > sizeof(temp_buf)) ? sizeof(temp_buf) : available_bytes;
    size_t bytes_read = client.read(temp_buf, to_read);

    for (size_t i = 0; i < bytes_read; i++) {
      uint8_t current_byte = temp_buf[i];

      if (currentState == SEARCH_START) {
        // Procura pelo marcador de início do JPEG (SOI: 0xFF 0xD8)
        if (last_byte == 0xFF && current_byte == 0xD8) {
          jpg_buffer[0] = 0xFF;
          jpg_buffer[1] = 0xD8;
          jpg_len = 2;
          currentState = CAPTURING;
        }
      } 
      else if (currentState == CAPTURING) {
        if (jpg_len < BUFFER_SIZE) {
          jpg_buffer[jpg_len++] = current_byte;
          
          // Procura pelo marcador de fim do JPEG (EOI: 0xFF 0xD9)
          if (last_byte == 0xFF && current_byte == 0xD9) {
            // Desenha o frame imediatamente
            TJpgDec.drawJpg(0, 0, jpg_buffer, jpg_len);
            
            // Força o reset para procurar o próximo frame, ignorando os cabeçalhos HTTP intermediários
            currentState = SEARCH_START; 
          }
        } else {
          // Estouro de buffer (frame corrompido ou muito grande), reseta
          currentState = SEARCH_START;
        }
      }
      last_byte = current_byte;
    }
  }
}