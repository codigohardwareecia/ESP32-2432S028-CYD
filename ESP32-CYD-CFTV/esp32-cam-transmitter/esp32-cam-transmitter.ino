#include "esp_camera.h"
#include <WiFi.h>
#include "soc/soc.h"           // Para desativar Brownout
#include "soc/rtc_cntl_reg.h"  // Para desativar Brownout

// Configurações do Ponto de Acesso (AP)
const char* ssid = "Esp32-Cam-Video";
const char* password = "123456789";

// Pinos de hardware exatos para o seu modelo
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27
#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

WiFiServer server(80);
WiFiClient client;
String currentLine;

void setup() {
 // WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0); // Proteção contra Brownout
  setCpuFrequencyMhz(240); // Overclock ativo
  Serial.begin(115200);
  Serial.setDebugOutput(false); // Desativado logs de debug para ganhar performance

  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sscb_sda = SIOD_GPIO_NUM;
  config.pin_sscb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  
  // OTIMIZAÇÃO DE RESOLUÇÃO E BUFFER
  if(psramFound()){
    config.frame_size = FRAMESIZE_QVGA; 
    config.jpeg_quality = 15; // Imagem mais leve = transmissão e decodificação mais rápidas
    config.fb_count = 3;      // Triple-buffering para suavizar a taxa de quadros
  } else {
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 16;
    config.fb_count = 1;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Falha na inicialização da câmera: 0x%x", err);
    return;
  }

  sensor_t * s = esp_camera_sensor_get();
  s->set_vflip(s, 1); 
  s->set_hmirror(s, 1);

  // CONFIGURAÇÃO DO PONTO DE ACESSO
  WiFi.softAP(ssid, password);

  // Configura a potência máxima de transmissão do rádio Wi-Fi para melhor sinal
  WiFi.setTxPower(WIFI_POWER_19_5dBm); 

  server.begin();
  Serial.println("Servidor HTTP Otimizado Pronto.");
}

void loop() {
  client = server.available();
  if (client) {
    // IMPORTANTE: Remove o delay de pacotes TCP (Garante envio imediato)
    //client.setNoDelay(true); 
    
    currentLine = "";
    while (client.connected()) {
      if (client.available()) {
        char c = client.read();
        if (c == '\n') {
          if (currentLine.length() == 0) {
            client.println("HTTP/1.1 200 OK");
            client.println("Content-type:multipart/x-mixed-replace; boundary=frame");
            client.println();

            while(client.connected()){
              camera_fb_t * fb = esp_camera_fb_get();
              if (!fb) {
                continue; // Ignora falhas esporádicas silenciosamente para não travar
              }
              
              // Envia o frame
              client.printf("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", fb->len);
              client.write(fb->buf, fb->len);
              client.println();
              
              esp_camera_fb_return(fb);
              
              // Removido qualquer delay artificial para dar taxa máxima de atualização
            }
            break;
          } else {
            currentLine = "";
          }
        } else if (c != '\r') {
          currentLine += c;
        }
      }
    }
    client.stop();
  }
}