package io.github.wakebrief;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** All user settings, stored in SharedPreferences. */
public final class Prefs {

    public static final String DEFAULT_FEEDS_EN = "https://feeds.bbci.co.uk/news/rss.xml";
    public static final String DEFAULT_FEEDS_PT =
            "https://news.google.com/rss?hl=pt-PT&gl=PT&ceid=PT:pt-150";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("wakebrief", Context.MODE_PRIVATE);
    }

    private SharedPreferences.Editor ed() {
        return sp.edit();
    }

    public boolean enabled() { return sp.getBoolean("enabled", true); }
    public void setEnabled(boolean v) { ed().putBoolean("enabled", v).apply(); }

    public String name() { return sp.getString("name", ""); }
    public void setName(String v) { ed().putString("name", v.trim()).apply(); }

    /** "en" or "pt". */
    public String lang() {
        String def = "pt".equals(Locale.getDefault().getLanguage()) ? "pt" : "en";
        return sp.getString("lang", def);
    }
    public void setLang(String v) { ed().putString("lang", v).apply(); }

    public String persona() { return sp.getString("persona", "butler"); }
    public void setPersona(String v) { ed().putString("persona", v).apply(); }

    /** "device" (the phone's own text-to-speech) or "ai" (a downloaded AI voice, see AiVoice). */
    public String voiceEngine() { return sp.getString("voice_engine", "device"); }
    public void setVoiceEngine(String v) { ed().putString("voice_engine", v).apply(); }

    public String cityLabel() { return sp.getString("city", ""); }
    public double lat() { return Double.longBitsToDouble(sp.getLong("lat", Double.doubleToLongBits(Double.NaN))); }
    public double lon() { return Double.longBitsToDouble(sp.getLong("lon", Double.doubleToLongBits(Double.NaN))); }
    public boolean hasCity() { return !Double.isNaN(lat()) && !Double.isNaN(lon()); }
    public void setCity(String label, double lat, double lon) {
        ed().putString("city", label)
                .putLong("lat", Double.doubleToLongBits(lat))
                .putLong("lon", Double.doubleToLongBits(lon))
                .apply();
    }

    /** Raw feed list as typed by the user (one URL per line). Empty = language default. */
    public String feedsRaw() { return sp.getString("feeds", ""); }
    public void setFeedsRaw(String v) { ed().putString("feeds", v.trim()).apply(); }

    public String[] feeds() {
        String raw = feedsRaw();
        if (raw.isEmpty()) raw = "pt".equals(lang()) ? DEFAULT_FEEDS_PT : DEFAULT_FEEDS_EN;
        return raw.split("[\\s,]+");
    }

    /** Feeds actually in use: the typed list, or the language default when it's empty. */
    public String effectiveFeedsText() {
        String raw = feedsRaw();
        if (!raw.isEmpty()) return raw;
        return "pt".equals(lang()) ? DEFAULT_FEEDS_PT : DEFAULT_FEEDS_EN;
    }

    /** "system", "light" or "dark". */
    public String theme() { return sp.getString("theme", "system"); }
    public void setTheme(String v) { ed().putString("theme", v).apply(); }

    /** Color of the top card: one of Ui.HERO_KEYS ("custom" uses {@link #heroHue()}). */
    public String heroColor() { return sp.getString("hero_color", "violet"); }
    public void setHeroColor(String v) { ed().putString("hero_color", v).apply(); }

    /** Hue (0-359) for the "custom" top card color. */
    public int heroHue() { return sp.getInt("hero_hue", 265); }
    public void setHeroHue(int v) { ed().putInt("hero_hue", ((v % 360) + 360) % 360).apply(); }

    public int headlines() { return sp.getInt("headlines", 5); }
    public void setHeadlines(int v) { ed().putInt("headlines", Math.max(0, Math.min(15, v))).apply(); }

    public String todos() { return sp.getString("todos", ""); }
    public void setTodos(String v) { ed().putString("todos", v.trim()).apply(); }

    /**
     * Only alarms at a time of day in [windowStart, windowEnd) trigger a briefing, in minutes
     * after midnight (1440 = end of the day). Older versions stored whole hours, which still count.
     */
    public int windowStart() { return sp.getInt("win_start_min", sp.getInt("win_start", 4) * 60); }
    public int windowEnd() { return sp.getInt("win_end_min", sp.getInt("win_end", 12) * 60); }
    public void setWindow(int startMinute, int endMinute) {
        ed().putInt("win_start_min", Math.max(0, Math.min(1439, startMinute)))
                .putInt("win_end_min", Math.max(1, Math.min(1440, endMinute)))
                .apply();
    }

    public boolean oncePerDay() { return sp.getBoolean("once_per_day", true); }
    public void setOncePerDay(boolean v) { ed().putBoolean("once_per_day", v).apply(); }

    /** Speak on the alarm stream (uses alarm volume) instead of the media stream. */
    public boolean alarmVolume() { return sp.getBoolean("alarm_volume", true); }
    public void setAlarmVolume(boolean v) { ed().putBoolean("alarm_volume", v).apply(); }

    /** Seconds to wait after the alarm stops ringing before speaking. */
    public int delaySeconds() { return sp.getInt("delay", 3); }
    public void setDelaySeconds(int v) { ed().putInt("delay", Math.max(0, Math.min(300, v))).apply(); }

    /** yyyy-MM-dd of the last automatic briefing. */
    public String lastBriefDate() { return sp.getString("last_brief", ""); }
    public void setLastBriefDate(String v) { ed().putString("last_brief", v).apply(); }
}
