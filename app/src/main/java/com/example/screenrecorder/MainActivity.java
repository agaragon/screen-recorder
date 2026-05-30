package com.example.screenrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME         = "screen_recorder_prefs";
    private static final String KEY_OUTPUT_URI     = "output_uri";
    private static final String KEY_LAST_RECORDING = "last_recording";
    private static final String AUTHORITY          = "com.example.screenrecorder.fileprovider";

    private MediaProjectionManager projectionManager;
    private boolean isRecording = false;

    private Button   btnToggle;
    private Button   btnShare;
    private TextView tvStatus;
    private TextView tvSaveFolder;
    private TextView tvActiveVideo;

    // -----------------------------------------------------------------------
    // Launchers
    // -----------------------------------------------------------------------

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null)
                            startRecordingService(result.getResultCode(), result.getData());
                        else
                            Toast.makeText(this, "Screen capture permission denied.",
                                    Toast.LENGTH_SHORT).show();
                    });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> checkPermissionsAndStartRecording());

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (!granted)
                            Toast.makeText(this,
                                    "Microphone permission denied — audio will not be recorded.",
                                    Toast.LENGTH_LONG).show();
                        requestScreenCapturePermission();
                    });

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

    // Opens the system video picker; the selected video becomes the active video
    private final ActivityResultLauncher<String> videoPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        // Best-effort persistable permission (works for SAF URIs)
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) {}
                        prefs().edit().putString(KEY_LAST_RECORDING, uri.toString()).apply();
                        updateActiveVideoLabel();
                        updateShareButton();
                    });

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        btnToggle    = findViewById(R.id.btnToggle);
        btnShare     = findViewById(R.id.btnShare);
        tvStatus     = findViewById(R.id.tvStatus);
        tvSaveFolder = findViewById(R.id.tvSaveFolder);
        tvActiveVideo = findViewById(R.id.tvActiveVideo);

        btnToggle.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else             checkPermissionsAndStartRecording();
        });

        btnShare.setOnClickListener(v -> shareLastRecording());

        findViewById(R.id.btnChooseVideo).setOnClickListener(v ->
                videoPickerLauncher.launch("video/*"));

        findViewById(R.id.btnChooseFolder).setOnClickListener(v -> {
            String saved = prefs().getString(KEY_OUTPUT_URI, null);
            folderPickerLauncher.launch(saved != null ? Uri.parse(saved) : null);
        });

        updateFolderLabel();
        updateActiveVideoLabel();
        updateShareButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShareButton();
    }

    // -----------------------------------------------------------------------
    // Permission flow: notifications → microphone → screen capture
    // -----------------------------------------------------------------------

    private void checkPermissionsAndStartRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
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
        DisplayMetrics metrics = getResources().getDisplayMetrics();

        Intent si = new Intent(this, ScreenRecorderService.class);
        si.setAction(ScreenRecorderService.ACTION_START);
        si.putExtra(ScreenRecorderService.EXTRA_RESULT_CODE,   resultCode);
        si.putExtra(ScreenRecorderService.EXTRA_RESULT_DATA,   data);
        si.putExtra(ScreenRecorderService.EXTRA_SCREEN_WIDTH,  metrics.widthPixels);
        si.putExtra(ScreenRecorderService.EXTRA_SCREEN_HEIGHT, metrics.heightPixels);
        si.putExtra(ScreenRecorderService.EXTRA_SCREEN_DPI,    metrics.densityDpi);

        String outputUri = prefs().getString(KEY_OUTPUT_URI, null);
        if (outputUri != null) si.putExtra(ScreenRecorderService.EXTRA_OUTPUT_URI, outputUri);

        ContextCompat.startForegroundService(this, si);
        isRecording = true;
        updateUI();
    }

    private void stopRecording() {
        Intent si = new Intent(this, ScreenRecorderService.class);
        si.setAction(ScreenRecorderService.ACTION_STOP);
        startService(si);
        isRecording = false;
        // The service saves KEY_LAST_RECORDING to prefs; give it a moment then refresh UI
        btnToggle.postDelayed(() -> {
            updateActiveVideoLabel();
            updateShareButton();
        }, 500);
        updateUI();
    }

    // -----------------------------------------------------------------------
    // Instagram Stories sharing
    // -----------------------------------------------------------------------

    private void shareLastRecording() {
        String lastRecording = prefs().getString(KEY_LAST_RECORDING, null);
        if (lastRecording == null) {
            Toast.makeText(this, R.string.no_recording, Toast.LENGTH_SHORT).show();
            return;
        }

        btnShare.setEnabled(false);
        Toast.makeText(this, R.string.preparing_video, Toast.LENGTH_SHORT).show();

        File chunksDir = new File(getExternalCacheDir(), "video_chunks");
        deleteDir(chunksDir);

        new Thread(() -> {
            try {
                List<File> chunks = VideoSplitter.split(
                        this, lastRecording, chunksDir, VideoSplitter.STORY_MAX_US);
                runOnUiThread(() -> {
                    updateShareButton();
                    shareChunks(chunks);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateShareButton();
                    Toast.makeText(this, "Failed to prepare video: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void shareChunks(List<File> chunks) {
        if (chunks.size() == 1) {
            Uri uri = FileProvider.getUriForFile(this, AUTHORITY, chunks.get(0));

            Intent igIntent = new Intent("com.instagram.share.ADD_TO_STORY");
            igIntent.setDataAndType(uri, "video/mp4");
            igIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (getPackageManager().resolveActivity(
                    igIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(igIntent);
            } else {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("video/mp4");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, getString(R.string.share_title)));
            }
        } else {
            Toast.makeText(this,
                    getString(R.string.split_into_parts, chunks.size()),
                    Toast.LENGTH_LONG).show();
            ArrayList<Uri> uris = new ArrayList<>();
            for (File chunk : chunks)
                uris.add(FileProvider.getUriForFile(this, AUTHORITY, chunk));
            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("video/mp4");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.share_title)));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void updateActiveVideoLabel() {
        String pathOrUri = prefs().getString(KEY_LAST_RECORDING, null);
        if (pathOrUri == null) {
            tvActiveVideo.setText(getString(R.string.active_video_none));
        } else {
            tvActiveVideo.setText(getString(R.string.active_video, getDisplayName(pathOrUri)));
        }
    }

    /** Returns a human-readable filename for a file path or content URI. */
    private String getDisplayName(String pathOrUri) {
        if (pathOrUri.startsWith("content://")) {
            try (Cursor c = getContentResolver().query(
                    Uri.parse(pathOrUri),
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (Exception ignored) {}
            return Uri.parse(pathOrUri).getLastPathSegment();
        }
        return new File(pathOrUri).getName();
    }

    private void updateFolderLabel() {
        String uriString = prefs().getString(KEY_OUTPUT_URI, null);
        if (uriString == null)
            tvSaveFolder.setText(getString(R.string.save_folder_default));
        else {
            String seg = Uri.parse(uriString).getLastPathSegment();
            tvSaveFolder.setText(getString(R.string.save_folder_custom, seg));
        }
    }

    private void updateShareButton() {
        boolean hasRecording = prefs().getString(KEY_LAST_RECORDING, null) != null;
        btnShare.setEnabled(hasRecording && !isRecording);
    }

    private void updateUI() {
        tvStatus.setText(isRecording ? R.string.status_recording : R.string.status_idle);
        btnToggle.setText(isRecording ? R.string.stop_recording  : R.string.start_recording);
        updateShareButton();
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) deleteDir(child);
        dir.delete();
    }
}
