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

Instalar as seguintes bibliotecas

TFT_eSPI
XPT2046_Touchscreen
ArduinoJson

### Passo 4 : Código de Exemplo com TOUCH

Cole este código no Arduino IDE

```
#include <WiFi.h>
#include <WebServer.h>
#include <SPI.h>
#include <TFT_eSPI.h>
#include <XPT2046_Touchscreen.h>

#define TOUCH_MISO 39
#define TOUCH_MOSI 32
#define TOUCH_CLK  25
#define TOUCH_CS   33

const char* ssid     = "SUA REDE";
const char* password = "SUA SENHA";

// Configuração de IP Fixo
IPAddress local_IP(192, 168, 15, 2); // IP que o ESP32 terá
IPAddress gateway(192, 168, 15, 1);  // IP do seu roteador
IPAddress subnet(255, 255, 255, 0);

TFT_eSPI tft = TFT_eSPI();
WebServer server(80);
TFT_eSprite imgEspelho = TFT_eSprite(&tft); 
SPIClass touchSPI(VSPI);
XPT2046_Touchscreen ts(TOUCH_CS);

int ultimoX = -1;
int ultimoY = -1;

// espessura da caneta
const int espessuraCaneta = 3; 

// Função auxiliar para desenhar a linha grossa conectando os pontos
void desenharLinhaGrossa(int x0, int y0, int x1, int y1, uint16_t cor) {
  // Desenha vários traços paralelos ou círculos para preencher as falhas e engrossar
  for (int i = -espessuraCaneta / 2; i <= espessuraCaneta / 2; i++) {
    for (int j = -espessuraCaneta / 2; j <= espessuraCaneta / 2; j++) {
      tft.drawLine(x0 + i, y0 + j, x1 + i, y1 + j, cor);
      imgEspelho.drawLine(x0 + i, y0 + j, x1 + i, y1 + j, cor);
    }
  }
}

// Função auxiliar para o primeiro toque (ponto isolado)
void desenharPontoGrosso(int x, int y, uint16_t cor) {
  tft.fillCircle(x, y, espessuraCaneta / 2, cor);
  imgEspelho.fillCircle(x, y, espessuraCaneta / 2, cor);
}

// Renderiza a interface original tanto no visor físico quanto no Sprite
void desenharInterfaceEBuffer() {
  // Limpa o vidro da tela física E a memória RAM com a cor BRANCA
  tft.fillScreen(TFT_WHITE);
  imgEspelho.fillSprite(TFT_WHITE);
  
  // Desenha a moldura interna em AZUL nos dois lugares
  tft.drawRect(0, 0, 320, 240, TFT_BLUE);
  imgEspelho.drawRect(0, 0, 320, 240, TFT_BLUE);
  
  // Configura os caracteres para AZUL com fundo BRANCO
  tft.setTextColor(TFT_BLUE, TFT_WHITE);
  tft.setTextSize(2);
  imgEspelho.setTextColor(TFT_BLUE, TFT_WHITE);
  imgEspelho.setTextSize(2);

  // Escreve o texto centralizado em azul
  /*tft.drawCentreString("Example", 160, 20, 1);
  imgEspelho.drawCentreString("Example", 160, 20, 1);*/
  
  /* Escreve o IP na parte inferior em azul
  String ipStr = "IP: " + WiFi.localIP().toString();
  tft.drawString(ipStr, 20, 200, 1);
  imgEspelho.drawString(ipStr, 20, 200, 1); */
}

void handleGetTela() {
  WiFiClient client = server.client();
  
  client.println("HTTP/1.1 200 OK");
  client.println("Content-Type: image/bmp");
  client.println("Access-Control-Allow-Origin: *");
  client.println("Connection: close");
  client.println();

  uint32_t largura = 320;
  uint32_t altura = 240;
  uint32_t tamanhoDadosVisuais = largura * altura * 3; 
  uint32_t tamanhoTotalArquivo = 54 + tamanhoDadosVisuais; 

  uint8_t cabecalhoBMP[54] = {
    'B', 'M',
    (uint8_t)(tamanhoTotalArquivo), (uint8_t)(tamanhoTotalArquivo >> 8), (uint8_t)(tamanhoTotalArquivo >> 16), (uint8_t)(tamanhoTotalArquivo >> 24),
    0, 0, 0, 0,
    54, 0, 0, 0,
    40, 0, 0, 0,
    (uint8_t)(largura), (uint8_t)(largura >> 8), (uint8_t)(largura >> 16), (uint8_t)(largura >> 24),
    (uint8_t)(-altura), (uint8_t)(-altura >> 8), (uint8_t)(-altura >> 16), (uint8_t)(-altura >> 24), // Top-down
    1, 0, 24, 0, 0, 0, 0, 0,
    (uint8_t)(tamanhoDadosVisuais), (uint8_t)(tamanhoDadosVisuais >> 8), (uint8_t)(tamanhoDadosVisuais >> 16), (uint8_t)(tamanhoDadosVisuais >> 24),
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  client.write(cabecalhoBMP, 54);

  uint8_t linhaBuffer24[320 * 3];

  for (int y = 0; y < altura; y++) {
    int idx = 0;
    for (int x = 0; x < largura; x++) {
      uint16_t pixel = imgEspelho.readPixel(x, y);

      uint8_t r = ((pixel >> 11) & 0x1F) << 3;
      uint8_t g = ((pixel >> 5) & 0x3F) << 2;
      uint8_t b = (pixel & 0x1F) << 3;

      linhaBuffer24[idx++] = b;
      linhaBuffer24[idx++] = g;
      linhaBuffer24[idx++] = r;
    }
    client.write(linhaBuffer24, largura * 3);
    if (y % 20 == 0) delay(1);
  }
}

void setup() {
  Serial.begin(115200);

  tft.init();
  tft.setRotation(1); 

  // Configura a profundidade do espelho para 8 bits
  imgEspelho.setColorDepth(8);
  imgEspelho.createSprite(320, 240);

  // Inicializa o Touch
  touchSPI.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
  ts.begin(touchSPI); 
  ts.setRotation(1);

  // Configura IP Fixo
  if (!WiFi.config(local_IP, gateway, subnet)) {
    tft.drawString("Erro ao configurar IP fixo", 10, 10, 1);
  }

  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) { 
    tft.drawString("Conectando...", 10, 10, 1);
    delay(500); 
  }
  
  desenharInterfaceEBuffer();

  server.on("/tela.bmp", handleGetTela);
  
  server.on("/limpar", []() {
    desenharInterfaceEBuffer();
    ultimoX = -1;
    ultimoY = -1;
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "application/json", "{\"status\":\"limpo\"}");
  });

  server.begin();
}

void loop() {
  server.handleClient();

  if (ts.touched()) {
    long somaX = 0, somaY = 0;
    int leiturasValidas = 0;

    for (int i = 0; i < 20; i++) {
      TS_Point p = ts.getPoint();
      if (p.z > 285 && p.z < 3850) { 
        somaX += p.x;  somaY += p.y;
        leiturasValidas++;
      }
      delayMicroseconds(50);
    }

    if (leiturasValidas >= 3) {
      int x = map(somaX / leiturasValidas, 230, 3920, 0, 320);
      int y = map(somaY / leiturasValidas, 285, 3850, 0, 240);

      if (x >= 0 && x < 320 && y >= 0 && y < 240) {
        if (ultimoX != -1 && ultimoY != -1) {
          if (abs(x - ultimoX) > 45 || abs(y - ultimoY) > 45) return; 
          
          // Desenha a linha conectada com a espessura configurada
          desenharLinhaGrossa(ultimoX, ultimoY, x, y, TFT_BLACK);
        } else {
          // Desenha o ponto inicial grosso
          desenharPontoGrosso(x, y, TFT_BLACK);
        }

        ultimoX = x;
        ultimoY = y;
      }
    }
  } else {
    ultimoX = -1;
    ultimoY = -1;
  }
  delay(10);
}
```
