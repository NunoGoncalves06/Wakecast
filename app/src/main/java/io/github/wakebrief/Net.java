package io.github.wakebrief;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tiny HTTP helper: plain HttpURLConnection, no libraries. */
final class Net {

    static final String APP_UA = "Wakecast/1.0 (Android; open source)";
    /**
     * Many news sites answer an unknown client with a 403 bot-check page, so news requests
     * identify as a mobile browser.
     */
    static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BYTES = 5_000_000;

    private Net() {}

    /** One response. {@code url} is where the redirects ended up. */
    static final class Page {
        final int code;
        final String url;
        final String contentType;
        final byte[] body; // empty unless the answer was 2xx

        Page(int code, String url, String contentType, byte[] body) {
            this.code = code;
            this.url = url;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body;
        }

        boolean ok() { return code >= 200 && code < 300; }
    }

    static byte[] getBytes(String url) throws IOException {
        return getBytes(url, APP_UA);
    }

    static byte[] getBytes(String url, String userAgent) throws IOException {
        IOException last = null;
        // Two attempts: the first request after the phone wakes from Doze sometimes fails.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Page page = request(url, userAgent, 10_000, 15_000);
                if (!page.ok()) throw new IOException("HTTP " + page.code + " for " + url);
                return page.body;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("Interrupted");
    }

    static String getString(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    /** Single quick attempt as a browser. HTTP errors come back as a Page, not an exception. */
    static Page probe(String url) throws IOException {
        return request(url, BROWSER_UA, 8_000, 8_000);
    }

    private static Page request(String url, String userAgent, int connectMs, int readMs)
            throws IOException {
        String current = url;
        // Followed by hand: HttpURLConnection won't follow http <-> https redirects.
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", userAgent);
            if (BROWSER_UA.equals(userAgent)) {
                // The browser's usual Accept: some bot filters (e.g. expresso.pt) reject anything else.
                c.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            }
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("HTTP " + code + " without Location");
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                byte[] body = code >= 200 && code < 300 ? read(c.getInputStream()) : new byte[0];
                return new Page(code, current, c.getContentType(), body);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects for " + url);
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("Response too large");
            }
            return out.toByteArray();
        }
    }
}
