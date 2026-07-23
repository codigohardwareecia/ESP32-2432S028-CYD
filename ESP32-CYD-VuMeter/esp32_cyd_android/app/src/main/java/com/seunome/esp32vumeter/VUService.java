package com.seunome.esp32vumeter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.util.UUID;

public class VUService extends Service {

    private static final String TAG = "VUService";
    private static final String CHANNEL_ID = "ESP32_VU_CHANNEL_ID";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private boolean isConnected = false;

    private AudioRecord micAudioRecord;
    private AudioRecord systemAudioRecord;
    private MediaProjection mediaProjection;
    private boolean isRecording = false;

    // Gerenciador de Áudio para ler o volume do sistema
    private AudioManager audioManager;
    private volatile float currentVolumeFactor = 1.0f; // Cache do volume para não travar a thread de áudio

    // Dados de envio
    private final int[] bands = new int[8];
    private final float[] targetBands = new float[8];
    private int volumeLeft = 0;
    private int volumeRight = 0;
    private float targetVolumeLR = 0;
    private int volumeMic = 0;

    // Ganhos calibrados para a zona verde
    private final float[] GAINS = new float[]{0.45f, 0.40f, 0.35f, 0.32f, 0.30f, 0.30f, 0.35f, 0.40f};

    // Thread dedicada para envio Bluetooth sem travar a UI/Processamento
    private HandlerThread btWorkerThread;
    private Handler btHandler;

    // --- BINDING & CALLBACK DA MAINACTIVITY ---
    public interface OnVuDataListener {
        void onVuDataUpdated(int[] bands, float mic);
    }

    private OnVuDataListener vuDataListener;

    public class LocalBinder extends Binder {
        public VUService getService() {
            return VUService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    public void setOnVuDataListener(OnVuDataListener listener) {
        this.vuDataListener = listener;
    }

    private final Runnable sendRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected && outputStream != null) {
                atualizarFatorVolume(); // Atualiza o volume do sistema com segurança (a ~30 FPS)
                suavizarEEnviar();
            }
            if (isRecording) {
                btHandler.postDelayed(this, 33); // Taxa estável de 30 FPS para Bluetooth
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // Inicializa o AudioManager
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Inicializa thread secundária dedicada para o Bluetooth
        btWorkerThread = new HandlerThread("BT_WorkerThread");
        btWorkerThread.start();
        btHandler = new Handler(btWorkerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String deviceAddress = intent != null ? intent.getStringExtra("DEVICE_ADDRESS") : null;

        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("ESP32 VU Meter")
                    .setContentText("Transmitindo áudio em tempo real...")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                startForeground(1, notification, serviceType);
            } else {
                startForeground(1, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro Foreground Service: " + e.getMessage());
        }

        if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int resultCode = intent.getIntExtra("MEDIA_PROJECTION_RESULT_CODE", Activity.RESULT_CANCELED);
            Intent projectionData = intent.getParcelableExtra("MEDIA_PROJECTION_DATA");

            if (resultCode == Activity.RESULT_OK && projectionData != null) {
                MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (projectionManager != null) {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData);
                }
            }
        }

        if (deviceAddress != null && !isConnected) {
            connectToBluetooth(deviceAddress);
        }

        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void connectToBluetooth(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        new Thread(() -> {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                if (adapter.isDiscovering()) adapter.cancelDiscovery();

                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();

                isConnected = true;
                showToast("Conectado ao ESP32-VU-Meter!");

                startAudioCaptures();
                btHandler.post(sendRunnable);

            } catch (Exception e) {
                Log.e(TAG, "Erro na Conexão BT: " + e.getMessage());
                showToast("Erro na conexão Bluetooth!");
                stopSelf();
            }
        }).start();
    }

    @SuppressLint("MissingPermission")
    private void startAudioCaptures() {
        isRecording = true;

        // 1. ÁUDIO DO SISTEMA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            new Thread(() -> {
                try {
                    AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

                    int sampleRate = 44100;
                    int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (bufferSize < 2048) bufferSize = 2048;

                    systemAudioRecord = new AudioRecord.Builder()
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build())
                            .setBufferSizeInBytes(bufferSize)
                            .setAudioPlaybackCaptureConfig(config)
                            .build();

                    if (systemAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        systemAudioRecord.startRecording();
                        short[] buffer = new short[bufferSize / 2];

                        while (isRecording) {
                            int read = systemAudioRecord.read(buffer, 0, buffer.length);
                            if (read > 0) {
                                processarAudioBuffer(buffer, read);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro no Áudio do Sistema: " + e.getMessage());
                }
            }).start();
        }

