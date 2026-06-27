// inclues do SPI e do display
#include <SPI.h>
#include <TFT_eSPI.h>

// Instancia o objeto tft para acesso ao display
TFT_eSPI tft = TFT_eSPI();

void setup() {
  Serial.begin(115200);

  // 2. Inicializa a Tela
  tft.init();
  tft.setRotation(1); // Horizontal
  tft.fillScreen(TFT_BLACK);

  // Exite a mensagem de Hello World
  tft.setTextColor(TFT_GREEN, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawCentreString("Hello World!", 160, 20, 1);
  tft.drawRect(0, 0, 320, 240, TFT_WHITE);
}

void loop() {
  delay(100);
}