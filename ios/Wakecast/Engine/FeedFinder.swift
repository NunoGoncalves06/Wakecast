import Foundation

/// Turns whatever the user typed ("economist.com", a section page, a feed link) into a working
/// RSS/Atom feed. In order: the link itself; feeds the page advertises; the usual feed paths
/// (which also works when the home page is behind a bot check); and Google News limited to that
/// site. A port of FeedFinder.java.
enum FeedFinder {

    struct Result: Equatable {
        let feedURL: String
        /// The feed's own name, or the site's host for Google News.
        let title: String
        let headlines: Int
        let viaGoogleNews: Bool
    }

    /// Where sites usually keep their main feed, most common first.
    static let commonPaths = [
        "/feed", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml", "/rss/index.xml",
        "/latest/rss.xml", "/news/rss.xml", "/feeds/posts/default", "/?feed=rss2",
    ]

    /// "economist.com" -> https://economist.com. nil for something that isn't a web address.
    static func normalize(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        let lower = s.lowercased()
        if lower.hasPrefix("feed://") {
            s = "https://" + s.dropFirst(7)
        } else if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") {
            s = "https://" + s
        }
        guard let url = URL(string: s), let host = url.host, host.contains("."),
              !host.hasPrefix("."), !host.hasSuffix(".") else { return nil }
        return url
    }

    /// Returns nil when no feed with headlines could be found.
    static func find(_ raw: String, lang: String) async -> Result? {
        guard let url = normalize(raw) else { return nil }

        let page = try? await Net.probe(url)
        if let page, page.ok {
            if let feed = NewsClient.parse(page.body, googleNews: NewsClient.isGoogleNews(page.url)) {
                // A feed link given on purpose counts even if it happens to be empty right now.
                return Result(feedURL: url.absoluteString, title: orHost(feed.title, url),
                              headlines: feed.headlines.count, viaGoogleNews: NewsClient.isGoogleNews(url))
            }
            for link in advertisedFeeds(html: String(decoding: page.body, as: UTF8.self), base: page.url) {
                if let r = await check(link) { return r }
            }
        }

        if let guessed = await firstWorking(candidates(for: page?.url ?? url)) { return guessed }
        return await googleNews(host: url.host?.lowercased(), lang: lang)
    }

    /// Feed links in the page's <head>, in the order the site lists them (at most 5).
    static func advertisedFeeds(html: String, base: URL) -> [URL] {
        let tagPattern = try! NSRegularExpression(pattern: "<link\\b[^>]*>", options: .caseInsensitive)
        let attrPattern = try! NSRegularExpression(pattern: "([a-zA-Z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
        var out: [URL] = []
        let ns = html as NSString
        for tag in tagPattern.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            let tagText = ns.substring(with: tag.range) as NSString
            var attrs: [String: String] = [:]
            for a in attrPattern.matches(in: tagText as String, range: NSRange(location: 0, length: tagText.length)) {
                let name = tagText.substring(with: a.range(at: 1)).lowercased()
                let value = (2...4).map { a.range(at: $0) }.first { $0.location != NSNotFound }.map { tagText.substring(with: $0) } ?? ""
                attrs[name] = value
            }
            guard let rel = attrs["rel"]?.lowercased(), rel.contains("alternate"),
                  let type = attrs["type"], type.range(of: "application/(rss|atom|rdf)\\+xml", options: [.regularExpression, .caseInsensitive]) != nil,
                  let href = attrs["href"] else { continue }
            let decoded = NewsClient.clean(href, googleNews: false)
            if let abs = URL(string: decoded, relativeTo: base)?.absoluteURL, !out.contains(abs) { out.append(abs) }
            if out.count >= 5 { break }
        }
        return out
    }

    /// The folder the user pointed at first (theguardian.com/world -> /world/rss), then the site root.
    static func candidates(for url: URL) -> [URL] {
        guard let scheme = url.scheme, let host = url.host else { return [] }
        let origin = scheme + "://" + host + (url.port.map { ":\($0)" } ?? "")
        var path = url.path
        while path.hasSuffix("/") { path.removeLast() }
        var out: [String] = []
        if !path.isEmpty { out += [origin + path + "/rss", origin + path + "/feed", origin + path + "/rss.xml"] }
        out += commonPaths.map { origin + $0 }
        var seen = Set<String>()
        return out.filter { seen.insert($0).inserted }.compactMap(URL.init(string:))
    }

    /// Tries every candidate at once but prefers the earliest one in the list that works.
    private static func firstWorking(_ urls: [URL]) async -> Result? {
        guard !urls.isEmpty else { return nil }
        let results: [Result?] = await withTaskGroup(of: (Int, Result?).self) { group in
            for (i, u) in urls.enumerated() { group.addTask { (i, await check(u)) } }
            var out = [Result?](repeating: nil, count: urls.count)
            for await (i, r) in group { out[i] = r }
            return out
        }
        return results.compactMap { $0 }.first
    }

    private static func googleNews(host: String?, lang: String) async -> Result? {
        guard let host else { return nil }
        let site = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        let en = "hl=en-US&gl=US&ceid=US:en", pt = "hl=pt-PT&gl=PT&ceid=PT:pt-150"
        // Each Google News edition only covers its own sites well, so try both, the voice's language first.
        let regions = lang == "pt" ? [pt, en] : [en, pt]
        // Last day first so the briefing gets today's stories; a quieter site gets a week.
        for window in ["1d", "7d"] {
            let q = "site:\(site) when:\(window)".addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
            var best: Result?
            for region in regions {
                guard let url = URL(string: "https://news.google.com/rss/search?q=\(q)&\(region)") else { continue }
                if let r = await check(url), best == nil || r.headlines > best!.headlines { best = r }
            }
            if let best { return Result(feedURL: best.feedURL, title: site, headlines: best.headlines, viaGoogleNews: true) }
        }
        return nil
    }

    /// The feed at `url`, if it is one and has at least one headline.
    private static func check(_ url: URL) async -> Result? {
        guard let page = try? await Net.probe(url), page.ok,
              let feed = NewsClient.parse(page.body, googleNews: NewsClient.isGoogleNews(url)),
              !feed.headlines.isEmpty else { return nil }
        return Result(feedURL: url.absoluteString, title: orHost(feed.title, url),
                      headlines: feed.headlines.count, viaGoogleNews: NewsClient.isGoogleNews(url))
    }

    /// "https://www.economist.com/x" -> "economist.com", for showing the user.
    static func siteName(_ raw: String) -> String {
        guard let host = normalize(raw)?.host?.lowercased() else { return raw }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    private static func orHost(_ title: String, _ url: URL) -> String {
        title.isEmpty ? (url.host ?? url.absoluteString) : title
    }
}