        // 2. MICROFONE
        new Thread(() -> {
            try {
                int sampleRate = 44100;
                int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

                micAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

                if (micAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    micAudioRecord.startRecording();
                    short[] buffer = new short[bufferSize / 2];

                    while (isRecording) {
                        int read = micAudioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            float maxM = 0;
                            for (int i = 0; i < read; i++) {
                                float sampleFloat = Math.abs(buffer[i] / 32768.0f);
                                if (sampleFloat > maxM) maxM = sampleFloat;
                            }
                            volumeMic = (int) Math.min(100, maxM * 100.0f * 0.5f);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro no Microfone: " + e.getMessage());
            }
        }).start();
    }

    // Método seguro com try/catch para ler o volume sem estourar chamadas IPC
    private void atualizarFatorVolume() {
        try {
            if (audioManager != null) {
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (maxVolume > 0) {
                    currentVolumeFactor = (float) currentVolume / maxVolume;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter volume do sistema: " + e.getMessage());
        }
    }

    private void processarAudioBuffer(short[] buffer, int length) {
        // Usa a variável em cache (sem chamar o audioManager no loop do buffer)
        float volumeFactor = currentVolumeFactor;

        // --- 1. VOLUME GLOBAL (L/R) ---
        double globalSum = 0;
        for (int i = 0; i < length; i++) {
            float sample = buffer[i] / 32768.0f;
            globalSum += (sample * sample);
        }
        float globalRms = (float) Math.sqrt(globalSum / length);

        // Aplica o fator de volume do celular no valor alvo
        float calculatedNeedleVal = (float) (Math.pow(globalRms * 0.45f, 1.3) * 100.0f * 0.7f) * volumeFactor;
        targetVolumeLR = Math.min(100f, Math.max(0f, calculatedNeedleVal));

        // --- 2. 8 BANDAS DE FREQUÊNCIA ---
        int chunkSize = length / 8;
        if (chunkSize < 1) return;

        for (int b = 0; b < 8; b++) {
            double sum = 0;
            int start = b * chunkSize;
            int end = Math.min(start + chunkSize, length);

            for (int i = start; i < end; i++) {
                float sample = buffer[i] / 32768.0f;
                sum += (sample * sample);
            }

            float rms = (float) Math.sqrt(sum / (end - start));

            // Aplica o fator de volume do celular nas bandas
            float val = (float) (Math.pow(rms * 0.85f, 1.25) * 100.0f * GAINS[b]) * volumeFactor;

            targetBands[b] = Math.min(100f, Math.max(0f, val));
        }
    }

    private void suavizarEEnviar() {
        // Suavização L/R
        if (targetVolumeLR > volumeLeft) {
            volumeLeft = (int) targetVolumeLR;
        } else {
            volumeLeft = (int) (volumeLeft * 0.80f);
        }
        volumeRight = volumeLeft;

        // Suavização Bandas
        for (int i = 0; i < 8; i++) {
            if (targetBands[i] > bands[i]) {
                bands[i] = (int) targetBands[i];
            } else {
                bands[i] = (int) (bands[i] * 0.75f);
            }
        }

        // --- NOTIFICA A MAINACTIVITY VIA MEMÓRIA RAM ---
        if (vuDataListener != null) {
            final int[] currentBands = bands.clone();
            final float currentMic = volumeMic;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (vuDataListener != null) {
                    vuDataListener.onVuDataUpdated(currentBands, currentMic);
                }
            });
        }

        // Transmissão direta sem alocar novas Threads
        try {
            StringBuilder sb = new StringBuilder(64);
            sb.append("{\"l\":").append(volumeLeft)
                    .append(",\"r\":").append(volumeRight)
                    .append(",\"b\":[");
            for (int i = 0; i < bands.length; i++) {
                sb.append(bands[i]);
                if (i < bands.length - 1) sb.append(",");
            }
            sb.append("],\"m\":").append(volumeMic).append("}\n");

            outputStream.write(sb.toString().getBytes());
            outputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, "Falha no envio Bluetooth: " + e.getMessage());
            stopSelf();
        }
    }

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "ESP32 VU Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.createNotificationChannel(channel);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isConnected = false;
        isRecording = false;

        if (btHandler != null) {
            btHandler.removeCallbacks(sendRunnable);
        }
        if (btWorkerThread != null) {
            btWorkerThread.quitSafely();
        }

        try {
            if (mediaProjection != null) {
                mediaProjection.stop();
            }
            if (systemAudioRecord != null) {
                systemAudioRecord.stop();
                systemAudioRecord.release();
            }
            if (micAudioRecord != null) {
                micAudioRecord.stop();
                micAudioRecord.release();
            }
            if (outputStream != null) outputStream.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}