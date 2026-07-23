using NAudio.CoreAudioApi;
using NAudio.Dsp;
using NAudio.Wave;
using System;
using System.IO.Ports;
using System.Windows.Forms;

namespace esp32_cyd_desktop
{
    public class VuMeterStream : IDisposable
    {
        private SerialPort serialPort = new SerialPort();
        private WasapiLoopbackCapture systemAudio;
        private WaveInEvent micAudio;

        // Dispositivo e volume master do Windows
        private MMDevice systemAudioDevice;
        private AudioEndpointVolumeNotificationDelegate volumeDelegate;
        private float currentMasterVolume = 1.0f;

        // Array com as 8 bandas do equalizador
        private int[] bands = new int[8];
        private float mic = 0;

        private System.Windows.Forms.Timer reconnectTimer = new System.Windows.Forms.Timer();
        private const string PORT_NAME = "COM10"; // Altere para a sua porta COM
        private const int BAUD_RATE = 115200;

        // Configurações da FFT
        private const int FFT_POINTS = 1024;
        private Complex[] fftBuffer = new Complex[FFT_POINTS];
        private int fftPos = 0;

        // Evento para notificar a interface visual (VuMeterControl)
        public event Action<int[], float> OnAudioDataUpdated;

        public VuMeterStream()
        {
            serialPort.PortName = PORT_NAME;
            serialPort.BaudRate = BAUD_RATE;
            serialPort.WriteTimeout = 500;
            
            BluetoothConnect();

            reconnectTimer.Interval = 2000;
            reconnectTimer.Tick += (s, a) => BluetoothConnect();
            reconnectTimer.Start();

            InicializarAudioSistemaFFT();
            InicializarMicrofone();

            // Timer de Envio/Atualização (~60 FPS)
            System.Windows.Forms.Timer sendTimer = new System.Windows.Forms.Timer();
            sendTimer.Interval = 16;
            sendTimer.Tick += (s, a) => EnviarEAtualizarData();
            sendTimer.Start();
        }

        private void BluetoothConnect()
        {
            if (serialPort.IsOpen) return;
            try
            {
                serialPort.Open();
            }
            catch
            {
                // Silencia exceção para não fechar a aplicação se o dispositivo não estiver conectado
            }
        }

        private void EnviarEAtualizarData()
        {
            // 1. Notifica o UserControl desktop para atualizar o desenho na tela
            OnAudioDataUpdated?.Invoke(bands, mic);

            // 2. Envia para o ESP32 via Serial caso a porta esteja aberta
            if (!serialPort.IsOpen) return;

            try
            {
                string bandsJson = string.Join(",", bands);
                string json = $"{{\"b\":[{bandsJson}],\"m\":{(int)mic}}}\n";
                serialPort.Write(json);
            }
            catch
            {
                FecharPortaComSeguranca();
            }
        }

        private void InicializarAudioSistemaFFT()
        {
            try
            {
                // 1. Obtém o dispositivo de saída padrão do Windows
                MMDeviceEnumerator enumerator = new MMDeviceEnumerator();
                systemAudioDevice = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);

                // 2. Lê o volume inicial e estado de Mute
                AtualizarVolumeMaster();

                // 3. Cadastra o evento para atualizar o volume dinamicamente apenas quando o usuário alterar
                volumeDelegate = (data) =>
                {
                    currentMasterVolume = data.Muted ? 0.0f : data.MasterVolume;
                };
                systemAudioDevice.AudioEndpointVolume.OnVolumeNotification += volumeDelegate;

                systemAudio = new WasapiLoopbackCapture();
                systemAudio.DataAvailable += (s, a) =>
                {
                    int bytesPerSample = systemAudio.WaveFormat.BitsPerSample / 8;

                    for (int i = 0; i < a.BytesRecorded; i += bytesPerSample * 2)
                    {
                        float rawSample = BitConverter.ToSingle(a.Buffer, i);

                        // Aplica o volume master do sistema
                        float sample = rawSample * currentMasterVolume;

                        // Aplica Janela de Hann
                        float window = (float)FastFourierTransform.HannWindow(fftPos, FFT_POINTS);
                        fftBuffer[fftPos].X = sample * window;
                        fftBuffer[fftPos].Y = 0;
                        fftPos++;

                        if (fftPos >= FFT_POINTS)
                        {
                            fftPos = 0;
                            FastFourierTransform.FFT(true, (int)Math.Log(FFT_POINTS, 2), fftBuffer);
                            ProcessarBandasFFT();
                        }
                    }
                };
                systemAudio.StartRecording();
            }
            catch (Exception ex)
            {
                MessageBox.Show("Erro ao iniciar áudio do sistema: " + ex.Message);
            }
        }

        private void AtualizarVolumeMaster()
        {
            if (systemAudioDevice != null)
            {
                bool isMuted = systemAudioDevice.AudioEndpointVolume.Mute;
                currentMasterVolume = isMuted ? 0.0f : systemAudioDevice.AudioEndpointVolume.MasterVolumeLevelScalar;
            }
        }

        private void ProcessarBandasFFT()
        {
            int[][] binRanges = new int[][]
            {
                new int[] { 1, 2 },     // Sub-Bass
                new int[] { 3, 5 },     // Bass
                new int[] { 6, 10 },    // Low-Mid
                new int[] { 11, 20 },   // Mid
                new int[] { 21, 40 },   // Upper-Mid
                new int[] { 41, 80 },   // High-Mid
                new int[] { 81, 160 },  // Presence
                new int[] { 161, 300 }  // Brilliance
            };

            for (int b = 0; b < 8; b++)
            {
                float sum = 0;
                int count = 0;

                for (int bin = binRanges[b][0]; bin <= binRanges[b][1]; bin++)
                {
                    float mag = (float)Math.Sqrt(fftBuffer[bin].X * fftBuffer[bin].X + fftBuffer[bin].Y * fftBuffer[bin].Y);
                    sum += mag;
                    count++;
                }

                float avg = sum / count;
                float gain = 500.0f * (1.0f + b * 0.3f);
                int val = (int)(avg * gain);

                bands[b] = Math.Min(100, Math.Max(0, val));
            }
        }

        private void InicializarMicrofone()
        {
            try
            {
                if (WaveIn.DeviceCount > 0)
                {
                    micAudio = new WaveInEvent();
                    micAudio.DataAvailable += (s, a) =>
                    {
                        float maxM = 0;
                        for (int i = 0; i < a.BytesRecorded; i += 2)
                        {
                            short sample = BitConverter.ToInt16(a.Buffer, i);
                            float sampleFloat = Math.Abs(sample / 32768f);
                            if (sampleFloat > maxM) maxM = sampleFloat;
                        }
                        mic = Math.Min(100, maxM * 100 * 1.5f);
                    };
                    micAudio.StartRecording();
                }
            }
            catch { }
        }

        private void FecharPortaComSeguranca()
        {
            try { if (serialPort.IsOpen) serialPort.Close(); } catch { }
        }

        public void Dispose()
        {
            reconnectTimer?.Stop();
            FecharPortaComSeguranca();

            try
            {
                if (systemAudioDevice != null && volumeDelegate != null)
                {
                    systemAudioDevice.AudioEndpointVolume.OnVolumeNotification -= volumeDelegate;
                }

                systemAudioDevice?.Dispose();
                systemAudio?.StopRecording();
                systemAudio?.Dispose();
                micAudio?.StopRecording();
                micAudio?.Dispose();
            }
            catch { }
        }
    }
}