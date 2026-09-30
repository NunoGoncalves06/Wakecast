import Foundation

/// Weather from Open-Meteo (free, no key), with the same endpoints and fields as WeatherClient.java.
enum WeatherClient {

    struct Place: Equatable {
        let label: String
        let lat: Double
        let lon: Double
    }

    static func geocode(_ query: String, lang: String) async throws -> Place? {
        var c = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        c.queryItems = [
            .init(name: "count", value: "1"), .init(name: "format", value: "json"),
            .init(name: "language", value: lang), .init(name: "name", value: query.trimmingCharacters(in: .whitespaces)),
        ]
        return try parsePlace(await Net.data(c.url!))
    }

    static func parsePlace(_ json: Data) throws -> Place? {
        struct Response: Decodable {
            struct Result: Decodable { let name: String; let admin1: String?; let country: String?; let latitude: Double; let longitude: Double }
            let results: [Result]?
        }
        guard let r = try JSONDecoder().decode(Response.self, from: json).results?.first else { return nil }
        var label = r.name
        if let admin = r.admin1, !admin.isEmpty, admin != r.name { label += ", " + admin }
        if let country = r.country, !country.isEmpty { label += ", " + country }
        return Place(label: label, lat: r.latitude, lon: r.longitude)
    }

    static func forecast(lat: Double, lon: Double) async throws -> Weather {
        let url = URL(string: String(format: "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon)
            + "&current=temperature_2m,apparent_temperature,weather_code"
            + "&hourly=precipitation_probability"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
            + "precipitation_sum,uv_index_max,wind_speed_10m_max,sunset"
            + "&timezone=auto&forecast_days=1")!
        return try parseForecast(await Net.data(url), now: Date())
    }

    static func parseForecast(_ json: Data, now: Date) throws -> Weather {
        struct Response: Decodable {
            struct Current: Decodable { let temperature_2m: Double; let apparent_temperature: Double?; let weather_code: Int? }
            struct Daily: Decodable {
                let weather_code: [Int?]; let temperature_2m_max: [Double]; let temperature_2m_min: [Double]
                let precipitation_probability_max: [Int?]; let precipitation_sum: [Double?]
                let uv_index_max: [Double?]; let wind_speed_10m_max: [Double?]; let sunset: [String?]
            }
            struct Hourly: Decodable { let time: [String]; let precipitation_probability: [Int?] }
            let utc_offset_seconds: Int?
            let current: Current
            let daily: Daily
            let hourly: Hourly
        }
        let r = try JSONDecoder().decode(Response.self, from: json)
        let code = r.current.weather_code ?? -1
        var w = Weather(
            temp: r.current.temperature_2m,
            feelsLike: r.current.apparent_temperature ?? r.current.temperature_2m,
            code: code,
            dayCode: r.daily.weather_code.first.flatMap { $0 } ?? code,
            max: r.daily.temperature_2m_max.first ?? r.current.temperature_2m,
            min: r.daily.temperature_2m_min.first ?? r.current.temperature_2m,
            rainChance: r.daily.precipitation_probability_max.first.flatMap { $0 } ?? 0,
            rainMm: r.daily.precipitation_sum.first.flatMap { $0 } ?? 0,
            uvMax: r.daily.uv_index_max.first.flatMap { $0 } ?? 0,
            windMax: r.daily.wind_speed_10m_max.first.flatMap { $0 } ?? 0,
            rainFromMinute: nil,
            sunsetMinute: r.daily.sunset.first.flatMap { $0 }.flatMap(minuteOfDay))

        // Hourly times are in the location's local time; compute "now" in that same clock.
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH"
        let nowHour = f.string(from: now.addingTimeInterval(TimeInterval(r.utc_offset_seconds ?? 0)))
        for (t, p) in zip(r.hourly.time, r.hourly.precipitation_probability) where t.count >= 16 {
            if String(t.prefix(13)) < nowHour { continue }
            if (p ?? 0) >= 50 {
                w.rainFromMinute = minuteOfDay(t)
                break
            }
        }
        return w
    }

    /// "2026-09-28T19:21" -> 1161
    static func minuteOfDay(_ isoLocal: String) -> Int? {
        let chars = Array(isoLocal)
        guard chars.count >= 16, let hh = Int(String(chars[11...12])), let mm = Int(String(chars[14...15])) else { return nil }
        return hh * 60 + mm
    }
}
