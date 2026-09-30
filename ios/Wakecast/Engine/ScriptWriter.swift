import Foundation

/// Turns weather, agenda, to-dos and headlines into the persona's spoken script.
/// A port of ScriptWriter.java: same order, pauses, thresholds and Portuguese time wording.
/// The lines themselves come from shared/scripts.json via ScriptBook.
struct ScriptWriter {

    /// The settings the script depends on (a slice of Prefs, so tests can build it directly).
    struct Settings {
        var lang = "en"
        var persona = "butler"
        var name = ""
        var hasCity = true
        var todos = ""
        var headlines = 5
    }

    private let pt: Bool
    private let persona: String
    private let book: ScriptBook
    private let calendar: Calendar
    private var rng: any RandomNumberGenerator
    private var vars: [String: String] = [:]
    private var out: [ScriptLine] = []

    private init(settings: Settings, book: ScriptBook, calendar: Calendar, rng: any RandomNumberGenerator) {
        self.pt = settings.lang == "pt"
        self.persona = book.templates[settings.persona]?["en"]?["greet"] != nil ? settings.persona : "butler"
        self.book = book
        self.calendar = calendar
        self.rng = rng
    }

    static func build(settings: Settings, data: BriefingData, now: Date = Date(),
                      book: ScriptBook = .shared, calendar: Calendar = .current,
                      rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) -> [ScriptLine] {
        var w = ScriptWriter(settings: settings, book: book, calendar: calendar, rng: rng)
        return w.write(settings: settings, data: data, now: now)
    }

    /// One greeting in the persona's voice, for the "Hear it" button.
    static func sample(settings: Settings, now: Date = Date(), book: ScriptBook = .shared) -> String {
        var w = ScriptWriter(settings: settings, book: book, calendar: .current, rng: SystemRandomNumberGenerator())
        w.initVars(name: settings.name, now: now)
        return w.pick("greet")
    }

    // MARK: - Script

    private mutating func write(settings: Settings, data: BriefingData, now: Date) -> [ScriptLine] {
        initVars(name: settings.name, now: now)

        say(pick("greet"), 700)

        // Weather
        say(pick("weather"), 300)
        if !settings.hasCity {
            say(pick("noCity"), 600)
        } else if let weather = data.weather {
            self.weather(weather, now: now)
        } else {
            say(pick("noWeather"), 600)
        }

        // Agenda
        if data.noCalendarPermission {
            say(pick("noCalendar"), 600)
        } else if let agenda = data.agenda {
            self.agenda(agenda, now: now)
        }

        // To-dos
        let todos = settings.todos.split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        if !todos.isEmpty {
            say(pick("todo"), 300)
            for t in todos { say(t, 350) }
            pause(400)
        }

        // News: each headline in its own language's voice; unclear ones stay in yours.
        if settings.headlines > 0 {
            if let news = data.news {
                if !news.isEmpty {
                    say(pick("news"), 400)
                    for h in news { say(h, 550, lang: LanguageGuesser.pick(h, fallback: lang)) }
                }
            } else {
                say(pick("noNews"), 600)
            }
        }

        say(pick("outro"), 0)
        return out
    }

