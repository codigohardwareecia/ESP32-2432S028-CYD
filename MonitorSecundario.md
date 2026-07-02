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
TJpg_Decoder

## Passo 4: O Código do ESP32-CYD

```
#include <SPI.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <WebServer.h>
#include <TJpg_Decoder.h>

// Configurações do Display
TFT_eSPI tft = TFT_eSPI();

// Configurações do Wi-Fi
const char* ssid = "Archenar";
const char* password = "PASS1234567890000";

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
```

## Passo 5: O Código do C-Sharp

Vamos criar um novo projeto .NET 8 do tipo Windows Forms, mas pode se utilizar console:

```
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

public class StreamTransmiter
{
    // Importações da API nativa do Windows para capturar a tela sem WinForms
    [DllImport("user32.dll")]
    private static extern IntPtr GetDesktopWindow();

    [DllImport("user32.dll")]
    private static extern IntPtr GetWindowDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

    [DllImport("gdi32.dll")]
    private static extern bool BitBlt(IntPtr hdcDest, int nXDest, int nYDest, int nWidth, int nHeight, IntPtr hdcSrc, int nXSrc, int nYSrc, int dwRop);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    private const int SM_CXSCREEN = 0;
    private const int SM_CYSCREEN = 1;
    private const int SRCCOPY = 0x00CC0020;

    private readonly HttpClient _client;
    private readonly string _url;
    private readonly int _displayWidth = 320;
    private readonly int _displayHeight = 240;
    private readonly ImageCodecInfo _jpegEncoder;
    private readonly EncoderParameters _encoderParams;

    private CancellationTokenSource _cts;
    public bool IsRunning { get; private set; }

    public StreamTransmiter(string ip)
    {
        _url = $"http://{ip}/upload";
        _client = new HttpClient();

        _jpegEncoder = GetEncoder(ImageFormat.Jpeg);
        _encoderParams = new EncoderParameters(1);
        _encoderParams.Param[0] = new EncoderParameter(Encoder.Quality, 55L);
    }

    public async Task StartStreamAsync()
    {
        if (IsRunning) return;

        IsRunning = true;
        _cts = new CancellationTokenSource();
        var token = _cts.Token;

        // Pega a resolução da tela direto do sistema operacional
        int screenWidth = GetSystemMetrics(SM_CXSCREEN);
        int screenHeight = GetSystemMetrics(SM_CYSCREEN);

        using (Bitmap bitmapOriginal = new Bitmap(screenWidth, screenHeight))
        using (Bitmap bitmapRedimensionado = new Bitmap(_displayWidth, _displayHeight))
        using (Graphics gSrc = Graphics.FromImage(bitmapOriginal))
        using (Graphics gResize = Graphics.FromImage(bitmapRedimensionado))
        using (MemoryStream ms = new MemoryStream())
        {
            gResize.InterpolationMode = InterpolationMode.NearestNeighbor;

            IntPtr hwndDesktop = GetDesktopWindow();

            while (!token.IsCancellationRequested)
            {
                try
                {
                    // Captura a tela usando contextos de dispositivo (DC) nativos
                    IntPtr hdcSrc = GetWindowDC(hwndDesktop);
                    IntPtr hdcDest = gSrc.GetHdc();

                    BitBlt(hdcDest, 0, 0, screenWidth, screenHeight, hdcSrc, 0, 0, SRCCOPY);

                    gSrc.ReleaseHdc(hdcDest);
                    ReleaseDC(hwndDesktop, hdcSrc);

                    // Redimensiona
                    gResize.DrawImage(bitmapOriginal, 0, 0, _displayWidth, _displayHeight);

                    // Salva no stream
                    ms.SetLength(0);
                    bitmapRedimensionado.Save(ms, _jpegEncoder, _encoderParams);

                    // Envia via HTTP
                    using (var formContent = new MultipartFormDataContent())
                    {
                        var conteudoBytes = new StreamContent(new MemoryStream(ms.GetBuffer(), 0, (int)ms.Length));
                        conteudoBytes.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");

                        formContent.Add(conteudoBytes, "file", "screen.jpg");
                        await _client.PostAsync(_url, formContent, token);
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Erro no stream: {ex.Message}");
                }

                try
                {
                    await Task.Delay(10, token);
                }
                catch (TaskCanceledException)
                {
                    break;
                }
            }
        }

        IsRunning = false;
    }

    public void StopStream()
    {
        if (!IsRunning) return;
        _cts?.Cancel();
    }

    private ImageCodecInfo GetEncoder(ImageFormat format)
    {
        ImageCodecInfo[] codecs = ImageCodecInfo.GetImageEncoders();
        foreach (ImageCodecInfo codec in codecs)
        {
            if (codec.FormatID == format.Guid) return codec;
        }
        return null;
    }
}
```
