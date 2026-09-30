import XCTest
@testable import Wakecast

/// Pins the iOS ScriptWriter to the Android rules (ScriptWriter.java): order, thresholds,
/// pauses, language per headline and Portuguese time wording.
final class ScriptWriterTests: XCTestCase {

    private let book = ScriptBook.load(from: .main)
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Lisbon")!
        return c
    }()

    /// 1 October 2026, 06:30 in Lisbon.
    private var now: Date { date(hour: 6, minute: 30) }

    private func date(hour: Int, minute: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: 1, hour: hour, minute: minute))!
    }

    private func build(_ settings: ScriptWriter.Settings, _ data: BriefingData, seed: UInt64 = 1) -> [ScriptLine] {
        ScriptWriter.build(settings: settings, data: data, now: now, book: book, calendar: calendar, rng: SeededGenerator(seed: seed))
    }

    /// True when `text` is one of the slot's variants, with any placeholder standing for anything.
    private func matches(_ text: String, persona: String = "butler", lang: String = "en", slot: String) -> Bool {
        book.variants(persona: persona, lang: lang, slot: slot).contains { variant in
            let parts = variant.components(separatedBy: CharacterSet(charactersIn: "{}"))
            // Even indexes are literal text, odd indexes are placeholder names.
            let pattern = parts.enumerated().map { $0.offset % 2 == 0 ? NSRegularExpression.escapedPattern(for: $0.element) : ".*" }.joined()
            return text.range(of: "^" + pattern + "$", options: .regularExpression) != nil
        }
    }

    private func lines(_ script: [ScriptLine], slot: String, persona: String = "butler", lang: String = "en") -> [ScriptLine] {
        script.filter { matches($0.text, persona: persona, lang: lang, slot: slot) }
    }

    private let coldWetDay = Weather(temp: 9, feelsLike: 5, code: 61, dayCode: 63, max: 11, min: 6,
                                     rainChance: 80, rainMm: 3, uvMax: 7, windMax: 45,
                                     rainFromMinute: 15 * 60, sunsetMinute: 19 * 60 + 21)

    func testTemplatesLoaded() {
        XCTAssertEqual(book.personas.map(\.key), ["butler", "sergeant", "radio", "pirate", "zen"])
        let count = book.templates.values.flatMap { $0.values }.reduce(0) { $0 + $1.count }
        XCTAssertEqual(count, 300)
    }

    func testEnglishBriefingFollowsTheAndroidRules() {
        var agenda: [AgendaEvent] = (0..<9).map { i in
            AgendaEvent(title: "Meeting \(i)", location: "", begin: date(hour: 9 + i, minute: 30),
                        end: date(hour: 10 + i, minute: 0), allDay: false)
        }
        agenda[0].location = "Room B, Floor 2, Porto"
        let data = BriefingData(weather: coldWetDay, agenda: agenda, news: [
            "Government says the new budget is ready",
            "Governo aprova novo orçamento para o próximo ano",
        ])
        let settings = ScriptWriter.Settings(lang: "en", persona: "butler", name: "Alex",
                                             todos: "Take the bins out\n\n  Call the dentist  ", headlines: 5)
        let script = build(settings, data)

        XCTAssertTrue(matches(script[0].text, slot: "greet"))
        XCTAssertTrue(script[0].text.contains("Alex"))
        XCTAssertEqual(script[0].pauseAfterMs, 700)

        // Weather: wet, cold (max < 12 means coat, not jacket), UV 7, wind 45, rain from 3 PM.
        XCTAssertEqual(lines(script, slot: "umbrella").count, 1)
        XCTAssertEqual(lines(script, slot: "cold").count, 1)
        XCTAssertTrue(lines(script, slot: "jacket").isEmpty)
        XCTAssertTrue(lines(script, slot: "uv").first?.text.contains("7") ?? false)
        XCTAssertTrue(lines(script, slot: "wind").first?.text.contains("45") ?? false)
        XCTAssertTrue(lines(script, slot: "rainAt").first?.text.contains("3 PM") ?? false)
        XCTAssertTrue(lines(script, slot: "sunset").first?.text.contains("7:21 PM") ?? false)
        // Feels-like (5 vs 9) differs by 3 or more, so it is appended to the "now" line.
        let feels = book.variants(persona: "butler", lang: "en", slot: "feels").map { $0.replacingOccurrences(of: "{feels}", with: "5") }
        XCTAssertTrue(script.contains { line in feels.contains { line.text.hasSuffix(" " + $0) } })

        // Agenda: 8 of the 9 events read out (the first with its short location), then "1 more",
        // then the first event is 3 hours away.
        let meetings = script.filter { $0.text.contains("Meeting") }
        XCTAssertEqual(meetings.count, 8)
        XCTAssertTrue(meetings[0].text.contains("Room B"))
        XCTAssertFalse(meetings[0].text.contains("Floor 2"))
        XCTAssertTrue(meetings[0].text.contains("9:30 AM"))
        XCTAssertTrue(lines(script, slot: "more").first?.text.contains("1") ?? false)
        XCTAssertTrue(lines(script, slot: "first").first?.text.contains("3 hours") ?? false)

        // To-dos: blank lines dropped, trimmed, longer pause after the last one.
        let bins = script.firstIndex { $0.text == "Take the bins out" }!
        XCTAssertEqual(script[bins + 1].text, "Call the dentist")
        XCTAssertEqual(script[bins + 1].pauseAfterMs, 400)

        // News: each headline in its own language.
        XCTAssertEqual(script.first { $0.text.hasPrefix("Government") }?.lang, "en")
        XCTAssertEqual(script.first { $0.text.hasPrefix("Governo") }?.lang, "pt")

        XCTAssertTrue(matches(script.last!.text, slot: "outro"))
        XCTAssertEqual(script.last!.pauseAfterMs, 0)
    }

    func testPortugueseTimeWording() {
        let weather = Weather(temp: 22, feelsLike: 22, code: 0, dayCode: 1, max: 25, min: 15,
                              rainChance: 0, rainMm: 0, uvMax: 3, windMax: 10,
                              rainFromMinute: nil, sunsetMinute: 21 * 60 + 5)
        let noon = AgendaEvent(title: "Almoço", location: "", begin: date(hour: 12, minute: 0),
                               end: date(hour: 13, minute: 0), allDay: false)
        let script = build(ScriptWriter.Settings(lang: "pt", persona: "zen", name: ""),
                           BriefingData(weather: weather, agenda: [noon], news: []))

        XCTAssertTrue(script[0].text.contains("alma serena"), "zen's Portuguese address when no name is set")
        XCTAssertTrue(lines(script, slot: "sunset", persona: "zen", lang: "pt").first?.text.contains("às 21 e 5") ?? false)
        XCTAssertTrue(script.first { $0.text.contains("Almoço") }?.text.contains("ao meio-dia") ?? false)
        XCTAssertTrue(lines(script, slot: "dry", persona: "zen", lang: "pt").count == 1)
        XCTAssertTrue(script.allSatisfy { $0.lang == "pt" })
    }

    func testMissingDataLines() {
        let noCity = build(ScriptWriter.Settings(hasCity: false, headlines: 5), BriefingData(noCalendarPermission: true, news: nil))
        XCTAssertEqual(lines(noCity, slot: "noCity").count, 1)
        XCTAssertEqual(lines(noCity, slot: "noCalendar").count, 1)
        XCTAssertEqual(lines(noCity, slot: "noNews").count, 1)

        let newsOff = build(ScriptWriter.Settings(headlines: 0), BriefingData(weather: nil, news: nil))
        XCTAssertEqual(lines(newsOff, slot: "noWeather").count, 1)
        XCTAssertTrue(lines(newsOff, slot: "noNews").isEmpty, "headlines set to 0 means no news section at all")
    }

    func testShortLocation() {
        XCTAssertEqual(ScriptWriter.shortLocation("Rua de Exemplo 12, 4200-000 Porto, Portugal"), "Rua de Exemplo 12")
        XCTAssertEqual(ScriptWriter.shortLocation(String(repeating: "x", count: 61)), "")
        XCTAssertEqual(ScriptWriter.shortLocation(""), "")
    }
}

