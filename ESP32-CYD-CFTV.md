## ESP32-CYD Transmitindo imagem do ESP32-CAM para o ESP32-CYD
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
	
4. **Conecte o dispositivo:**

	- Em Tools> Ports localize a porta USB onde foi conectada o dispositivo
	- Envie um codigo vazio para teste de comunicação
	- Se a comunicação ocorreu corretamente ao gravar o codigo vazio vamos para o proximo passo.

## Passo 2: Instalar as Bibliotecas da Tela

Para controlar o display TFT e o Touch, você precisará de duas bibliotecas principais:

1. Vá em **Sketch > Include Library > Manage Libraries...** (ou `Ctrl + Shift + I`) e instale:

2. **TFT_eSPI** (por Bodmer) -> Essa é a biblioteca que desenha os gráficos na tela com alta performance.
    
3. **XPT2046_Touchscreen** (por Paul Stoffregen) -> Essa controla o toque na tela.
    

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

## Passo 4: Código do ESP32-CYD

Será necessário instalar a bibloteca TJpg_Decoder
Selecionar Boards > esp32 > ESP32 Dev Module

Código do CYD

```
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
```


## Passo 5: Código do ESP32-CAM

Não será necessário instalar nenhuma biblioteca
Selecionar Boards > esp32 > ESP32 Wrover Module

Código do ESP32-CAM MB

```
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

void setup() {
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0); // Proteção contra Brownout

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
  WiFiClient client = server.available();
  if (client) {
    // IMPORTANTE: Remove o delay de pacotes TCP (Garante envio imediato)
    client.setNoDelay(true); 
    
    String currentLine = "";
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
```