    private mutating func initVars(name: String, now: Date) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        vars["name"] = trimmed.isEmpty ? (book.persona(persona)?.address[lang] ?? "") : trimmed
        vars["time"] = time(now)
        vars["day"] = formatted(now, pt ? "EEEE, d 'de' MMMM" : "EEEE, MMMM d", locale: pt ? "pt_PT" : "en_US")
    }

    private mutating func weather(_ w: Weather, now: Date) {
        let temp = Int(w.temp.rounded())
        let feels = Int(w.feelsLike.rounded())
        let max = Int(w.max.rounded())
        let min = Int(w.min.rounded())

        var nowLine = line("now", ["temp": "\(temp)", "sky": wmo(w.code)])
        if abs(feels - temp) >= 3 { nowLine += " " + line("feels", ["feels": "\(feels)"]) }
        say(nowLine, 250)
        say(line("today", ["sky": wmo(w.dayCode), "max": "\(max)", "min": "\(min)"]), 250)

        let wet = w.rainChance >= 50 || w.rainMm >= 1.0
        vars["chance"] = "\(w.rainChance)"
        if wet, let from = w.rainFromMinute {
            setTimeVars(atMinute(from, of: now))
            say(pick("rainAt"), 250)
        } else if wet {
            say(pick("rain"), 250)
        } else if w.rainChance >= 20 {
            say(pick("rainLow"), 250)
        } else {
            say(pick("dry"), 250)
        }

        // Decision helpers
        if wet { say(pick("umbrella"), 250) }
        if max < 12 { say(pick("cold"), 250) }
        else if max < 19 || min < 10 { say(pick("jacket"), 250) }
        if max >= 30 { say(pick("hot"), 250) }
        if w.uvMax >= 6 { say(line("uv", ["uv": "\(Int(w.uvMax.rounded()))"]), 250) }
        if w.windMax >= 40 { say(line("wind", ["wind": "\(Int(w.windMax.rounded()))"]), 250) }

        if let sunset = w.sunsetMinute {
            setTimeVars(atMinute(sunset, of: now))
            say(pick("sunset"), 700)
        } else {
            pause(700)
        }
    }

    private mutating func agenda(_ events: [AgendaEvent], now: Date) {
        if events.isEmpty {
            say(pick("empty"), 600)
            return
        }
        let n = events.count
        vars["count"] = n == 1 ? tr("one event", "um compromisso") : "\(n)" + tr(" events", " compromissos")
        say(pick("agenda"), 350)

        let shown = Swift.min(n, 8)
        for e in events.prefix(shown) {
            vars["title"] = e.title.isEmpty ? tr("an untitled event", "um evento sem título") : e.title
            let place = Self.shortLocation(e.location)
            if e.allDay {
                say(pick("allDay"), 350)
                continue
            }
            setTimeVars(e.begin)
            if place.isEmpty {
                say(pick("event"), 350)
            } else {
                vars["place"] = place
                say(pick("eventAt"), 350)
            }
        }
        if n > shown { say(line("more", ["n": "\(n - shown)"]), 350) }

        if let first = events.first(where: { !$0.allDay && $0.begin > now }) {
            let gap = first.begin.timeIntervalSince(now)
            if gap < 14 * 3600 { say(line("first", ["in": duration(gap)]), 300) }
        }
        pause(500)
    }

    // MARK: - Helpers

    private var lang: String { pt ? "pt" : "en" }

    private mutating func say(_ text: String, _ pauseAfterMs: Int, lang: String? = nil) {
        guard !text.isEmpty else { return }
        out.append(ScriptLine(text: text, pauseAfterMs: pauseAfterMs, lang: lang ?? self.lang))
    }

    private mutating func pause(_ ms: Int) {
        guard let last = out.popLast() else { return }
        out.append(ScriptLine(text: last.text, pauseAfterMs: Swift.max(last.pauseAfterMs, ms), lang: last.lang))
    }

    /// Sets the given placeholders and picks a line for the slot.
    private mutating func line(_ slot: String, _ values: [String: String]) -> String {
        vars.merge(values) { _, new in new }
        return pick(slot)
    }

    private mutating func pick(_ slot: String) -> String {
        let variants = book.variants(persona: persona, lang: lang, slot: slot)
        guard !variants.isEmpty else { return "" }
        var s = variants[Int.random(in: 0..<variants.count, using: &rng)]
        for (key, value) in vars { s = s.replacingOccurrences(of: "{\(key)}", with: value) }
        return s
    }

    /// {at}, and in Portuguese {from}, for a clock time.
    private mutating func setTimeVars(_ date: Date) {
        let t = time(date)
        guard pt else {
            vars["at"] = t
            vars["from"] = t
            return
        }
        let h = calendar.component(.hour, from: date)
        let m = calendar.component(.minute, from: date)
        if h == 12 && m == 0 {
            vars["at"] = "ao " + t
            vars["from"] = "a partir do " + t
        } else if h == 0 || h == 1 {   // "à meia-noite", "à 1 e 30"
            vars["at"] = "à " + t
            vars["from"] = "a partir da " + t
        } else {
            vars["at"] = "às " + t
            vars["from"] = "a partir das " + t
        }
    }

    private func tr(_ en: String, _ ptText: String) -> String { pt ? ptText : en }

    private func atMinute(_ minuteOfDay: Int, of day: Date) -> Date {
        calendar.date(bySettingHour: minuteOfDay / 60, minute: minuteOfDay % 60, second: 0, of: day) ?? day
    }

    /// A time the speech engine reads naturally: "7:30 AM" / "7 e 30".
    private func time(_ date: Date) -> String {
        let h = calendar.component(.hour, from: date)
        let m = calendar.component(.minute, from: date)
        if !pt { return formatted(date, m == 0 ? "h a" : "h:mm a", locale: "en_US") }
        if m == 0 {
            if h == 0 { return "meia-noite" }
            if h == 12 { return "meio-dia" }
            return "\(h)" + (h == 1 ? " hora" : " horas")
        }
        return "\(h) e \(m)"
    }

    private func duration(_ interval: TimeInterval) -> String {
        let mins = Swift.max(1, Int((interval / 60).rounded()))
        let h = mins / 60, m = mins % 60
        let hs = h == 1 ? tr("1 hour", "1 hora") : "\(h)" + tr(" hours", " horas")
        let ms = m == 1 ? tr("1 minute", "1 minuto") : "\(m)" + tr(" minutes", " minutos")
        if h == 0 { return ms }
        if m == 0 { return hs }
        return hs + tr(" and ", " e ") + ms
    }

    private func formatted(_ date: Date, _ pattern: String, locale: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: locale)
        f.calendar = calendar
        f.timeZone = calendar.timeZone
        f.dateFormat = pattern
        return f.string(from: date)
    }

    /// "Rua de Exemplo 12, 4200-000 Porto, Portugal" -> "Rua de Exemplo 12"
    static func shortLocation(_ location: String) -> String {
        var s = location
        if let comma = location.firstIndex(of: ","), comma != location.startIndex {
            s = String(location[..<comma])
        }
        return s.count > 60 ? "" : s.trimmingCharacters(in: .whitespaces)
    }

    /// WMO weather codes as used by Open-Meteo.
    private func wmo(_ code: Int) -> String {
        switch code {
        case 0: return tr("clear skies", "céu limpo")
        case 1: return tr("mostly clear skies", "céu pouco nublado")
        case 2: return tr("some clouds", "céu parcialmente nublado")
        case 3: return tr("overcast skies", "céu encoberto")
        case 45, 48: return tr("fog", "nevoeiro")
        case 51, 53, 55: return tr("drizzle", "chuvisco")
        case 56, 57: return tr("freezing drizzle", "chuvisco gelado")
        case 61: return tr("light rain", "chuva fraca")
        case 63: return tr("rain", "chuva")
        case 65: return tr("heavy rain", "chuva forte")
        case 66, 67: return tr("freezing rain", "chuva gelada")
        case 71: return tr("light snow", "neve fraca")
        case 73, 77: return tr("snow", "neve")
        case 75: return tr("heavy snow", "neve forte")
        case 80: return tr("light showers", "aguaceiros fracos")
        case 81: return tr("rain showers", "aguaceiros")
        case 82: return tr("violent showers", "aguaceiros fortes")
        case 85, 86: return tr("snow showers", "aguaceiros de neve")
        case 95: return tr("thunderstorms", "trovoada")
        case 96, 99: return tr("thunderstorms with hail", "trovoada com granizo")
        default: return tr("mixed conditions", "condições variáveis")
        }
    }
}