final class LanguageGuesserTests: XCTestCase {
    func testGuesses() {
        XCTAssertEqual(LanguageGuesser.guess("Government says the new budget is ready"), "en")
        XCTAssertEqual(LanguageGuesser.guess("Governo aprova novo orçamento para o próximo ano"), "pt")
        XCTAssertNil(LanguageGuesser.guess("Benfica 2-1 Porto"))
        XCTAssertEqual(LanguageGuesser.pick("Benfica 2-1 Porto", fallback: "pt"), "pt")
    }
}

final class SleepGaugeTests: XCTestCase {
    func testThresholds() {
        XCTAssertEqual(SleepGauge.of(timeLeft: nil).value, "24h+")
        XCTAssertEqual(SleepGauge.of(timeLeft: 13 * 3600).tone, .blue)
        XCTAssertEqual(SleepGauge.of(timeLeft: 9 * 3600).tone, .green)
        let short = SleepGauge.of(timeLeft: 7.5 * 3600)
        XCTAssertEqual(short.tone, .yellow)
        XCTAssertEqual(short.value, "7h 30m")
        XCTAssertEqual(short.fill, 7.5 / 8, accuracy: 0.001)
        XCTAssertEqual(SleepGauge.of(timeLeft: 3 * 3600 + 5 * 60).value, "3h 05m")
        XCTAssertEqual(SleepGauge.of(timeLeft: 3 * 3600).tone, .red)
    }
}

/// Deterministic random numbers for tests (SplitMix64).
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
