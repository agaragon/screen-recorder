package com.example.screenrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // Request code used to identify the screen-capture permission result
    private static final int REQUEST_SCREEN_CAPTURE = 100;

    private MediaProjectionManager projectionManager;
    private boolean isRecording = false;

    private Button btnToggle;
    private TextView tvStatus;

    // -----------------------------------------------------------------------
    // Launchers registered before onCreate (AndroidX Activity Result API)
    // -----------------------------------------------------------------------

    // Launcher for the system "Allow display over other apps / capture screen" dialog
    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            startRecordingService(result.getResultCode(), result.getData());
                        } else {
                            Toast.makeText(this,
                                    "Screen capture permission denied.", Toast.LENGTH_SHORT).show();
                        }
                    });

    // Launcher for the POST_NOTIFICATIONS runtime permission (Android 13+)
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        // Permission result is informational; we proceed regardless because
                        // the foreground service can still run without the notification being
                        // visible on very old API levels where POST_NOTIFICATIONS doesn't exist.
                        requestScreenCapturePermission();
                    });

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        btnToggle = findViewById(R.id.btnToggle);
        tvStatus  = findViewById(R.id.tvStatus);

        btnToggle.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                checkPermissionsAndStartRecording();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Permission flow
    // -----------------------------------------------------------------------

    /**
     * Entry point for the start-recording flow.
     * On API 33+ we must hold POST_NOTIFICATIONS before launching the foreground
     * service; on older versions we go straight to the screen-capture dialog.
     */
    private void checkPermissionsAndStartRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        requestScreenCapturePermission();
    }

    /**
     * Shows the system dialog that asks the user to allow this app to capture
     * the screen. The result is delivered to {@link #screenCaptureLauncher}.
     */
    private void requestScreenCapturePermission() {
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }

    // -----------------------------------------------------------------------
    // Service control
    // -----------------------------------------------------------------------

    /**
     * Passes the approved MediaProjection token to the recording service and
     * starts it as a foreground service.
     *
     * @param resultCode RESULT_OK from the screen-capture permission dialog
     * @param data       Intent containing the MediaProjection token
     */
    private void startRecordingService(int resultCode, Intent data) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        Intent serviceIntent = new Intent(this, ScreenRecorderService.class);
        serviceIntent.setAction(ScreenRecorderService.ACTION_START);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_SCREEN_WIDTH,  metrics.widthPixels);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_SCREEN_HEIGHT, metrics.heightPixels);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_SCREEN_DPI,    metrics.densityDpi);

        ContextCompat.startForegroundService(this, serviceIntent);

        isRecording = true;
        updateUI();
    }

    private void stopRecording() {
        Intent serviceIntent = new Intent(this, ScreenRecorderService.class);
        serviceIntent.setAction(ScreenRecorderService.ACTION_STOP);
        startService(serviceIntent);

        isRecording = false;
        updateUI();
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private void updateUI() {
        if (isRecording) {
            tvStatus.setText(R.string.status_recording);
            btnToggle.setText(R.string.stop_recording);
        } else {
            tvStatus.setText(R.string.status_idle);
            btnToggle.setText(R.string.start_recording);
        }
    }
}
