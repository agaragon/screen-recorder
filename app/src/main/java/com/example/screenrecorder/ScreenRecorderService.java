package com.example.screenrecorder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecorderService extends Service {

    private static final String TAG = "ScreenRecorderService";
    private static final int    NOTIFICATION_ID = 1;
    private static final String PREFS_NAME      = "screen_recorder_prefs";
    private static final String KEY_LAST_RECORDING = "last_recording";

    public static final String ACTION_START = "com.example.screenrecorder.START";
    public static final String ACTION_STOP  = "com.example.screenrecorder.STOP";

    public static final String EXTRA_RESULT_CODE   = "result_code";
    public static final String EXTRA_RESULT_DATA   = "result_data";
    public static final String EXTRA_SCREEN_WIDTH  = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_SCREEN_DPI    = "screen_dpi";
    public static final String EXTRA_OUTPUT_URI    = "output_uri";

    private MediaProjectionManager projectionManager;
    private MediaProjection         mediaProjection;
    private VirtualDisplay          virtualDisplay;
    private MediaRecorder           mediaRecorder;
    private ParcelFileDescriptor    outputPfd;
    private String                  pendingOutputPath;

    private int screenWidth;
    private int screenHeight;
    private int screenDpi;

    private final MediaProjection.Callback projectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection stopped externally");
                    stopRecording();
                    stopForeground(true);
                    stopSelf();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        switch (intent.getAction() != null ? intent.getAction() : "") {
            case ACTION_START:
                startForeground(NOTIFICATION_ID, buildNotification());

                screenWidth  = alignTo16(intent.getIntExtra(EXTRA_SCREEN_WIDTH,  1080));
                screenHeight = alignTo16(intent.getIntExtra(EXTRA_SCREEN_HEIGHT, 1920));
                screenDpi    = intent.getIntExtra(EXTRA_SCREEN_DPI, 320);

                int    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                mediaProjection   = projectionManager.getMediaProjection(resultCode, resultData);

                if (mediaProjection == null) {
                    Log.e(TAG, "getMediaProjection returned null — aborting");
                    stopSelf();
                    return START_NOT_STICKY;
                }

                mediaProjection.registerCallback(
                        projectionCallback, new Handler(Looper.getMainLooper()));

                startRecording(intent.getStringExtra(EXTRA_OUTPUT_URI));
                break;

            case ACTION_STOP:
                stopRecording();
                stopForeground(true);
                stopSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() { stopRecording(); super.onDestroy(); }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    private void startRecording(String outputUriString) {
        mediaRecorder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? new MediaRecorder(this) : new MediaRecorder();

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        if (outputUriString != null) {
            try {
                Uri treeUri = Uri.parse(outputUriString);
                Uri docUri  = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                String ts   = timestamp();
                Uri fileUri = DocumentsContract.createDocument(
                        getContentResolver(), docUri, "video/mp4", "recording_" + ts + ".mp4");
                if (fileUri != null) {
                    outputPfd = getContentResolver().openFileDescriptor(fileUri, "w");
                    mediaRecorder.setOutputFile(outputPfd.getFileDescriptor());
                    pendingOutputPath = fileUri.toString();
                } else {
                    String path = buildOutputFilePath();
                    mediaRecorder.setOutputFile(path);
                    pendingOutputPath = path;
                }
            } catch (Exception e) {
                Log.e(TAG, "SAF output failed, falling back to Movies dir", e);
                closeOutputPfd();
                String path = buildOutputFilePath();
                mediaRecorder.setOutputFile(path);
                pendingOutputPath = path;
            }
        } else {
            String path = buildOutputFilePath();
            mediaRecorder.setOutputFile(path);
            pendingOutputPath = path;
        }

        mediaRecorder.setVideoSize(screenWidth, screenHeight);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(128 * 1024);
        mediaRecorder.setAudioSamplingRate(44100);

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed", e);
            closeOutputPfd();
            pendingOutputPath = null;
            return;
        }

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder", screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(), null, null);

        mediaRecorder.start();
        Log.i(TAG, "Recording started at " + screenWidth + "x" + screenHeight);
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                if (pendingOutputPath != null) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putString(KEY_LAST_RECORDING, pendingOutputPath).apply();
                    Log.i(TAG, "Saved last recording: " + pendingOutputPath);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "stop() failed", e);
            }
            pendingOutputPath = null;
            mediaRecorder.release();
            mediaRecorder = null;
        }

        closeOutputPfd();

        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }

        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(projectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
        Log.i(TAG, "Recording stopped");
    }

    private void closeOutputPfd() {
        if (outputPfd != null) {
            try { outputPfd.close(); } catch (IOException e) { Log.w(TAG, "close pfd", e); }
            outputPfd = null;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int alignTo16(int value) { return (value / 16) * 16; }

    private String buildOutputFilePath() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "recording_" + timestamp() + ".mp4").getAbsolutePath();
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    getString(R.string.channel_id),
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Used while a screen recording is in progress.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, getString(R.string.channel_id))
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }
}
