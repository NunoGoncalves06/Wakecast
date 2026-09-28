package io.github.wakebrief;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Weather from Open-Meteo (https://open-meteo.com): free, no API key, built on national
 * weather-service models (ECMWF, DWD, Météo-France, ...).
 */
final class WeatherClient {

    private WeatherClient() {}

    static final class Place {
        final String label;
        final double lat, lon;

        Place(String label, double lat, double lon) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    static final class Weather {
        double temp, feelsLike;
        int code;
        int dayCode;
        double max, min;
        int rainChance;          // max precipitation probability today, %
        double rainMm;           // total precipitation today, mm
        double uvMax;
        double windMax;          // km/h
        int rainFromMinute = -1; // minutes after midnight of the first hour (from now) with >= 50% rain
        int sunsetMinute = -1;   // minutes after midnight
    }

    static Place geocode(String query, String lang) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json"
                + "&language=" + lang
                + "&name=" + URLEncoder.encode(query.trim(), "UTF-8");
        JSONObject root = new JSONObject(Net.getString(url));
        JSONArray results = root.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject r = results.getJSONObject(0);
        StringBuilder label = new StringBuilder(r.getString("name"));
        String admin = r.optString("admin1", "");
        String country = r.optString("country", "");
        if (!admin.isEmpty() && !admin.equals(r.getString("name"))) label.append(", ").append(admin);
        if (!country.isEmpty()) label.append(", ").append(country);
        return new Place(label.toString(), r.getDouble("latitude"), r.getDouble("longitude"));
    }

    static Weather forecast(double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,weather_code"
                        + "&hourly=precipitation_probability"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                        + "precipitation_probability_max,precipitation_sum,uv_index_max,"
                        + "wind_speed_10m_max,sunset"
                        + "&timezone=auto&forecast_days=1",
                lat, lon);
        JSONObject root = new JSONObject(Net.getString(url));
        Weather w = new Weather();

        JSONObject cur = root.getJSONObject("current");
        w.temp = cur.getDouble("temperature_2m");
        w.feelsLike = cur.optDouble("apparent_temperature", w.temp);
        w.code = cur.optInt("weather_code", -1);

        JSONObject d = root.getJSONObject("daily");
        w.dayCode = d.getJSONArray("weather_code").optInt(0, w.code);
        w.max = d.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = d.getJSONArray("temperature_2m_min").getDouble(0);
        w.rainChance = d.getJSONArray("precipitation_probability_max").optInt(0, 0);
        w.rainMm = d.getJSONArray("precipitation_sum").optDouble(0, 0);
        w.uvMax = d.getJSONArray("uv_index_max").optDouble(0, 0);
        w.windMax = d.getJSONArray("wind_speed_10m_max").optDouble(0, 0);
        String sunset = d.getJSONArray("sunset").optString(0, "");
        if (sunset.length() >= 16) w.sunsetMinute = minuteOfDay(sunset);

        // Hourly times are in the location's local time; compute "now" in that same clock.
        int offsetSec = root.optInt("utc_offset_seconds", 0);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String nowHour = f.format(new Date(System.currentTimeMillis() + offsetSec * 1000L));

        JSONObject h = root.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time");
        JSONArray probs = h.getJSONArray("precipitation_probability");
        for (int i = 0; i < times.length() && i < probs.length(); i++) {
            String t = times.getString(i);
            if (t.length() < 16 || t.substring(0, 13).compareTo(nowHour) < 0) continue;
            if (probs.optInt(i, 0) >= 50) {
                w.rainFromMinute = minuteOfDay(t);
                break;
            }
        }
        return w;
    }

    /** "2026-09-28T19:21" -> 1161 */
    private static int minuteOfDay(String isoLocal) {
        int hh = Integer.parseInt(isoLocal.substring(11, 13));
        int mm = Integer.parseInt(isoLocal.substring(14, 16));
        return hh * 60 + mm;
    }
}
