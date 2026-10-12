package dev.mirror.repurpose;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class H264Encoder {

    private static final String TAG = "H264Encoder";
    private static final String MIME = "video/avc";

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    public static final int FPS = 30;
    private static final int BIT_RATE = 3_000_000;

    private MediaCodec codec;
    private long frameIndex = 0;

    private long outputFpsStart = 0L;
    private int outputFpsFrames = 0;

    private final byte[] converted =
            new byte[WIDTH * HEIGHT * 3 / 2];

    private final BlockingQueue<byte[]> frameQueue =
            new ArrayBlockingQueue<>(1);

    private volatile boolean running = false;
    private Thread worker;

    public void start() throws IOException {
        MediaFormat format =
                MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);

        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        codec = MediaCodec.createByCodecName(
                "OMX.qcom.video.encoder.avc");

        codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE);

        codec.start();

        running = true;

        worker = new Thread(() -> {
            while (running) {
                try {
                    byte[] frame = frameQueue.take();
                    encodeFrame(frame);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException e) {
                    Log.e(TAG, "Encoder worker error", e);
                }
            }
        }, "H264EncoderWorker");

        worker.start();

        Log.i(
                TAG,
                "Started Qualcomm H.264 encoder: "
                        + WIDTH + "x" + HEIGHT
                        + " @ " + FPS + " fps");
    }

    public void queueNv21(byte[] nv21) {
        if (!running || nv21 == null) {
            return;
        }

        int expected = WIDTH * HEIGHT * 3 / 2;

        if (nv21.length < expected) {
            return;
        }

        byte[] copy = new byte[expected];
        System.arraycopy(nv21, 0, copy, 0, expected);

        frameQueue.clear();
        frameQueue.offer(copy);
    }

    private void encodeFrame(byte[] nv21) {
        if (codec == null) {
            return;
        }

        int expected = WIDTH * HEIGHT * 3 / 2;

        int inputIndex = codec.dequeueInputBuffer(10000);

        if (inputIndex >= 0) {
            ByteBuffer input = codec.getInputBuffer(inputIndex);

            if (input != null) {
                input.clear();

                int ySize = WIDTH * HEIGHT;

                // Y plane is already correct.
                System.arraycopy(
                        nv21,
                        0,
                        converted,
                        0,
                        ySize);

                // Camera gives NV21: VU VU VU VU
                // Encoder expects NV12: UV UV UV UV
                //
                // Process four chroma pairs per loop to reduce loop overhead.
                int i = ySize;

                for (; i + 7 < expected; i += 8) {
                    converted[i]     = nv21[i + 1];
                    converted[i + 1] = nv21[i];

                    converted[i + 2] = nv21[i + 3];
                    converted[i + 3] = nv21[i + 2];

                    converted[i + 4] = nv21[i + 5];
                    converted[i + 5] = nv21[i + 4];

                    converted[i + 6] = nv21[i + 7];
                    converted[i + 7] = nv21[i + 6];
                }

                for (; i < expected; i += 2) {
                    converted[i] = nv21[i + 1];
                    converted[i + 1] = nv21[i];
                }

                input.put(converted, 0, expected);

                long ptsUs = frameIndex * 1_000_000L / FPS;
                frameIndex++;

                codec.queueInputBuffer(
                        inputIndex,
                        0,
                        expected,
                        ptsUs,
                        0);
            }
        }

        drainOutput();
    }

    private void drainOutput() {
        if (codec == null) {
            return;
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Output format: " + codec.getOutputFormat());
                continue;
            }

            if (outputIndex >= 0) {
                ByteBuffer output = codec.getOutputBuffer(outputIndex);

                if (output != null && info.size > 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);

                    byte[] encoded = new byte[info.size];
                    output.get(encoded);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        H264StreamStore.setCodecConfig(encoded);
                    } else {
                        H264StreamStore.publish(encoded);

                        long now = android.os.SystemClock.elapsedRealtime();

                        if (outputFpsStart == 0L) {
                            outputFpsStart = now;
                        }

                        outputFpsFrames++;

                        if (now - outputFpsStart >= 1000) {
                            double fps =
                                    outputFpsFrames * 1000.0
                                    / (now - outputFpsStart);

                            Log.i(
                                    "H264OutputFPS",
                                    "Encoder output FPS: " + fps);

                            outputFpsFrames = 0;
                            outputFpsStart = now;
                        }
                    }
                }

                codec.releaseOutputBuffer(outputIndex, false);
            }
        }
    }

    public void stop() {
        running = false;

        if (worker != null) {
            worker.interrupt();

            try {
                worker.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            worker = null;
        }

        frameQueue.clear();

        if (codec == null) {
            return;
        }

        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }

        try {
            codec.release();
        } catch (RuntimeException ignored) {
        }

        codec = null;
    }
}
