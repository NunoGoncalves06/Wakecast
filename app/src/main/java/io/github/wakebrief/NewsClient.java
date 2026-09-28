package io.github.wakebrief;

import android.text.Html;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Headlines from any RSS or Atom feed. Free, no account, no key. */
final class NewsClient {

    private static final String TAG = "Wakecast";

    private NewsClient() {}

    /** A parsed feed: its own name and the item titles, newest first as published. */
    static final class Feed {
        final String title;
        final List<String> headlines;

        Feed(String title, List<String> headlines) {
            this.title = title;
            this.headlines = headlines;
        }
    }

    /**
     * Takes headlines round-robin from each feed so one feed doesn't drown out the others.
     * Returns null if every feed failed.
     */
    static List<String> headlines(String[] feeds, int max, String lang) {
        List<List<String>> perFeed = new ArrayList<>();
        for (String raw : feeds) {
            String url = FeedFinder.normalize(raw); // "economist.com" → "https://economist.com"
            if (url == null) continue;
            try {
                perFeed.add(fetch(url, lang));
            } catch (Exception e) {
                Log.w(TAG, "Feed failed: " + url, e);
            }
        }
        if (perFeed.isEmpty()) return null;

        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; out.size() < max; i++) {
            boolean any = false;
            for (List<String> list : perFeed) {
                if (i >= list.size()) continue;
                any = true;
                String h = list.get(i);
                if (seen.add(h.toLowerCase(Locale.ROOT)) && out.size() < max) out.add(h);
            }
            if (!any) break;
        }
        return out;
    }

    private static List<String> fetch(String url, String lang) throws Exception {
        try {
            Feed feed = parse(Net.getBytes(url, Net.BROWSER_UA), isGoogleNews(url));
            if (feed != null) return feed.headlines;
        } catch (IOException e) {
            // Carry on: a home page behind a bot check (economist.com: 403) still has a feed.
            Log.w(TAG, "Couldn't read " + url + ": " + e.getMessage());
        }

        // A web page rather than a feed (typed by hand and never checked): look for its feed.
        FeedFinder.Result found = FeedFinder.find(url, lang);
        if (found == null) throw new Exception("No news feed found at " + url);
        Log.i(TAG, "Using " + found.feedUrl + " for " + url);
        Feed feed = parse(Net.getBytes(found.feedUrl, Net.BROWSER_UA), isGoogleNews(found.feedUrl));
        if (feed == null) throw new Exception("Not a feed: " + found.feedUrl);
        return feed.headlines;
    }

    static boolean isGoogleNews(String url) {
        return url.contains("news.google.");
    }

    /** Parses RSS, Atom or RDF. Returns null when the document isn't a feed (e.g. a web page). */
    static Feed parse(byte[] body, boolean googleNews) {
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xp.setInput(new ByteArrayInputStream(body), null); // parser detects the encoding

            String feedTitle = null;
            List<String> titles = new ArrayList<>();
            boolean sawRoot = false;
            boolean inItem = false;
            int ev;
            while ((ev = xp.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = xp.getName();
                    if (!sawRoot) {
                        if (!"rss".equals(n) && !"feed".equals(n) && !"rdf:RDF".equals(n)) return null;
                        sawRoot = true;
                    } else if ("item".equals(n) || "entry".equals(n)) {
                        inItem = true;
                    } else if ("title".equals(n)) {
                        String t = clean(xp.nextText(), googleNews && inItem);
                        if (inItem) {
                            if (!t.isEmpty()) titles.add(t);
                        } else if (feedTitle == null) {
                            feedTitle = t;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = xp.getName();
                    if ("item".equals(n) || "entry".equals(n)) inItem = false;
                }
            }
            return sawRoot ? new Feed(feedTitle == null ? "" : feedTitle, titles) : null;
        } catch (Exception e) {
            return null; // HTML and other non-XML ends up here
        }
    }

    private static String clean(String raw, boolean googleNews) {
        String t = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ")
                .trim();
        // Google News titles end with " - Publisher"; drop it so it isn't read aloud.
        if (googleNews) {
            int dash = t.lastIndexOf(" - ");
            if (dash > 20) t = t.substring(0, dash).trim();
        }
        return t;
    }
}
