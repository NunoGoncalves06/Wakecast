package io.github.wakebrief;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Plays raw audio from the AI voice as it is generated. One instance per briefing (or sample);
 * {@link #stop()} may be called from any thread to cut it short.
 */
final class PcmPlayer {

    private static final int CHUNK_MS = 200;

    private final AudioTrack track;
    private final int sampleRate;
    private long framesWritten;
    private volatile boolean stopped;

    PcmPlayer(int sampleRate, AudioAttributes attrs) {
        this.sampleRate = sampleRate;
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min, sampleRate * 4 / 2)) // ~half a second
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
    }

    /** Queues speech; blocks while the buffer is full. Returns false once stopped. */
    boolean write(float[] samples) {
        int chunk = sampleRate * CHUNK_MS / 1000;
        for (int off = 0; off < samples.length; off += chunk) {
            if (stopped) return false;
            int n = Math.min(chunk, samples.length - off);
            int w = track.write(samples, off, n, AudioTrack.WRITE_BLOCKING);
            if (w < 0) return false;
            framesWritten += w;
        }
        return !stopped;
    }

    int sampleRate() {
        return sampleRate;
    }

    /**
     * Converts audio made at another rate (Piper voices are 22.05 kHz, Kokoro 24 kHz) to this
     * player's, so every voice can share one uninterrupted stream.
     */
    static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int n = (int) ((long) in.length * to / from);
        float[] out = new float[n];
        double step = (double) from / to;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int j = (int) pos;
            float t = (float) (pos - j);
            float a = in[Math.min(j, in.length - 1)];
            float b = in[Math.min(j + 1, in.length - 1)];
            out[i] = a + (b - a) * t;
        }
        return out;
    }

    boolean silence(int ms) {
        return ms <= 0 || write(new float[sampleRate * ms / 1000]);
    }

    /** Waits until everything queued has actually been heard. */
    void drain() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000 + framesWritten * 1000 / sampleRate;
        while (!stopped && System.currentTimeMillis() < deadline) {
            long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            if (played >= framesWritten) return;
            Thread.sleep(50);
        }
    }

    /** Stops at once. Safe to call more than once and from any thread. */
    void stop() {
        stopped = true;
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
            // already released
        }
    }

    /** Frees the audio track. Only the thread that writes may call this. */
    void release() {
        stop();
        track.release();
    }
}
