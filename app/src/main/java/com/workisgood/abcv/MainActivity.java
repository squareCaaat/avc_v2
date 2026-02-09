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
import java.util.concurrent.TimeUnit;

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
    
    private static String getWebSocketUrl() {
        String host = BuildConfig.WEBSOCKET_HOST;
        if (host.startsWith("https://")) {
            return "wss://" + host.substring(8) + "/ws";
        } else if (host.startsWith("http://")) {
            return "ws://" + host.substring(7) + "/ws";
        }
        return "wss://" + host + "/ws";
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Runnable> repeaters = new SparseArray<>();

    private TextView deviceStatusText;
    private TextView statusArmText;      // box1 - ARM 정보
    private TextView statusSteerText;    // box2 - 조향 정보
    private TextView statusMotor1Text;   // box3 - 모터1 정보
    private TextView statusMotor2Text;   // box4 - 모터2 정보
    
    private String firstMotorPin = null;

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
        statusSteerText = findViewById(R.id.tv_status_box2);
        statusMotor1Text = findViewById(R.id.tv_status_box3);
        statusMotor2Text = findViewById(R.id.tv_status_box4);
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
        sendBluetoothStatus(true);
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
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        handler.post(() -> updateStatusBoxes(trimmed));
        sendArduinoTelemetry(trimmed);
    }

    // ── UI 표시 ──────────────────────────────────────────────

    private void updateStatusBoxes(String payload) {
        if (payload.length() < 2 || payload.charAt(1) != ':') {
            return;
        }
        char header = payload.charAt(0);
        String data = payload.substring(2);
        String[] parts = data.split(":");

        switch (header) {
            case 'm':
                updateMotorDisplay(parts);
                break;
            case 's':
                updateSteeringDisplay(parts);
                break;
            case 'a':
                updateArmDisplay(parts);
                break;
            default:
                android.util.Log.w("Parse", "Unknown header: " + header);
                break;
        }
    }

    private void updateMotorDisplay(String[] parts) {
        // m:scPin:targetThrottle:activeSpeed:pulseCount:pwmOut
        if (parts.length < 5) return;
        String pin = parts[0];
        String display = "Pin:" + pin
                + "\nTarget:" + parts[1]
                + "\nSpeed:" + parts[2]
                + "\nPulse:" + parts[3]
                + "\nPWM:" + parts[4];

        if (firstMotorPin == null) {
            firstMotorPin = pin;
        }
        if (pin.equals(firstMotorPin)) {
            statusMotor1Text.setText(display);
        } else {
            statusMotor2Text.setText(display);
        }
    }

    private void updateSteeringDisplay(String[] parts) {
        // s:currentAngle:targetAngle
        if (parts.length < 2) return;
        statusSteerText.setText("Cur:" + parts[0] + "\nTgt:" + parts[1]);
    }

    private void updateArmDisplay(String[] parts) {
        // a:curBottom:tgtBottom:curL1:tgtL1:curL2:tgtL2:curGrip:tgtGrip
        if (parts.length < 8) return;
        String display = "Base:" + parts[0] + "/" + parts[1]
                + " L1:" + parts[2] + "/" + parts[3]
                + "\nL2:" + parts[4] + "/" + parts[5]
                + " Grip:" + parts[6] + "/" + parts[7];
        statusArmText.setText(display);
    }

    // ── JSON 생성 & WebSocket 전송 ──────────────────────────

    private void sendArduinoTelemetry(String payload) {
        if (payload.length() < 2 || payload.charAt(1) != ':') {
            return;
        }
        char header = payload.charAt(0);
        String data = payload.substring(2);
        String[] parts = data.split(":");
        String json = null;

        switch (header) {
            case 'm':
                json = buildMotorJson(parts);
                break;
            case 's':
                json = buildSteeringJson(parts);
                break;
            case 'a':
                json = buildArmJson(parts);
                break;
        }
        if (json != null) {
            sendWebSocketMessage(json);
        }
    }

    private String buildMotorJson(String[] p) {
        // m:scPin:targetThrottle:activeSpeed:pulseCount:pwmOut
        if (p.length < 5) return null;
        long ts = System.currentTimeMillis();
        return "{\"timestamp\":" + ts
                + ",\"type\":\"motor\""
                + ",\"data\":{\"scPin\":" + p[0]
                + ",\"targetThrottle\":" + p[1]
                + ",\"activeSpeed\":" + p[2]
                + ",\"pulseCount\":" + p[3]
                + ",\"pwmOut\":" + p[4]
                + "}}";
    }

    private String buildSteeringJson(String[] p) {
        // s:currentAngle:targetAngle
        if (p.length < 2) return null;
        long ts = System.currentTimeMillis();
        return "{\"timestamp\":" + ts
                + ",\"type\":\"steering\""
                + ",\"data\":{\"currentAngle\":" + p[0]
                + ",\"targetAngle\":" + p[1]
                + "}}";
    }

    private String buildArmJson(String[] p) {
        // a:curBottom:tgtBottom:curL1:tgtL1:curL2:tgtL2:curGrip:tgtGrip
        if (p.length < 8) return null;
        long ts = System.currentTimeMillis();
        return "{\"timestamp\":" + ts
                + ",\"type\":\"arm\""
                + ",\"data\":{"
                + "\"armBottom\":{\"current\":" + p[0] + ",\"target\":" + p[1] + "}"
                + ",\"linkOne\":{\"current\":" + p[2] + ",\"target\":" + p[3] + "}"
                + ",\"linkTwo\":{\"current\":" + p[4] + ",\"target\":" + p[5] + "}"
                + ",\"gripper\":{\"current\":" + p[6] + ",\"target\":" + p[7] + "}"
                + "}}";
    }

    private void handleConnectionLost() {
        sendBluetoothStatus(false);
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
        sendBluetoothStatus(false);
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
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
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
                .url(getWebSocketUrl())
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

    private void sendBluetoothStatus(boolean connected) {
        String json = "{\"type\":\"bluetooth\",\"data\":{\"connected\":" + connected + "}}";
        sendWebSocketMessage(json);
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