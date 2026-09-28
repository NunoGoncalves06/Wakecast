package io.github.wakebrief;

import android.text.Html;
import android.util.Log;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever the user typed ("economist.com", a section page, a feed link) into a working
 * RSS/Atom feed. In order:
 * <ol>
 *   <li>the link itself, if it already is a feed;</li>
 *   <li>feeds the page advertises with {@code <link rel="alternate" type="application/rss+xml">};</li>
 *   <li>the usual feed paths (/feed, /rss.xml, …), which also works when the home page itself
 *       is behind a bot check, as economist.com is;</li>
 *   <li>Google News limited to that site, which covers almost any news site.</li>
 * </ol>
 */
final class FeedFinder {

    private static final String TAG = "Wakecast";

    /** Where sites usually keep their main feed, most common first. */
    private static final String[] COMMON_PATHS = {
            "/feed", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml", "/rss/index.xml",
            "/latest/rss.xml", "/news/rss.xml", "/feeds/posts/default", "/?feed=rss2",
    };
    private static final long PROBE_BUDGET_MS = 20_000;

    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern FEED_TYPE = Pattern.compile(
            "application/(rss|atom|rdf)\\+xml", Pattern.CASE_INSENSITIVE);

    private FeedFinder() {}

    static final class Result {
        final String feedUrl;
        final String title;     // the feed's own name, or the site's host for Google News
        final int headlines;
        final boolean viaGoogleNews;

        Result(String feedUrl, String title, int headlines, boolean viaGoogleNews) {
            this.feedUrl = feedUrl;
            this.title = title;
            this.headlines = headlines;
            this.viaGoogleNews = viaGoogleNews;
        }
    }

    /** "economist.com" → "https://economist.com". Returns null for something that isn't a web address. */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if (s.regionMatches(true, 0, "feed://", 0, 7)) s = "https://" + s.substring(7);
        else if (!s.regionMatches(true, 0, "http://", 0, 7)
                && !s.regionMatches(true, 0, "https://", 0, 8)) s = "https://" + s;
        try {
            URL u = new URL(s);
            String host = u.getHost();
            if (host == null || !host.contains(".") || host.startsWith(".") || host.endsWith(".")) return null;
            return u.toString();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** Blocks on the network. Returns null when no feed with headlines could be found. */
    static Result find(String raw, String lang) {
        String url = normalize(raw);
        if (url == null) return null;

        Net.Page page = null;
        try {
            page = Net.probe(url);
        } catch (IOException e) {
            Log.w(TAG, "Couldn't open " + url, e);
        }
        if (page != null && page.ok()) {
            NewsClient.Feed feed = NewsClient.parse(page.body, NewsClient.isGoogleNews(page.url));
            if (feed != null) {
                // A feed link given on purpose counts even if it happens to be empty right now.
                return new Result(url, orHost(feed.title, url), feed.headlines.size(),
                        NewsClient.isGoogleNews(url));
            }
            for (String link : advertisedFeeds(page)) {
                Result r = check(link);
                if (r != null) return r;
            }
        }

        Result guessed = firstWorking(candidates(page != null ? page.url : url));
        if (guessed != null) return guessed;

        return googleNews(host(url), lang);
    }

    // ------------------------------------------------------------------ steps

    /** Feed links in the page's {@code <head>}, in the order the site lists them. */
    static List<String> advertisedFeeds(Net.Page page) {
        List<String> out = new ArrayList<>();
        String html = new String(page.body, StandardCharsets.UTF_8);
        Matcher tag = LINK_TAG.matcher(html);
        while (tag.find()) {
            String rel = null, type = null, href = null;
            Matcher a = ATTR.matcher(tag.group());
            while (a.find()) {
                String name = a.group(1).toLowerCase(Locale.ROOT);
                String value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
                if ("rel".equals(name)) rel = value.toLowerCase(Locale.ROOT);
                else if ("type".equals(name)) type = value;
                else if ("href".equals(name)) href = value;
            }
            if (rel == null || !rel.contains("alternate") || type == null || href == null) continue;
            if (!FEED_TYPE.matcher(type).find()) continue;
            String decoded = Html.fromHtml(href, Html.FROM_HTML_MODE_LEGACY).toString().trim();
            try {
                String abs = new URL(new URL(page.url), decoded).toString();
                if (!out.contains(abs)) out.add(abs);
            } catch (MalformedURLException ignored) {
                // skip the odd broken href
            }
            if (out.size() >= 5) break;
        }
        return out;
    }

    /** The folder the user pointed at first (theguardian.com/world → /world/rss), then the site root. */
    static List<String> candidates(String url) {
        Set<String> out = new LinkedHashSet<>();
        try {
            URL u = new URL(url);
            String origin = u.getProtocol() + "://" + u.getAuthority();
            String path = u.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (!path.isEmpty()) {
                out.add(origin + path + "/rss");
                out.add(origin + path + "/feed");
                out.add(origin + path + "/rss.xml");
            }
            for (String p : COMMON_PATHS) out.add(origin + p);
        } catch (MalformedURLException ignored) {
            // nothing to guess from
        }
        return new ArrayList<>(out);
    }

    /** Tries every candidate at once but prefers the earliest one in the list that works. */
    private static Result firstWorking(List<String> urls) {
        if (urls.isEmpty()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, urls.size()));
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (String u : urls) futures.add(pool.submit(() -> check(u)));
            long deadline = System.currentTimeMillis() + PROBE_BUDGET_MS;
            for (Future<Result> f : futures) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    Result r = f.get(left, TimeUnit.MILLISECONDS);
                    if (r != null) return r;
                } catch (Exception ignored) {
                    // timed out or failed: try the next one
                }
            }
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Result googleNews(String host, String lang) {
        if (host == null) return null;
        String site = host.startsWith("www.") ? host.substring(4) : host;
        String en = "hl=en-US&gl=US&ceid=US:en", pt = "hl=pt-PT&gl=PT&ceid=PT:pt-150";
        // Each Google News edition only covers its own sites well (expresso.pt: 79 stories in
        // the PT edition, 0 in the US one), so try both, the voice's language first.
        String[] regions = "pt".equals(lang) ? new String[]{pt, en} : new String[]{en, pt};
        // Last day first so the briefing gets today's stories; a quieter site gets a week.
        for (String window : new String[]{"1d", "7d"}) {
            String q = URLEncoder.encode("site:" + site + " when:" + window, StandardCharsets.UTF_8);
            Result best = null;
            for (String region : regions) {
                Result r = check("https://news.google.com/rss/search?q=" + q + "&" + region);
                if (r != null && (best == null || r.headlines > best.headlines)) best = r;
            }
            if (best != null) return new Result(best.feedUrl, site, best.headlines, true);
        }
        return null;
    }

    /** The feed at {@code url}, if it is one and has at least one headline. */
    private static Result check(String url) {
        try {
            Net.Page page = Net.probe(url);
            if (!page.ok()) return null;
            NewsClient.Feed feed = NewsClient.parse(page.body, NewsClient.isGoogleNews(url));
            if (feed == null || feed.headlines.isEmpty()) return null;
            return new Result(url, orHost(feed.title, url), feed.headlines.size(), NewsClient.isGoogleNews(url));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** "https://www.economist.com/x" → "economist.com", for showing the user. */
    static String siteName(String raw) {
        String url = normalize(raw);
        String h = url == null ? null : host(url);
        if (h == null) return raw;
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost().toLowerCase(Locale.ROOT);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static String orHost(String title, String url) {
        if (title != null && !title.isEmpty()) return title;
        String h = host(url);
        return h == null ? url : h;
    }
}
