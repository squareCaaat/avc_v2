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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private static final long COMMAND_INTERVAL_MS = 100L;
    private static final long RECONNECT_DELAY_MS = 2000L;
    private static final long WEBSOCKET_RECONNECT_DELAY_MS = 3000L;
    private static final int PERMISSION_REQUEST_CODE = 1201;
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String WEBSOCKET_URL = "ws://localhost:8080/ws";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Runnable> repeaters = new SparseArray<>();

    private TextView deviceStatusText;
    private TextView statusArmText;
    private TextView statusTiltText;
    private TextView statusBox3Text;
    private TextView statusBox4Text;
    
    private String firstPinNumber = null;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread readerThread;

    private String lastDeviceAddress;
    private boolean reconnectRequested = false;

    private OkHttpClient webSocketClient;
    private WebSocket webSocket;
    private boolean webSocketReconnectEnabled = true;

    private final ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private final HashSet<String> discoveredAddresses = new HashSet<>();
    private ArrayAdapter<String> deviceListAdapter;
    private AlertDialog deviceDialog;

    private final Runnable reconnectRunnable = () -> {
        if (lastDeviceAddress != null) {
            connectToAddress(lastDeviceAddress);
        }
    };

    private final Runnable webSocketReconnectRunnable = this::startWebSocket;

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
                    if (deviceListAdapter != null && deviceListAdapter.isEmpty()) {
                        deviceListAdapter.add("No device found");
                    }
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
        setupWebSocket();
    }

    private void setupTopBar() {
        Button searchButton = findViewById(R.id.btn_search);
        Button shutdownButton = findViewById(R.id.btn_shutdown);
        deviceStatusText = findViewById(R.id.tv_device_status);

        applyClickEffect(searchButton);
        applyClickEffect(shutdownButton);

        searchButton.setOnClickListener(v -> startDiscovery());
        shutdownButton.setOnClickListener(v -> {
            sendCommand("Q");
            disconnect("Disconnected");
        });
    }

    private void applyClickEffect(View button) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animateButton(v, true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateButton(v, false);
                    break;
            }
            return false;
        });
    }

    private void animateButton(View v, boolean pressed) {
        if (pressed) {
            v.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.7f).setDuration(100).start();
        } else {
            v.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(100).start();
        }
    }

    private void setupControlButtons() {
        bindRepeatingButton(R.id.btn_arm_up, "K", false);
        bindRepeatingButton(R.id.btn_arm_down, "J", false);
        bindRepeatingButton(R.id.btn_arm_left, "H", false);
        bindRepeatingButton(R.id.btn_arm_right, "L", false);

        bindRepeatingButton(R.id.btn_link1_up, "R", false);
        bindRepeatingButton(R.id.btn_link1_down, "T", false);

        bindRepeatingButton(R.id.btn_link2_up, "Y", false);
        bindRepeatingButton(R.id.btn_link2_down, "U", false);

        bindRepeatingButton(R.id.btn_grab, "I", false);
        bindRepeatingButton(R.id.btn_release, "O", false);

        bindRepeatingButton(R.id.btn_car_forward, "W", false);
        bindRepeatingButton(R.id.btn_car_left, "A", false);
        bindRepeatingButton(R.id.btn_car_right, "D", false);
        bindRepeatingButton(R.id.btn_car_backward, "S", false);
    }

    private void setupStatusBoxes() {
        statusArmText = findViewById(R.id.tv_status_box1);
        statusTiltText = findViewById(R.id.tv_status_box2);
        statusBox3Text = findViewById(R.id.tv_status_box3);
        statusBox4Text = findViewById(R.id.tv_status_box4);
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
                    animateButton(v, true);
                    handler.removeCallbacks(repeater);
                    handler.post(repeater);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateButton(v, false);
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
            outputStream.write(command.getBytes(StandardCharsets.UTF_8));
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
        updateDeviceStatus("Searching...");
        showDeviceDialog();
        refreshDeviceList();
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
    }

    private void handleDeviceFound(BluetoothDevice device) {
        if (connectedDevice != null) {
            return;
        }
        addDeviceToList(device);
    }

    private void showDeviceDialog() {
        if (deviceListAdapter == null) {
            deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        }
        if (deviceDialog == null) {
            deviceDialog = new AlertDialog.Builder(this)
                    .setTitle("Bluetooth Devices")
                    .setAdapter(deviceListAdapter, (dialog, which) -> {
                        if (which >= 0 && which < discoveredDevices.size()) {
                            BluetoothDevice device = discoveredDevices.get(which);
                            connectToDevice(device);
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                            bluetoothAdapter.cancelDiscovery();
                        }
                    })
                    .create();
        }
        if (!deviceDialog.isShowing()) {
            deviceDialog.show();
        }
    }

    @SuppressLint("MissingPermission")
    private void refreshDeviceList() {
        discoveredDevices.clear();
        discoveredAddresses.clear();
        deviceListAdapter.clear();

        if (!hasBluetoothPermissions()) {
            return;
        }
        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice device : bonded) {
                addDeviceToList(device);
            }
        }
        if (deviceListAdapter.isEmpty()) {
            deviceListAdapter.add("Searching...");
        }
    }

    private void addDeviceToList(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        String address = device.getAddress();
        if (address == null || discoveredAddresses.contains(address)) {
            return;
        }
        discoveredAddresses.add(address);
        discoveredDevices.add(device);
        String label = safeName(device) + " (" + address + ")";
        if (deviceListAdapter != null) {
            if (deviceListAdapter.getCount() == 1 &&
                    "Searching...".contentEquals(deviceListAdapter.getItem(0))) {
                deviceListAdapter.clear();
            }
            deviceListAdapter.add(label);
            deviceListAdapter.notifyDataSetChanged();
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
        handler.post(() -> {
            if (deviceDialog != null && deviceDialog.isShowing()) {
                deviceDialog.dismiss();
            }
        });
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
        sendArduinoTelemetry(payload);
    }

    private void updateStatusBoxes(String payload) {
        String normalized = payload.trim();
        if (normalized.isEmpty()) {
            return;
        }
        
        String[] parts = normalized.split(":");
        if (parts.length < 6) {
            return;
        }
        
        String pinNumber = parts[0];
        String formattedStatus = formatMotorStatus(parts);
        
        // 첫 번째로 도착한 핀 번호는 box3에, 다른 핀 번호는 box4에 표시
        if (firstPinNumber == null) {
            firstPinNumber = pinNumber;
        }
        
        if (pinNumber.equals(firstPinNumber)) {
            statusBox3Text.setText(formattedStatus);
        } else {
            statusBox4Text.setText(formattedStatus);
        }
    }

    private String formatMotorStatus(String[] parts) {
        return "Pin:" + parts[0] + "\n" +
                "Pulse:" + parts[1] + "\n" +
                "Target:" + parts[2] + "\n" +
                "PWM:" + parts[3] + "\n" +
                "Dir:" + parts[4] + ";BK:" + parts[5];
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

    private void setupWebSocket() {
        if (webSocketClient == null) {
            webSocketClient = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .build();
        }
        startWebSocket();
    }

    private void startWebSocket() {
        if (!webSocketReconnectEnabled) {
            return;
        }
        if (webSocket != null) {
            return;
        }
        Request request = new Request.Builder()
                .url(WEBSOCKET_URL)
                .build();
        webSocket = webSocketClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                android.util.Log.d("WebSocket", "Connected");
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                handleServerCommand(text);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket,
                                  @NonNull Throwable t,
                                  Response response) {
                android.util.Log.d("WebSocket", "Failure: " + t.getMessage());
                cleanupWebSocket();
                scheduleWebSocketReconnect();
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                android.util.Log.d("WebSocket", "Closed: " + reason);
                cleanupWebSocket();
                scheduleWebSocketReconnect();
            }
        });
    }

    private void scheduleWebSocketReconnect() {
        if (!webSocketReconnectEnabled) {
            return;
        }
        handler.removeCallbacks(webSocketReconnectRunnable);
        handler.postDelayed(webSocketReconnectRunnable, WEBSOCKET_RECONNECT_DELAY_MS);
    }

    private void cleanupWebSocket() {
        if (webSocket != null) {
            webSocket.cancel();
            webSocket = null;
        }
    }

    private void shutdownWebSocket() {
        webSocketReconnectEnabled = false;
        handler.removeCallbacks(webSocketReconnectRunnable);
        if (webSocket != null) {
            webSocket.close(1000, "app closed");
            webSocket = null;
        }
        if (webSocketClient != null) {
            webSocketClient.dispatcher().executorService().shutdown();
        }
    }

    private void sendArduinoTelemetry(String payload) {
        String data = payload.trim();
        if (data.isEmpty()) {
            return;
        }
        String payloadWithNewline = data + "\n";
        String message = buildTelemetryMessage(payloadWithNewline);
        sendWebSocketMessage(message);
    }

    private String buildTelemetryMessage(String payload) {
        long timestamp = System.currentTimeMillis();
        return "{\"timestamp\":" + timestamp + ",\"data\":\"" + escapeJson(payload) + "\"}";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void sendWebSocketMessage(String message) {
        if (webSocket == null) {
            return;
        }
        boolean sent = webSocket.send(message);
        if (!sent) {
            scheduleWebSocketReconnect();
        }
    }

    private void handleServerCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        android.util.Log.d("WebSocket", "Command received: " + command);
        // TODO: 확장 시 명령 파싱 후 블루투스 명령 전송 처리
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
        if (deviceDialog != null) {
            deviceDialog.dismiss();
        }
        disconnect("Disconnected");
        shutdownWebSocket();
    }
}