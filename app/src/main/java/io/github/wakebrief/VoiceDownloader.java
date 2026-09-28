package io.github.wakebrief;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads an AI voice pack and unpacks it on the fly (no temporary archive to store twice).
 * Keeps going if the screen is recreated; the UI listens for changes and re-reads the state.
 * All public methods are for the main thread.
 */
final class VoiceDownloader {

    interface Listener {
        void onVoiceDownloadChanged();
    }

    private static final String TAG = "Wakecast";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Job> JOBS = new HashMap<>();
    private static final Map<String, String> ERRORS = new HashMap<>();
    private static Listener listener;

    private static final class Job {
        volatile int percent;
        volatile boolean cancelled;
    }

    private VoiceDownloader() {}

    static void setListener(Listener l) {
        listener = l;
    }

    static boolean isRunning(AiVoice.Pack p) {
        return JOBS.containsKey(p.id);
    }

    static int percent(AiVoice.Pack p) {
        Job j = JOBS.get(p.id);
        return j == null ? 0 : j.percent;
    }

    /** Why the last attempt failed, or null. */
    static String error(AiVoice.Pack p) {
        return ERRORS.get(p.id);
    }

    static void cancel(AiVoice.Pack p) {
        Job j = JOBS.get(p.id);
        if (j != null) j.cancelled = true;
    }

    static void start(Context ctx, AiVoice.Pack p) {
        if (isRunning(p)) return;
        Context app = ctx.getApplicationContext();
        Job job = new Job();
        JOBS.put(p.id, job);
        ERRORS.remove(p.id);
        notifyChanged();
        new Thread(() -> {
            String error = null;
            try {
                download(app, p, job);
            } catch (InterruptedIOException e) {
                // cancelled by the user: not an error
            } catch (UnknownHostException e) {
                error = "No internet connection. Try again when you're online.";
            } catch (Exception e) {
                Log.w(TAG, "Voice download failed", e);
                error = e instanceof SpaceException ? e.getMessage() : "The download didn't finish. Try again.";
            } finally {
                AiVoice.deleteRecursively(partDir(app, p));
            }
            String err = error;
            MAIN.post(() -> {
                JOBS.remove(p.id);
                if (err != null) ERRORS.put(p.id, err);
                notifyChanged();
            });
        }, "voice-download").start();
    }

    private static void notifyChanged() {
        if (listener != null) listener.onVoiceDownloadChanged();
    }

    private static File partDir(Context app, AiVoice.Pack p) {
        return new File(AiVoice.root(app), p.id + ".part");
    }

    private static final class SpaceException extends IOException {
        SpaceException(String msg) { super(msg); }
    }

    private static void download(Context app, AiVoice.Pack p, Job job) throws IOException {
        File root = AiVoice.root(app);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        // Unpacked, a pack takes a bit more room than the download.
        long needed = p.megabytes * 1_600_000L;
        if (root.getUsableSpace() < needed) {
            throw new SpaceException("Not enough free space: this voice needs about "
                    + (needed / 1_000_000) + " MB.");
        }
        File tmp = partDir(app, p);
        AiVoice.deleteRecursively(tmp);
        if (!tmp.mkdirs()) throw new IOException("Can't create " + tmp);
        String tmpPath = tmp.getCanonicalPath() + File.separator;

        HttpURLConnection c = (HttpURLConnection) new URL(p.url()).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true); // GitHub sends us on to its download host
        c.setRequestProperty("User-Agent", Net.APP_UA);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + p.url());
            long total = c.getContentLengthLong();
            if (total <= 0) total = p.megabytes * 1_000_000L;
            final long size = total;

            InputStream counted = new FilterInputStream(new BufferedInputStream(c.getInputStream(), 1 << 16)) {
                long read;

                @Override public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) progress(1);
                    return b;
                }

                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    int n = super.read(buf, off, len);
                    if (n > 0) progress(n);
                    return n;
                }

                private void progress(int n) throws InterruptedIOException {
                    if (job.cancelled) throw new InterruptedIOException("Cancelled");
                    read += n;
                    int pct = (int) Math.min(99, read * 100 / size);
                    if (pct != job.percent) {
                        job.percent = pct;
                        MAIN.post(VoiceDownloader::notifyChanged);
                    }
                }
            };

            byte[] buf = new byte[1 << 16];
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(counted))) {
                TarArchiveEntry e;
                while ((e = tar.getNextEntry()) != null) {
                    // Drop the archive's top folder ("kokoro-int8-en-v0_19/…").
                    String name = e.getName();
                    int slash = name.indexOf('/');
                    name = slash >= 0 ? name.substring(slash + 1) : name;
                    if (name.isEmpty()) continue;
                    File out = new File(tmp, name);
                    // Never write outside the voice folder, whatever the archive says.
                    if (!out.getCanonicalPath().startsWith(tmpPath)) continue;
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                        continue;
                    }
                    if (!e.isFile()) continue; // links and special files aren't needed
                    File parent = out.getParentFile();
                    if (parent != null) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = tar.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        AiVoice.markInstalled(tmp);
        File dest = AiVoice.dir(app, p);
        AiVoice.deleteRecursively(dest);
        if (!tmp.renameTo(dest)) throw new IOException("Couldn't move the voice into place");
        Log.i(TAG, "Installed voice " + p.id);
    }
}
