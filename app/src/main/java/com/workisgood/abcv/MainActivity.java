package com.workisgood.abcv;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private static final long COMMAND_INTERVAL_MS = 100L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Runnable> repeaters = new SparseArray<>();

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
    }

    private void setupTopBar() {
        Button searchButton = findViewById(R.id.btn_search);
        Button shutdownButton = findViewById(R.id.btn_shutdown);
        TextView statusText = findViewById(R.id.tv_device_status);

        searchButton.setOnClickListener(v -> statusText.setText("Searching..."));
        shutdownButton.setOnClickListener(v -> statusText.setText("Disconnected"));
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
        // TODO: Bluetooth 송신 로직 연결
        android.util.Log.d("Command", "Send: " + command);
    }
}