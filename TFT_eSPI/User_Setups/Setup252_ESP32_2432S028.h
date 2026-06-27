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