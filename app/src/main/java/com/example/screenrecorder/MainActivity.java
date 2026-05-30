package com.example.screenrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
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

    private static final String PREFS_NAME = "screen_recorder_prefs";
    private static final String KEY_OUTPUT_URI = "output_uri";

    private MediaProjectionManager projectionManager;
    private boolean isRecording = false;

    private Button btnToggle;
    private TextView tvStatus;
    private TextView tvSaveFolder;

    // -----------------------------------------------------------------------
    // Launchers
    // -----------------------------------------------------------------------

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

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> requestScreenCapturePermission());

    private final ActivityResultLauncher<Uri> folderPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) return;
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        prefs().edit().putString(KEY_OUTPUT_URI, uri.toString()).apply();
                        updateFolderLabel();
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

        btnToggle    = findViewById(R.id.btnToggle);
        tvStatus     = findViewById(R.id.tvStatus);
        tvSaveFolder = findViewById(R.id.tvSaveFolder);

        btnToggle.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else             checkPermissionsAndStartRecording();
        });

        findViewById(R.id.btnChooseFolder).setOnClickListener(v -> {
            String saved = prefs().getString(KEY_OUTPUT_URI, null);
            folderPickerLauncher.launch(saved != null ? Uri.parse(saved) : null);
        });

        updateFolderLabel();
    }

    // -----------------------------------------------------------------------
    // Permission flow
    // -----------------------------------------------------------------------

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

    private void requestScreenCapturePermission() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    // -----------------------------------------------------------------------
    // Service control
    // -----------------------------------------------------------------------

    private void startRecordingService(int resultCode, Intent data) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        Intent serviceIntent = new Intent(this, ScreenRecorderService.class);
        serviceIntent.setAction(ScreenRecorderService.ACTION_START);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_RESULT_CODE,   resultCode);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_RESULT_DATA,   data);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_SCREEN_WIDTH,  metrics.widthPixels);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_SCREEN_HEIGHT, metrics.heightPixels);
        serviceIntent.putExtra(ScreenRecorderService.EXTRA_SCREEN_DPI,    metrics.densityDpi);

        String outputUri = prefs().getString(KEY_OUTPUT_URI, null);
        if (outputUri != null) {
            serviceIntent.putExtra(ScreenRecorderService.EXTRA_OUTPUT_URI, outputUri);
        }

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
    // Helpers
    // -----------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void updateFolderLabel() {
        String uriString = prefs().getString(KEY_OUTPUT_URI, null);
        if (uriString == null) {
            tvSaveFolder.setText(getString(R.string.save_folder_default));
        } else {
            String segment = Uri.parse(uriString).getLastPathSegment();
            tvSaveFolder.setText(getString(R.string.save_folder_custom, segment));
        }
    }

    private void updateUI() {
        tvStatus.setText(isRecording ? R.string.status_recording : R.string.status_idle);
        btnToggle.setText(isRecording ? R.string.stop_recording  : R.string.start_recording);
    }
}
