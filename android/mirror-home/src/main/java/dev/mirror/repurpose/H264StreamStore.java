package dev.mirror.repurpose;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class H264StreamStore {

    private static final String TAG = "H264StreamStore";

    // Keep only a tiny live buffer.
    // For the mirror, newest frames matter more than preserving history.
    private static final BlockingQueue<byte[]> queue =
            new ArrayBlockingQueue<>(4);

    private static volatile byte[] codecConfig;

    private H264StreamStore() {
    }

    public static void setCodecConfig(byte[] data) {
        codecConfig = data.clone();
        Log.i(TAG, "Stored codec config: " + data.length + " bytes");
    }

    public static void publish(byte[] data) {
        if (!queue.offer(data)) {
            // Never let networking block the camera/encoder.
            queue.poll();
            queue.offer(data);
        }
    }

    public static InputStream openStream() {
        // A live mirror should begin at "now", not replay buffered history.
        queue.clear();

        return new InputStream() {

            private byte[] current;
            private int position;

            private boolean sentConfig = false;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];

                int count = read(one, 0, 1);

                if (count < 0) {
                    return -1;
                }

                return one[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length)
                    throws IOException {

                if (!sentConfig) {
                    byte[] config = codecConfig;

                    if (config != null) {
                        current = config;
                        position = 0;
                    }

                    sentConfig = true;
                }

                while (current == null || position >= current.length) {
                    try {
                        current = queue.take();
                        position = 0;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }

                int amount = Math.min(
                        length,
                        current.length - position);

                System.arraycopy(
                        current,
                        position,
                        buffer,
                        offset,
                        amount);

                position += amount;

                return amount;
            }
        };
    }

    public static void clear() {
        queue.clear();
        codecConfig = null;
    }
}
