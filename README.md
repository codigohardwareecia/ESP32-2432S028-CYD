Como configurar o ESP32-2432S028-CYD

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

Instalar as Bibliotecas da Tela

Para controlar o display TFT e o Touch, você precisará de duas bibliotecas principais:

1. Vá em **Sketch > Include Library > Manage Libraries...** (ou `Ctrl + Shift + I`) e instale:

2. **TFT_eSPI** (por Bodmer) -> Essa é a biblioteca que desenha os gráficos na tela com alta performance.
    
3. **XPT2046_Touchscreen** (por Paul Stoffregen) -> Essa controla o toque na tela.
    

Configurar a TFT_eSPI

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
