import Foundation

/// Headlines from any RSS, Atom or RDF feed. Free, no account, no key. A port of NewsClient.java.
enum NewsClient {

    /// A parsed feed: its own name and the item titles, newest first as published.
    struct Feed: Equatable {
        let title: String
        let headlines: [String]
    }

    /// Takes headlines round-robin from each feed so one feed doesn't drown out the others.
    /// Returns nil if every feed failed.
    static func headlines(feeds: [String], max: Int, lang: String) async -> [String]? {
        let urls = feeds.compactMap(FeedFinder.normalize)
        // Fetch all feeds at once, but keep them in the user's order.
        let perFeed: [[String]?] = await withTaskGroup(of: (Int, [String]?).self) { group in
            for (i, url) in urls.enumerated() {
                group.addTask { (i, try? await fetch(url, lang: lang)) }
            }
            var results = [[String]?](repeating: nil, count: urls.count)
            for await (i, list) in group { results[i] = list }
            return results
        }
        let lists = perFeed.compactMap { $0 }
        guard !lists.isEmpty else { return nil }
        return merge(lists, max: max)
    }

    /// Round robin across feeds, dropping repeats (case-insensitive).
    static func merge(_ lists: [[String]], max: Int) -> [String] {
        var out: [String] = []
        var seen = Set<String>()
        var i = 0
        while out.count < max {
            var any = false
            for list in lists where i < list.count {
                any = true
                let h = list[i]
                if seen.insert(h.lowercased()).inserted && out.count < max { out.append(h) }
            }
            if !any { break }
            i += 1
        }
        return out
    }

    private static func fetch(_ url: URL, lang: String) async throws -> [String] {
        // Carry on if this fails: a home page behind a bot check (economist.com: 403) still has a feed.
        if let body = try? await Net.data(url, userAgent: Net.browserUA),
           let feed = parse(body, googleNews: isGoogleNews(url)) {
            return feed.headlines
        }
        // A web page rather than a feed (typed by hand and never checked): look for its feed.
        guard let found = await FeedFinder.find(url.absoluteString, lang: lang),
              let feedURL = URL(string: found.feedURL) else {
            throw URLError(.cannotParseResponse)
        }
        let body = try await Net.data(feedURL, userAgent: Net.browserUA)
        guard let feed = parse(body, googleNews: isGoogleNews(feedURL)) else { throw URLError(.cannotParseResponse) }
        return feed.headlines
    }

    static func isGoogleNews(_ url: URL) -> Bool {
        url.absoluteString.contains("news.google.")
    }

    /// Parses RSS, Atom or RDF. Returns nil when the document isn't a feed (e.g. a web page).
    static func parse(_ body: Data, googleNews: Bool) -> Feed? {
        let delegate = FeedParser(googleNews: googleNews)
        let parser = XMLParser(data: body)
        parser.shouldProcessNamespaces = false
        parser.delegate = delegate
        parser.parse()
        // A parse error after the items (a stray entity near the end) still leaves good headlines.
        guard delegate.sawRoot, !delegate.notAFeed else { return nil }
        return Feed(title: delegate.feedTitle ?? "", headlines: delegate.titles)
    }

    /// Strips tags and squeezes whitespace; for Google News drops the " - Publisher" suffix.
    static func clean(_ raw: String, googleNews: Bool) -> String {
        var t = raw.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        for (entity, char) in [("&amp;", "&"), ("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"),
                               ("&lt;", "<"), ("&gt;", ">"), ("&nbsp;", " ")] {
            t = t.replacingOccurrences(of: entity, with: char)
        }
        t = t.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if googleNews, let dash = t.range(of: " - ", options: .backwards),
           t.distance(from: t.startIndex, to: dash.lowerBound) > 20 {
            t = String(t[..<dash.lowerBound]).trimmingCharacters(in: .whitespaces)
        }
        return t
    }

    private final class FeedParser: NSObject, XMLParserDelegate {
        let googleNews: Bool
        var sawRoot = false
        var notAFeed = false
        var inItem = false
        var inTitle = false
        var text = ""
        var feedTitle: String?
        var titles: [String] = []

        init(googleNews: Bool) { self.googleNews = googleNews }

        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                    qualifiedName: String?, attributes: [String: String] = [:]) {
            if !sawRoot {
                guard ["rss", "feed", "rdf:RDF"].contains(name) else {
                    notAFeed = true
                    parser.abortParsing()
                    return
                }
                sawRoot = true
            } else if name == "item" || name == "entry" {
                inItem = true
            } else if name == "title" {
                inTitle = true
                text = ""
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if inTitle { text += string }
        }

        func parser(_ parser: XMLParser, foundCDATA block: Data) {
            if inTitle { text += String(decoding: block, as: UTF8.self) }
        }

        func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
            if name == "title" && inTitle {
                inTitle = false
                let t = NewsClient.clean(text, googleNews: googleNews && inItem)
                if inItem {
                    if !t.isEmpty { titles.append(t) }
                } else if feedTitle == nil {
                    feedTitle = t
                }
            } else if name == "item" || name == "entry" {
                inItem = false
            }
        }
    }
}
