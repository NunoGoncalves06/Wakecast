import Foundation

/// What the hero ring says about tonight's sleep, from the time left until the next alarm.
/// Measured against 8 hours: full at 8 h or more, emptying below that. A port of SleepGauge.java.
struct SleepGauge: Equatable {
    enum Tone: Equatable { case blue, green, yellow, red }

    /// How full the ring is, 0...1 (1 = 8 h of sleep or more).
    let fill: Double
    let tone: Tone
    /// Big text in the ring, e.g. "6h 40m".
    let value: String
    /// Small text under it, e.g. "of sleep".
    let caption: String
    /// One short line of advice.
    let advice: String

    private static let hour: TimeInterval = 3600
    private static let goodNight: TimeInterval = 8 * hour

    /// - Parameter timeLeft: time until the next alarm, or nil when there is none.
    static func of(timeLeft: TimeInterval?) -> SleepGauge {
        guard let left = timeLeft, left >= 0, left <= 24 * hour else {
            return SleepGauge(fill: 1, tone: .blue, value: "24h+", caption: "no alarm", advice: "No alarm in the next 24 hours")
        }
        let text = shortTime(left)
        let fill = min(1, left / goodNight)
        switch left {
        case let l where l > 12 * hour: return SleepGauge(fill: 1, tone: .blue, value: text, caption: "to go", advice: "Plenty of time before bed")
        case let l where l > 10 * hour: return SleepGauge(fill: 1, tone: .green, value: text, caption: "to go", advice: "Start winding down tonight")
        case let l where l >= 8 * hour: return SleepGauge(fill: 1, tone: .green, value: text, caption: "of sleep", advice: "Good time to get ready for bed")
        case let l where l >= 7 * hour: return SleepGauge(fill: fill, tone: .yellow, value: text, caption: "of sleep", advice: "Under 8 h: head to bed now")
        case let l where l >= 6 * hour: return SleepGauge(fill: fill, tone: .yellow, value: text, caption: "of sleep", advice: "Getting short: sleep soon")
        case let l where l >= 5 * hour: return SleepGauge(fill: fill, tone: .yellow, value: text, caption: "of sleep", advice: "Short night: sleep right away")
        case let l where l >= 4 * hour: return SleepGauge(fill: fill, tone: .red, value: text, caption: "of sleep", advice: "Under 5 h: move the alarm later?")
        default: return SleepGauge(fill: fill, tone: .red, value: text, caption: "of sleep", advice: "Health risk: reschedule if you can")
        }
    }

    /// "13h 50m", "7h 05m" or "45m": short enough for the ring. Rounded down.
    static func shortTime(_ interval: TimeInterval) -> String {
        let mins = max(0, Int(interval / 60))
        let h = mins / 60, m = mins % 60
        if h == 0 { return "\(m)m" }
        return "\(h)h \(m < 10 ? "0" : "")\(m)m"
    }
}
