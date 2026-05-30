package com.example.screenrecorder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

class VideoSplitter {

    static final long STORY_MAX_US = 15_000_000L; // 15 seconds in microseconds

    /**
     * Splits the video at pathOrUri into chunks of at most maxUs microseconds,
     * writing each chunk as an MP4 file inside outputDir.
     *
     * If the video already fits in a single story the original content is still
     * copied to outputDir so the result is always a FileProvider-shareable File.
     */
    static List<File> split(Context context, String pathOrUri, File outputDir, long maxUs)
            throws IOException {

        long durationUs = getDuration(context, pathOrUri);
        outputDir.mkdirs();

        int numChunks = (durationUs > maxUs)
                ? (int) Math.ceil((double) durationUs / maxUs)
                : 1;

        // Collect track formats once
        MediaExtractor probe = newExtractor(context, pathOrUri);
        int trackCount = probe.getTrackCount();
        MediaFormat[] formats = new MediaFormat[trackCount];
        for (int i = 0; i < trackCount; i++) formats[i] = probe.getTrackFormat(i);
        probe.release();

        List<File> chunks = new ArrayList<>();

        for (int c = 0; c < numChunks; c++) {
            long startUs = (long) c * maxUs;
            long endUs   = (c == numChunks - 1) ? Long.MAX_VALUE : startUs + maxUs;

            String name = (numChunks == 1)
                    ? "story.mp4"
                    : "story_" + (c + 1) + "_of_" + numChunks + ".mp4";
            File outFile = new File(outputDir, name);
            chunks.add(outFile);

            MediaExtractor ex = newExtractor(context, pathOrUri);
            for (int t = 0; t < trackCount; t++) ex.selectTrack(t);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            MediaMuxer muxer = new MediaMuxer(
                    outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] muxTracks = new int[trackCount];
            for (int t = 0; t < trackCount; t++) muxTracks[t] = muxer.addTrack(formats[t]);
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeOffsetUs = -1;

            while (true) {
                int trackIdx = ex.getSampleTrackIndex();
                if (trackIdx < 0) break;

                long sampleUs = ex.getSampleTime();
                if (sampleUs < startUs) { ex.advance(); continue; } // behind sync point
                if (sampleUs >= endUs) break;

                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;

                if (timeOffsetUs < 0) timeOffsetUs = sampleUs;

                info.offset = 0;
                info.size   = size;
                info.presentationTimeUs = sampleUs - timeOffsetUs;
                info.flags  = ex.getSampleFlags();

                muxer.writeSampleData(muxTracks[trackIdx], buf, info);
                ex.advance();
            }

            muxer.stop();
            muxer.release();
            ex.release();
        }

        return chunks;
    }

    private static long getDuration(Context context, String pathOrUri) {
        try {
            MediaExtractor ex = newExtractor(context, pathOrUri);
            long duration = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                if (fmt.containsKey(MediaFormat.KEY_DURATION))
                    duration = Math.max(duration, fmt.getLong(MediaFormat.KEY_DURATION));
            }
            ex.release();
            return duration;
        } catch (IOException e) {
            return 0;
        }
    }

    private static MediaExtractor newExtractor(Context context, String pathOrUri)
            throws IOException {
        MediaExtractor ex = new MediaExtractor();
        if (pathOrUri.startsWith("content://"))
            ex.setDataSource(context, Uri.parse(pathOrUri), null);
        else
            ex.setDataSource(pathOrUri);
        return ex;
    }
}
