package io.github.wakebrief;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone's own voice, rendered to audio samples instead of played directly. Used during an
 * AI-voice briefing for a headline in a language whose AI voice isn't downloaded, so it can go
 * through the same audio stream (same loudness, no gap) as the rest.
 */
final class DeviceSynth implements AutoCloseable {

    static final class Audio {
        final float[] samples;
        final int sampleRate;

        Audio(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private final TextToSpeech tts;
    private final File dir;
    private volatile CountDownLatch pending;
    private int n;

    /** Blocks until the engine is ready (a second or two). Not on the main thread. */
    DeviceSynth(Context ctx) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        int[] status = {TextToSpeech.ERROR};
        tts = new TextToSpeech(ctx.getApplicationContext(), s -> {
            status[0] = s;
            ready.countDown();
        });
        if (!ready.await(20, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
            tts.shutdown();
            throw new IOException("The phone's text-to-speech didn't start");
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { release(); }
            @Override @Deprecated public void onError(String id) { release(); }
            @Override public void onError(String id, int code) { release(); }

            private void release() {
                CountDownLatch l = pending;
                if (l != null) l.countDown();
            }
        });
        dir = ctx.getCacheDir();
    }

    synchronized Audio synthesize(String text, Locale locale, float rate) throws Exception {
        DeviceVoice.apply(tts, locale);
        tts.setSpeechRate(rate);
        n++;
        File wav = new File(dir, "line-" + n + ".wav");
        try {
            pending = new CountDownLatch(1);
            if (tts.synthesizeToFile(text, null, wav, "line" + n) != TextToSpeech.SUCCESS) {
                throw new IOException("Couldn't render: " + text);
            }
            if (!pending.await(15_000 + text.length() * 150L, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out rendering: " + text);
            }
            return readWav(wav);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
    }

    /** 16-bit PCM WAV (what Android's engines write) to mono float samples. */
    private static Audio readWav(File f) throws IOException {
        byte[] bytes;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            bytes = new byte[(int) raf.length()];
            raf.readFully(bytes);
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {
            throw new IOException("Not a WAV file");                 // "RIFF" … "WAVE"
        }
        int channels = 1, rate = 0, bits = 16;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos), size = b.getInt(pos + 4);
            int body = pos + 8;
            if (id == 0x20746d66) {                                   // "fmt "
                channels = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
            } else if (id == 0x61746164) {                            // "data"
                if (bits != 16 || rate <= 0) throw new IOException("Unsupported WAV: " + bits + " bit");
                // Some engines leave the size unset while streaming: then read to the end.
                int end = size <= 0 ? bytes.length : Math.min(bytes.length, body + size);
                int frames = (end - body) / (2 * channels);
                float[] out = new float[frames];
                for (int i = 0; i < frames; i++) {
                    out[i] = b.getShort(body + i * 2 * channels) / 32768f; // first channel
                }
                return new Audio(out, rate);
            }
            if (size < 0) break;
            pos = body + size + (size & 1);
        }
        throw new IOException("No audio in WAV");
    }

    @Override
    public void close() {
        tts.stop();
        tts.shutdown();
    }
}
