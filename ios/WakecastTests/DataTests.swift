import XCTest
@testable import Wakecast

/// Offline tests for the news and weather parsing (same behaviour as the Android classes).
final class NewsTests: XCTestCase {

    func testParsesRss() {
        let rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>BBC News</title>
        <item><title><![CDATA[UK diesel price hits all-time high]]></title></item>
        <item><title>Rates &amp; <b>mortgages</b>   explained</title></item>
        </channel></rss>
        """
        let feed = NewsClient.parse(Data(rss.utf8), googleNews: false)
        XCTAssertEqual(feed, NewsClient.Feed(title: "BBC News", headlines: ["UK diesel price hits all-time high", "Rates & mortgages explained"]))
    }

    func testParsesAtomAndRdf() {
        let atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Blog</title><entry><title>First post</title></entry></feed>"
        XCTAssertEqual(NewsClient.parse(Data(atom.utf8), googleNews: false)?.headlines, ["First post"])
        let rdf = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><item><title>Old style</title></item></rdf:RDF>"
        XCTAssertEqual(NewsClient.parse(Data(rdf.utf8), googleNews: false)?.headlines, ["Old style"])
    }

    func testWebPageIsNotAFeed() {
        let html = "<html><head><title>Home</title></head><body>Hi</body></html>"
        XCTAssertNil(NewsClient.parse(Data(html.utf8), googleNews: false))
    }

    func testGoogleNewsDropsThePublisher() {
        XCTAssertEqual(NewsClient.clean("IPMA alerta para chuva forte e trovoada - Jornal de Notícias", googleNews: true),
                       "IPMA alerta para chuva forte e trovoada")
        XCTAssertEqual(NewsClient.clean("Short - Site", googleNews: true), "Short - Site", "only when the headline is long enough")
    }

    func testMergeIsRoundRobinWithoutRepeats() {
        let merged = NewsClient.merge([["A1", "A2", "A3"], ["B1", "a1", "B2"]], max: 4)
        XCTAssertEqual(merged, ["A1", "B1", "A2", "A3"])
    }

    func testNormalizeAndSiteName() {
        XCTAssertEqual(FeedFinder.normalize("economist.com")?.absoluteString, "https://economist.com")
        XCTAssertEqual(FeedFinder.normalize("feed://example.org/rss")?.absoluteString, "https://example.org/rss")
        XCTAssertNil(FeedFinder.normalize("not a site"))
        XCTAssertNil(FeedFinder.normalize(""))
        XCTAssertEqual(FeedFinder.siteName("https://www.economist.com/latest"), "economist.com")
    }

    func testCandidatesStartWithTheGivenFolder() {
        let c = FeedFinder.candidates(for: URL(string: "https://www.theguardian.com/world/")!).map(\.absoluteString)
        XCTAssertEqual(Array(c.prefix(4)), [
            "https://www.theguardian.com/world/rss", "https://www.theguardian.com/world/feed",
            "https://www.theguardian.com/world/rss.xml", "https://www.theguardian.com/feed",
        ])
        XCTAssertEqual(c.count, 3 + FeedFinder.commonPaths.count)
    }

    func testAdvertisedFeeds() {
        let html = """
        <head><link rel="stylesheet" href="/a.css">
        <link rel="alternate" type="application/rss+xml" title="News" href="/rss.xml?x=1&amp;y=2">
        <link type='application/atom+xml' href='https://cdn.example.com/atom' rel='alternate'></head>
        """
        let feeds = FeedFinder.advertisedFeeds(html: html, base: URL(string: "https://example.com/news/")!)
        XCTAssertEqual(feeds.map(\.absoluteString), ["https://example.com/rss.xml?x=1&y=2", "https://cdn.example.com/atom"])
    }
}

final class WeatherTests: XCTestCase {

    private let json = """
    {"utc_offset_seconds":3600,
     "current":{"temperature_2m":19.2,"apparent_temperature":20.8,"weather_code":3},
     "hourly":{"time":["2026-09-28T09:00","2026-09-28T10:00","2026-09-28T11:00","2026-09-28T12:00"],
               "precipitation_probability":[90,20,55,70]},
     "daily":{"weather_code":[63],"temperature_2m_max":[24.6],"temperature_2m_min":[14.1],
              "precipitation_probability_max":[70],"precipitation_sum":[4.2],"uv_index_max":[5.5],
              "wind_speed_10m_max":[31.0],"sunset":["2026-09-28T19:21"]}}
    """

    func testParsesForecastAndFindsTheNextRainyHour() throws {
        // 09:30 UTC is 10:30 local (UTC+1): the 09:00 rain is past, 11:00 is the next hour at 50% or more.
        let now = ISO8601DateFormatter().date(from: "2026-09-28T09:30:00Z")!
        let w = try WeatherClient.parseForecast(Data(json.utf8), now: now)
        XCTAssertEqual(w.temp, 19.2)
        XCTAssertEqual(w.code, 3)
        XCTAssertEqual(w.dayCode, 63)
        XCTAssertEqual(w.rainChance, 70)
        XCTAssertEqual(w.rainFromMinute, 11 * 60)
        XCTAssertEqual(w.sunsetMinute, 19 * 60 + 21)
    }

    func testGeocodeLabel() throws {
        let json = """
        {"results":[{"name":"Porto","admin1":"Distrito do Porto","country":"Portugal","latitude":41.1485,"longitude":-8.61097}]}
        """
        let place = try WeatherClient.parsePlace(Data(json.utf8))
        XCTAssertEqual(place?.label, "Porto, Distrito do Porto, Portugal")
        XCTAssertNil(try WeatherClient.parsePlace(Data("{}".utf8)))
    }
}
