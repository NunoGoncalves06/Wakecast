import Foundation

/// Today's forecast, as WeatherClient.Weather on Android.
struct Weather: Equatable {
    var temp: Double
    var feelsLike: Double
    var code: Int
    var dayCode: Int
    var max: Double
    var min: Double
    /// Highest chance of rain today, in percent.
    var rainChance: Int
    /// Total rain today, in mm.
    var rainMm: Double
    var uvMax: Double
    /// km/h
    var windMax: Double
    /// Minutes after midnight of the first hour from now with at least 50% chance of rain, or nil.
    var rainFromMinute: Int?
    /// Minutes after midnight, or nil.
    var sunsetMinute: Int?
}

/// One calendar event, as AgendaReader.Event on Android.
struct AgendaEvent: Equatable {
    var title: String
    var location: String
    var begin: Date
    var end: Date
    var allDay: Bool
}

/// Everything the briefing reads, as ScriptWriter.Data on Android. nil means "unavailable".
struct BriefingData {
    var weather: Weather?
    var agenda: [AgendaEvent]?
    var noCalendarPermission = false
    var news: [String]?
}

/// One spoken line: the text, the silence after it, and the voice language ("en" or "pt").
struct ScriptLine: Equatable {
    var text: String
    var pauseAfterMs: Int
    var lang: String
}
