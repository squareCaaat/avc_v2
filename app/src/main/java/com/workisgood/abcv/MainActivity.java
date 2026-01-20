package com.workisgood.abcv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final long COMMAND_INTERVAL_MS = 100L;
    private static final long RECONNECT_DELAY_MS = 2000L;
    private static final int PERMISSION_REQUEST_CODE = 1201;
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Runnable> repeaters = new SparseArray<>();

    private TextView deviceStatusText;
    private TextView statusArmText;
    private TextView statusTiltText;
    private TextView statusSpeedText;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread readerThread;

    private String lastDeviceAddress;
    private boolean reconnectRequested = false;

    private final Runnable reconnectRunnable = () -> {
        if (lastDeviceAddress != null) {
            connectToAddress(lastDeviceAddress);
        }
    };

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    handleDeviceFound(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (connectedDevice == null) {
                    updateDeviceStatus("No device found");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setupTopBar();
        setupControlButtons();
        setupStatusBoxes();
        setupBluetooth();
    }

    private void setupTopBar() {
        Button searchButton = findViewById(R.id.btn_search);
        Button shutdownButton = findViewById(R.id.btn_shutdown);
        deviceStatusText = findViewById(R.id.tv_device_status);

        searchButton.setOnClickListener(v -> startDiscovery());
        shutdownButton.setOnClickListener(v -> disconnect("Disconnected"));
    }

    private void setupControlButtons() {
        bindRepeatingButton(R.id.btn_arm_up, "AU", false);
        bindRepeatingButton(R.id.btn_arm_down, "AD", false);
        bindRepeatingButton(R.id.btn_arm_left, "AL", false);
        bindRepeatingButton(R.id.btn_arm_right, "AR", false);

        bindRepeatingButton(R.id.btn_link1_up, "L1U", false);
        bindRepeatingButton(R.id.btn_link1_down, "L1D", false);

        bindRepeatingButton(R.id.btn_link2_up, "L2U", false);
        bindRepeatingButton(R.id.btn_link2_down, "L2D", false);

        bindRepeatingButton(R.id.btn_grab, "G", false);
        bindRepeatingButton(R.id.btn_release, "GR", false);

        bindRepeatingButton(R.id.btn_car_forward, "F", true);
        bindRepeatingButton(R.id.btn_car_left, "L", true);
        bindRepeatingButton(R.id.btn_car_right, "R", true);
        bindRepeatingButton(R.id.btn_car_backward, "B", true);
    }

    private void setupStatusBoxes() {
        statusArmText = findViewById(R.id.tv_status_box1);
        statusTiltText = findViewById(R.id.tv_status_box2);
        statusSpeedText = findViewById(R.id.tv_status_box3);
    }

    private void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        SharedPreferences prefs = getSharedPreferences("bt_prefs", MODE_PRIVATE);
        lastDeviceAddress = prefs.getString("last_device_address", null);
        registerDiscoveryReceiver();
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
        }
    }

    private void bindRepeatingButton(int buttonId, String command, boolean sendStopOnRelease) {
        View button = findViewById(buttonId);
        Runnable repeater = new Runnable() {
            @Override
            public void run() {
                sendCommand(command);
                handler.postDelayed(this, COMMAND_INTERVAL_MS);
            }
        };
        repeaters.put(buttonId, repeater);

        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(repeater);
                    handler.post(repeater);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(repeater);
                    if (sendStopOnRelease) {
                        sendCommand("S");
                    }
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void sendCommand(String command) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException e) {
            handleConnectionLost();
        }
        android.util.Log.d("Command", "Send: " + command);
    }

    private void startDiscovery() {
        if (bluetoothAdapter == null) {
            updateDeviceStatus("Bluetooth not supported");
            return;
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            updateDeviceStatus("Enable Bluetooth");
            return;
        }

        reconnectRequested = false;
        if (lastDeviceAddress != null) {
            BluetoothDevice cached = bluetoothAdapter.getRemoteDevice(lastDeviceAddress);
            if (cached != null && cached.getBondState() == BluetoothDevice.BOND_BONDED) {
                connectToDevice(cached);
                return;
            }
        }

        updateDeviceStatus("Searching...");
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
    }

    private void handleDeviceFound(BluetoothDevice device) {
        if (connectedDevice != null) {
            return;
        }
        if (lastDeviceAddress != null && lastDeviceAddress.equals(device.getAddress())) {
            connectToDevice(device);
            return;
        }
        if (device.getName() != null && !device.getName().isEmpty()) {
            connectToDevice(device);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        updateDeviceStatus("Connecting " + safeName(device));
        bluetoothAdapter.cancelDiscovery();
        closeSocket();

        new Thread(() -> {
            try {
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                onConnected(device, socket);
            } catch (IOException e) {
                handler.post(() -> updateDeviceStatus("Connect failed"));
                scheduleReconnect();
            }
        }).start();
    }

    private void connectToAddress(String address) {
        if (bluetoothAdapter == null || address == null) {
            return;
        }
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device != null) {
            connectToDevice(device);
        }
    }

    private void onConnected(BluetoothDevice device, BluetoothSocket socket) throws IOException {
        connectedDevice = device;
        bluetoothSocket = socket;
        outputStream = socket.getOutputStream();
        inputStream = socket.getInputStream();
        saveLastDevice(device.getAddress());
        handler.post(() -> updateDeviceStatus("Connected: " + safeName(device)));
        startReaderThread();
    }

    private void startReaderThread() {
        stopReaderThread();
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[256];
            int bytes;
            StringBuilder builder = new StringBuilder();
            try {
                while (!Thread.currentThread().isInterrupted() && inputStream != null) {
                    bytes = inputStream.read(buffer);
                    if (bytes == -1) {
                        break;
                    }
                    builder.append(new String(buffer, 0, bytes, StandardCharsets.UTF_8));
                    int newlineIndex;
                    while ((newlineIndex = builder.indexOf("\n")) >= 0) {
                        String line = builder.substring(0, newlineIndex).trim();
                        builder.delete(0, newlineIndex + 1);
                        if (!line.isEmpty()) {
                            handleIncoming(line);
                        }
                    }
                }
            } catch (IOException e) {
                handleConnectionLost();
            }
        });
        readerThread.start();
    }

    private void handleIncoming(String payload) {
        handler.post(() -> updateStatusBoxes(payload));
    }

    private void updateStatusBoxes(String payload) {
        String normalized = payload.trim();
        if (normalized.isEmpty()) {
            return;
        }
        String upper = normalized.toUpperCase();

        // TODO: 수신 데이터 처리
        // if (upper.startsWith("ARM")) {
        //     statusArmText.setText("ARM: " + extractValue(normalized));
        // }
    }

    private String extractValue(String payload) {
        int idx = payload.indexOf(':');
        if (idx < 0) {
            idx = payload.indexOf('=');
        }
        if (idx >= 0 && idx + 1 < payload.length()) {
            return payload.substring(idx + 1).trim();
        }
        return payload.trim();
    }

    private void handleConnectionLost() {
        handler.post(() -> updateDeviceStatus("Disconnected"));
        closeSocket();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (reconnectRequested) {
            return;
        }
        reconnectRequested = true;
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void disconnect(String reason) {
        reconnectRequested = false;
        handler.removeCallbacks(reconnectRunnable);
        closeSocket();
        updateDeviceStatus(reason);
    }

    private void updateDeviceStatus(String status) {
        if (deviceStatusText != null) {
            deviceStatusText.setText(status);
        }
    }

    private void closeSocket() {
        stopReaderThread();
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException ignored) {
        }
        inputStream = null;
        outputStream = null;
        bluetoothSocket = null;
        connectedDevice = null;
    }

    private void stopReaderThread() {
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    private void saveLastDevice(String address) {
        lastDeviceAddress = address;
        SharedPreferences prefs = getSharedPreferences("bt_prefs", MODE_PRIVATE);
        prefs.edit().putString("last_device_address", address).apply();
    }

    private String safeName(BluetoothDevice device) {
        String name = device.getName();
        return (name == null || name.isEmpty()) ? device.getAddress() : name;
    }

    private void registerDiscoveryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
    }

    private boolean hasBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[] {
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    PERMISSION_REQUEST_CODE
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasBluetoothPermissions()) {
                startDiscovery();
            } else {
                updateDeviceStatus("Permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        disconnect("Disconnected");
    }
}