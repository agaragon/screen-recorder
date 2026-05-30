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
import android.os.IBinder;
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
    private static final int NOTIFICATION_ID = 1;

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

    private int screenWidth;
    private int screenHeight;
    private int screenDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        switch (intent.getAction() != null ? intent.getAction() : "") {
            case ACTION_START:
                startForeground(NOTIFICATION_ID, buildNotification());

                // Align to 16 immediately so every downstream user gets consistent values
                screenWidth  = alignTo16(intent.getIntExtra(EXTRA_SCREEN_WIDTH,  1080));
                screenHeight = alignTo16(intent.getIntExtra(EXTRA_SCREEN_HEIGHT, 1920));
                screenDpi    = intent.getIntExtra(EXTRA_SCREEN_DPI, 320);

                int    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                mediaProjection   = projectionManager.getMediaProjection(resultCode, resultData);

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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    private void startRecording(String outputUriString) {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        if (outputUriString != null) {
            try {
                Uri treeUri = Uri.parse(outputUriString);
                Uri docUri  = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                Uri fileUri = DocumentsContract.createDocument(
                        getContentResolver(), docUri, "video/mp4", "recording_" + ts + ".mp4");
                if (fileUri != null) {
                    outputPfd = getContentResolver().openFileDescriptor(fileUri, "w");
                    mediaRecorder.setOutputFile(outputPfd.getFileDescriptor());
                } else {
                    mediaRecorder.setOutputFile(buildOutputFilePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "SAF output failed, falling back to Movies dir", e);
                closeOutputPfd();
                mediaRecorder.setOutputFile(buildOutputFilePath());
            }
        } else {
            mediaRecorder.setOutputFile(buildOutputFilePath());
        }

        // screenWidth/Height are already aligned to 16 in onStartCommand
        mediaRecorder.setVideoSize(screenWidth, screenHeight);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
        mediaRecorder.setVideoFrameRate(30);

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed", e);
            closeOutputPfd();
            return;
        }

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null, null);

        mediaRecorder.start();
        Log.i(TAG, "Recording started at " + screenWidth + "x" + screenHeight);
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); }
            catch (RuntimeException e) { Log.w(TAG, "stop() failed — probably never started", e); }
            mediaRecorder.release();
            mediaRecorder = null;
        }

        closeOutputPfd();

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
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

    /** H.264 hardware encoders require width and height to be multiples of 16. */
    private static int alignTo16(int value) {
        return (value / 16) * 16;
    }

    private String buildOutputFilePath() {
        File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (!moviesDir.exists()) moviesDir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(moviesDir, "recording_" + ts + ".mp4").getAbsolutePath();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId   = getString(R.string.channel_id);
            String channelName = getString(R.string.channel_name);
            NotificationChannel channel = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Used while a screen recording is in progress.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
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
