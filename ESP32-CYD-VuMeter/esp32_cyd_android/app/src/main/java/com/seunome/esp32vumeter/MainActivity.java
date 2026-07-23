package com.seunome.esp32vumeter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements VUService.OnVuDataListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 1001;
    private static final String TARGET_DEVICE_NAME = "ESP32-VU-Meter";

    private Button btnConnect;
    private TextView txtStatus;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice esp32Device = null;
    private boolean isServiceRunning = false;

    private ActivityResultLauncher<Intent> mediaProjectionLauncher;
    private VuMeterView vuMeterView;

    // Comunicação direta por memória via Binder
    private VUService vuService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VUService.LocalBinder binder = (VUService.LocalBinder) service;
            vuService = binder.getService();
            if (vuService != null) {
                vuService.setOnVuDataListener(MainActivity.this); // Assina a atualização
            }
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            vuService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vuMeterView = findViewById(R.id.vuMeterView);
        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        setupMediaProjectionLauncher();

        btnConnect.setOnClickListener(v -> {
            if (!isServiceRunning) {
                if (hasAllPermissions()) {
                    requestMediaProjectionPermission();
                } else {
                    requestAppPermissions();
                }
            } else {
                stopVUService();
            }
        });

        if (!hasAllPermissions()) {
            requestAppPermissions();
        } else {
            findEsp32Device();
        }
    }

    // CORREÇÃO CRÍTICA 1: Força a atualização da CustomView na UI Thread
    @Override
    public void onVuDataUpdated(int[] bands, float mic) {
        runOnUiThread(() -> {
            if (vuMeterView != null) {
                vuMeterView.updateValues(bands, mic);
            }
        });
    }

    private void setupMediaProjectionLauncher() {
        mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        startVUServiceWithProjection(result.getResultCode(), result.getData());
                    } else {
                        Toast.makeText(this, "Permissão de gravação de áudio do sistema negada!", Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void requestMediaProjectionPermission() {
        if (esp32Device == null) {
            findEsp32Device();
        }

        if (esp32Device == null) {
            Toast.makeText(this, "Pareie o " + TARGET_DEVICE_NAME + " no Bluetooth antes de conectar!", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent());
            }
        } else {
            startVUServiceWithProjection(Activity.RESULT_OK, null);
        }
    }

    private void startVUServiceWithProjection(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, VUService.class);
        serviceIntent.putExtra("DEVICE_ADDRESS", esp32Device.getAddress());
        serviceIntent.putExtra("MEDIA_PROJECTION_RESULT_CODE", resultCode);
        if (data != null) {
            serviceIntent.putExtra("MEDIA_PROJECTION_DATA", data);
        }

        try {
            // CORREÇÃO CRÍTICA 2: Iniciação segura do serviço
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // Conecta a Activity ao Serviço de forma protegida
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

            isServiceRunning = true;
            btnConnect.setText("Desconectar / Parar Background");
            updateStatus("Conectando ao " + TARGET_DEVICE_NAME + "...");
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar VUService: " + e.getMessage());
            Toast.makeText(this, "Erro ao iniciar serviço: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasAllPermissions() {
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        boolean btConnect = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return mic && btConnect;
    }

    private void requestAppPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQ_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQ_CODE) {
            if (hasAllPermissions()) {
                findEsp32Device();
            } else {
                Toast.makeText(this, "Permissões de Microfone e Bluetooth são OBRIGATÓRIAS!", Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void findEsp32Device() {
        if (bluetoothAdapter == null) {
            updateStatus("Bluetooth não suportado neste dispositivo.");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            updateStatus("Ative o Bluetooth do celular!");
            Toast.makeText(this, "Por favor, ative o Bluetooth!", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            esp32Device = null;

            if (pairedDevices != null) {
                for (BluetoothDevice device : pairedDevices) {
                    if (TARGET_DEVICE_NAME.equals(device.getName())) {
                        esp32Device = device;
                        break;
                    }
                }
            }

            if (esp32Device != null) {
                updateStatus("ESP32 Encontrado!\n" + esp32Device.getAddress());
            } else {
                updateStatus("ESP32-VU-Meter NÃO pareado!\nPareie nas configurações do Android.");
            }

        } catch (SecurityException e) {
            updateStatus("Erro de permissão no Bluetooth!");
        }
    }

    private void stopVUService() {
        if (isBound) {
            if (vuService != null) {
                vuService.setOnVuDataListener(null);
            }
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            isBound = false;
        }

        Intent serviceIntent = new Intent(this, VUService.class);
        stopService(serviceIntent);

        isServiceRunning = false;
        btnConnect.setText("Conectar");
        updateStatus("Desconectado.");

        if (vuMeterView != null) {
            vuMeterView.updateValues(new int[8], 0f);
        }

        Toast.makeText(this, "Serviço parado!", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            if (vuService != null) {
                vuService.setOnVuDataListener(null);
            }
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            isBound = false;
        }
    }

    private void updateStatus(String msg) {
        if (txtStatus != null) {
            txtStatus.setText(msg);
        }
    }
}