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