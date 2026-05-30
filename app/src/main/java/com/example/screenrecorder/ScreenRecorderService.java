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
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that owns the MediaProjection session and drives
 * MediaRecorder to write an MP4 file to the Movies directory.
 *
 * Lifecycle:
 *   startForegroundService(ACTION_START) → begins recording
 *   startService(ACTION_STOP)            → stops recording and releases resources
 */
public class ScreenRecorderService extends Service {

    private static final String TAG = "ScreenRecorderService";
    private static final int NOTIFICATION_ID = 1;

    // ---- Intent actions & extras ----
    public static final String ACTION_START = "com.example.screenrecorder.START";
    public static final String ACTION_STOP  = "com.example.screenrecorder.STOP";

    public static final String EXTRA_RESULT_CODE   = "result_code";
    public static final String EXTRA_RESULT_DATA   = "result_data";
    public static final String EXTRA_SCREEN_WIDTH  = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_SCREEN_DPI    = "screen_dpi";

    // ---- Recording components ----
    private MediaProjectionManager projectionManager;
    private MediaProjection         mediaProjection;
    private VirtualDisplay          virtualDisplay;
    private MediaRecorder           mediaRecorder;

    // ---- Display metrics (filled from Intent extras) ----
    private int screenWidth;
    private int screenHeight;
    private int screenDpi;

    // -----------------------------------------------------------------------
    // Service callbacks
    // -----------------------------------------------------------------------

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
                // Promote to foreground immediately so the OS doesn't kill us
                startForeground(NOTIFICATION_ID, buildNotification());

                screenWidth  = intent.getIntExtra(EXTRA_SCREEN_WIDTH,  1080);
                screenHeight = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, 1920);
                screenDpi    = intent.getIntExtra(EXTRA_SCREEN_DPI,    320);

                int    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
                startRecording();
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
        return null; // not a bound service
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Recording logic
    // -----------------------------------------------------------------------

    private void startRecording() {
        String outputPath = buildOutputFilePath();

        mediaRecorder = new MediaRecorder();
        configureMediaRecorder(mediaRecorder, outputPath);

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed", e);
            return;
        }

        // VirtualDisplay feeds captured frames into the MediaRecorder surface
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null, null);

        mediaRecorder.start();
        Log.i(TAG, "Recording started → " + outputPath);
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException e) {
                // stop() throws if recording never actually began (e.g. error in start)
                Log.w(TAG, "MediaRecorder stop failed — probably never started", e);
            }
            mediaRecorder.release();
            mediaRecorder = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        Log.i(TAG, "Recording stopped.");
    }

    // -----------------------------------------------------------------------
    // MediaRecorder configuration
    // -----------------------------------------------------------------------

    /**
     * Configures the recorder to capture video only (no audio).
     * Add setAudioSource / setAudioEncoder calls if you need a microphone track.
     */
    private void configureMediaRecorder(MediaRecorder recorder, String outputPath) {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(outputPath);

        recorder.setVideoSize(screenWidth, screenHeight);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoEncodingBitRate(5 * 1024 * 1024); // 5 Mbps
        recorder.setVideoFrameRate(30);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns a timestamped path inside the public Movies directory. */
    private String buildOutputFilePath() {
        File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (!moviesDir.exists()) moviesDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(moviesDir, "recording_" + timestamp + ".mp4").getAbsolutePath();
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
