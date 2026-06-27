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

### Passo 4 : Código de Exemplo Monitor de Dados


```
#include <SPI.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <WebServer.h>

// Configurações do Display
TFT_eSPI tft = TFT_eSPI();

// Configurações do Wi-Fi
const char* ssid = "SUA REDE";
const char* password = "SUA SENHA";

// Configuração de IP Fixo
IPAddress local_IP(192, 168, 15, 2); // IP que o ESP32 terá
IPAddress gateway(192, 168, 15, 1);  // IP do seu roteador
IPAddress subnet(255, 255, 255, 0);

// Servidor Web na porta 80
WebServer server(80);

// Declaração das funções para que o compilador as reconheça antes do HTML
void showMessage(String title, String subTitle, String description);
void drawStringWithMultipleLine(String texto, int x_inicial, int y_inicial, int largura_maxima, int tamanho_fonte, uint16_t cor);

// --- PÁGINA WEB EM HTML/CSS/JS ---
const char INDEX_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Painel ESP32 CYD</title>
    <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; background-color: #121212; color: #e0e0e0; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
        .container { background-color: #1e1e1e; padding: 30px; border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.5); width: 100%; max-width: 400px; border: 1px solid #333; }
        h2 { text-align: center; color: #4caf50; margin-top: 0; }
        label { display: block; margin: 14px 0 6px; font-weight: bold; font-size: 14px; color: #b0b0b0; }
        input[type="text"], textarea { width: 100%; padding: 10px; border: 1px solid #444; background-color: #2d2d2d; color: #fff; border-radius: 6px; box-sizing: border-box; font-size: 14px; }
        input[type="text"]:focus, textarea:focus { border-color: #4caf50; outline: none; }
        textarea { resize: vertical; height: 80px; }
        .btn-group { display: flex; gap: 10px; margin-top: 20px; }
        button { flex: 1; padding: 12px; border: none; border-radius: 6px; font-weight: bold; font-size: 15px; cursor: pointer; transition: background 0.2s; }
        .btn-send { background-color: #4caf50; color: white; }
        .btn-send:hover { background-color: #43a047; }
        .btn-clear { background-color: #f44336; color: white; }
        .btn-clear:hover { background-color: #e53935; }
        #status { text-align: center; margin-top: 15px; font-size: 14px; font-weight: 500; }
    </style>
</head>
<body>
    <div class="container">
        <h2>Painel de Controle CYD</h2>
        
        <label for="title">Título:</label>
        <input type="text" id="title" placeholder="Ex: ALERTA DE SISTEMA">

        <label for="subtitle">Subtítulo:</label>
        <input type="text" id="subtitle" placeholder="Ex: Sensor Ativo">

        <label for="description">Descrição:</label>
        <textarea id="description" placeholder="Digite o texto longo aqui..."></textarea>

        <div class="btn-group">
            <button class="btn-send" onclick="enviarDados()">Enviar Painel</button>
            <button class="btn-clear" onclick="limparTela()">Limpar Tela</button>
        </div>

        <div id="status"></div>
    </div>

    <script>
        function exibirStatus(msg, cor) {
            const statusDiv = document.getElementById('status');
            statusDiv.innerText = msg;
            statusDiv.style.color = cor;
        }

        async function enviarDados() {
            exibirStatus("Enviando...", "#ffeb3b");
            
            // Cria os dados no formato application/x-www-form-urlencoded compatível com o seu server.arg()
            const params = new URLSearchParams();
            params.append('title', document.getElementById('title').value);
            params.append('subtitle', document.getElementById('subtitle').value);
            params.append('description', document.getElementById('description').value);

            try {
                const resposta = await fetch('/getdata', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params
                });
                
                if (resposta.ok) {
                    exibirStatus("Painel atualizado com sucesso!", "#4caf50");
                } else {
                    exibirStatus("Erro ao processar no ESP32.", "#f44336");
                }
            } catch (erro) {
                exibirStatus("Erro de conexão.", "#f44336");
            }
        }

        async function limparTela() {
            exibirStatus("Limpando...", "#ffeb3b");
            try {
                const resposta = await fetch('/clear');
                if (resposta.ok) {
                    exibirStatus("Tela limpa!", "#f44336");
                    document.getElementById('title').value = "";
                    document.getElementById('subtitle').value = "";
                    document.getElementById('description').value = "";
                }
            } catch (erro) {
                exibirStatus("Erro de conexão.", "#f44336");
            }
        }
    </script>
</body>
</html>
)rawliteral";

// Endpoint da Raiz - Serve a Interface Web
void handleRoot() {
  server.send(200, "text/html", INDEX_HTML);
}

// Endpoint para receber dados
void handGetData() {
  String title       = server.arg("title");
  String subtitle    = server.arg("subtitle");
  String description = server.arg("description");

  // exibe a mensagem
  showMessage(title, subtitle, description);

  // Responde ao cliente que deu tudo certo
  server.send(200, "text/plain", "Dados recebidos com sucesso!");
}

// Endpoint de limpeza
void handleClear() {
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_RED, TFT_BLACK);
  tft.setTextSize(2);
  server.send(200, "text/plain", "Tela limpa com sucesso!");
}

// Exibe a mensagem na tela
void showMessage(String title, String subTitle, String description){
  // 1. Limpa a tela inteira
  tft.fillScreen(TFT_BLACK);
  
  // 2. Linha 1: TÍTULO (Maior e em Vermelho)
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextSize(3); // Texto grande para destaque
  tft.drawString(title, 10, 10);
  
  // 3. Linha 2: SUBTÍTULO (Médio e em Amarelo/Laranja)
  tft.setTextColor(TFT_DARKCYAN, TFT_BLACK);
  tft.setTextSize(2); // Texto médio
  tft.drawString(subTitle, 10, 40, 1);
  
  // 4. Linha 3: DESCRIÇÃO (Ajustado margens de 15px até 305px -> Largura 290)
  drawStringWithMultipleLine(description, 10, 70, 300, 2, TFT_GOLD);
}

// exibe a mensagem com quebra de linha
void drawStringWithMultipleLine(String texto, int x_inicial, int y_inicial, int largura_maxima, int tamanho_fonte, uint16_t cor) {
  tft.setTextColor(cor, TFT_BLACK);
  tft.setTextSize(tamanho_fonte);
  
  int cursor_x = x_inicial;
  int cursor_y = y_inicial;
  int altura_linha = tamanho_fonte * 10; 
  
  String palavra = "";
  
  for (int i = 0; i <= texto.length(); i++) {
    char c = (i < texto.length()) ? texto[i] : '\0';
    
    // Se encontrar uma quebra de linha explícita (\n) ou espaço ou fim do texto
    if (c == '\n' || c == ' ' || c == '\0') {
      
      // Se houver uma palavra acumulada, processa ela
      if (palavra.length() > 0) {
        if (c == ' ') palavra += " "; // Mantém o espaço se for o separador
        
        int largura_palavra = tft.textWidth(palavra);
        
        // Quebra por estourar a largura da tela
        if (cursor_x + largura_palavra > x_inicial + largura_maxima) {
          cursor_x = x_inicial;
          cursor_y += altura_linha;
        }
        
        tft.drawString(palavra, cursor_x, cursor_y);
        cursor_x += largura_palavra;
        palavra = "";
      }
      
      // Ação específica para a quebra de linha forçada \n
      if (c == '\n') {
        cursor_x = x_inicial;     // Reseta para a margem esquerda
        cursor_y += altura_linha; // Pula para a próxima linha
      }
      
    } else {
      palavra += c; // Continua montando a palavra caractere por caractere
    }
  }
}

void setup() {
  Serial.begin(115200);

  // Inicializa a Tela
  tft.init();
  tft.setRotation(1); 
  tft.fillScreen(TFT_BLACK);

  // mensagem de conexao com Wifi
  tft.setTextColor(TFT_YELLOW, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawString("Conectando ao WiFi...", 10, 10, 1);

  // Configura IP Fixo
  if (!WiFi.config(local_IP, gateway, subnet)) {
    tft.drawString("Erro ao configurar IP fixo", 10, 10, 1);
  }

  // Inicializa Wi-Fi
  WiFi.begin(ssid, password);

  // Tentativa de conexao
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    tft.drawString("Conectando...", 10, 10, 1);
  }

  // Atualiza tela com o IP conectado
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_GREEN, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawString("Conectado com Sucesso!", 10, 10, 1);
  
  tft.setTextSize(1);
  tft.drawString("IP: " + WiFi.localIP().toString(), 15, 215, 1);

  // Vincula os Endpoints às funções
  server.on("/", HTTP_GET, handleRoot);            // <--- Rota adicionada para abrir no navegador
  server.on("/getdata", HTTP_POST, handGetData);
  server.on("/clear", handleClear); 

  // Inicia o servidor
  server.begin();
}

void loop() {
  server.handleClient(); // Mantém o servidor web rodando e ouvindo requisições
  delay(2); // Pequeno delay para estabilidade do WiFi
}
```
