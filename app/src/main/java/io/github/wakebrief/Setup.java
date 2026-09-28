package io.github.wakebrief;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the app needs to work reliably, and how to fix each thing that's missing. */
final class Setup {

    static final class Item {
        final String title, why, action;
        final int icon;
        final boolean ok;
        final Runnable fix;

        Item(String title, String why, String action, int icon, boolean ok, Runnable fix) {
            this.title = title;
            this.why = why;
            this.action = action;
            this.icon = icon;
            this.ok = ok;
            this.fix = fix;
        }
    }

    private Setup() {}

    /** Every requirement, done or not. {@code tts} may be null (the voice check is then skipped). */
    static List<Item> items(Activity a, Prefs p, TextToSpeech tts, boolean ttsReady) {
        List<Item> out = new ArrayList<>();
        String pkg = a.getPackageName();
        out.add(new Item("Calendar", "To read today's events", "Allow", R.drawable.ic_event,
                a.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
                () -> a.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1)));
        if (Build.VERSION.SDK_INT >= 33) {
            out.add(new Item("Notifications", "Play and Stop buttons while it talks", "Allow",
                    R.drawable.ic_notifications,
                    a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    () -> a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2)));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            out.add(new Item("Alarms & reminders", "To start right on time", "Allow", R.drawable.ic_alarm,
                    a.getSystemService(AlarmManager.class).canScheduleExactAlarms(),
                    () -> a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + pkg)))));
        }
        out.add(new Item("Battery: unrestricted", "So the phone doesn't close it overnight", "Allow",
                R.drawable.ic_battery_charging_full,
                a.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg),
                () -> a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)))));
        out.add(new Item("City", "Needed for the weather", "Set", R.drawable.ic_location_on, p.hasCity(),
                () -> SettingsActivity.open(a, SettingsActivity.WEATHER)));
        if (tts != null && ttsReady && !AiVoice.willUse(a, p)) { // the AI voice doesn't need it
            Locale loc = ScriptWriter.locale(p.lang(), p.persona());
            out.add(new Item("pt".equals(p.lang()) ? "Portuguese voice" : "English voice",
                    "Your phone's text-to-speech voice", "Install", R.drawable.ic_record_voice_over,
                    tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE,
                    () -> {
                        try {
                            a.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                        } catch (Exception e) {
                            a.startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }));
        }
        return out;
    }

    static int missing(List<Item> items) {
        int n = 0;
        for (Item i : items) if (!i.ok) n++;
        return n;
    }
}
