import Foundation

/// Tiny HTTP helper, as Net.java: two attempts for normal downloads, a quick single "probe" that
/// reports HTTP errors instead of throwing, a 5 MB cap and a browser identity for news sites.
enum Net {
    static let appUA = "Wakecast/1.2 (iOS; open source)"
    /// Many news sites answer an unknown client with a 403 bot-check page, so news requests
    /// identify as a mobile browser.
    static let browserUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    private static let browserAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    private static let maxBytes = 5_000_000

    struct HTTPError: Error { let code: Int; let url: URL }
    struct TooLarge: Error {}

    /// One response. `url` is where the redirects ended up; `body` is empty unless 2xx.
    struct Page {
        let code: Int
        let url: URL
        let body: Data
        var ok: Bool { (200..<300).contains(code) }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    /// Downloads `url`, trying twice (the first request after the phone wakes sometimes fails).
    static func data(_ url: URL, userAgent: String = appUA) async throws -> Data {
        var last: Error = URLError(.unknown)
        for attempt in 0..<2 {
            do {
                let page = try await request(url, userAgent: userAgent, timeout: 15)
                guard page.ok else { throw HTTPError(code: page.code, url: url) }
                return page.body
            } catch {
                last = error
                if attempt == 0 { try? await Task.sleep(nanoseconds: 2_000_000_000) }
            }
        }
        throw last
    }

    /// Single quick attempt as a browser. HTTP errors come back as a Page, not an error.
    static func probe(_ url: URL) async throws -> Page {
        try await request(url, userAgent: browserUA, timeout: 8)
    }

    private static func request(_ url: URL, userAgent: String, timeout: TimeInterval) async throws -> Page {
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if userAgent == browserUA { req.setValue(browserAccept, forHTTPHeaderField: "Accept") }
        // URLSession follows redirects (including http <-> https) on its own.
        let (data, response) = try await session.data(for: req)
        guard data.count <= maxBytes else { throw TooLarge() }
        let http = response as? HTTPURLResponse
        let code = http?.statusCode ?? 200
        return Page(code: code, url: response.url ?? url, body: (200..<300).contains(code) ? data : Data())
    }
}
