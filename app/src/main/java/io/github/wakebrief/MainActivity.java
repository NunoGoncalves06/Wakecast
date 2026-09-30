package io.github.wakebrief;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.speech.tts.TextToSpeech;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home, modelled on a bedside clock plus a podcast's chapter list: the next briefing in big
 * numerals with the sleep ring, a quiet banner only when setup is unfinished, and the "running
 * order" of tomorrow's briefing with a real preview of each part. Everything else is in Settings.
 */
public class MainActivity extends BaseActivity {

    private static final int T_LABEL_LARGE = com.google.android.material.R.attr.textAppearanceLabelLarge;
    private static final int T_DISPLAY = com.google.android.material.R.attr.textAppearanceDisplayLarge;
    private static final int T_BODY_LARGE = com.google.android.material.R.attr.textAppearanceBodyLarge;
    private static final int T_BODY = com.google.android.material.R.attr.textAppearanceBodyMedium;
    private static final int T_TITLE_SMALL = com.google.android.material.R.attr.textAppearanceTitleSmall;
    private static final long PREVIEW_MAX_AGE = 30 * 60_000L;

    // Preview of the briefing's content, kept across screen rebuilds (main thread only).
    private static ScriptWriter.Data preview;
    private static long previewAt;
    private static String previewFor = "";
    private static boolean previewLoading;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderHero();
            renderFab();
            handler.postDelayed(this, 1_000);
        }
    };

    private Scheduler.Status status;
    private TextView heroLabel, heroTime, heroSub, heroAdvice, heroNote, duration;
    private View heroAdviceRow, heroDot;
    private Ui.Ring heroRing;
    private MaterialSwitch heroSwitch;
    private MaterialButton heroAction;
    private boolean bindingHero;
    private LinearLayout attention, rundown, readBy;
    private TextToSpeech tts;
    private boolean ttsReady;
    private long lastRing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitleText(getString(R.string.app_name));
        collapsing.setSubtitle(new SimpleDateFormat("EEEE, d MMMM", Locale.US).format(new Date()));
        MenuItem settings = toolbar.getMenu().add("Settings");
        settings.setIcon(R.drawable.ic_settings);
        settings.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        settings.setOnMenuItemClickListener(i -> {
            SettingsActivity.open(this, SettingsActivity.ROOT);
            return true;
        });

        hero();
        attention = ui.column();
        content.addView(attention, Ui.matchWrap());

        // "Running order ........ ≈ 2 min", then the chapters.
        LinearLayout head = ui.row();
        head.setPadding(ui.dp(28), ui.dp(24), ui.dp(28), ui.dp(8));
        head.addView(ui.text("Running order", T_TITLE_SMALL, ui.primary), Ui.weight(1));
        duration = ui.text("", T_TITLE_SMALL, ui.onSurfaceVariant);
        duration.setFontFeatureSettings("tnum");
        head.addView(duration);
        content.addView(head, Ui.matchWrap());
        rundown = ui.group(content);

        content.addView(ui.header("Read by"));
        readBy = ui.group(content);

        fab.setVisibility(View.VISIBLE);
        fab.setOnClickListener(v -> {
            if (BriefingService.active) {
                startService(new Intent(this, BriefingService.class).setAction(BriefingService.ACTION_STOP));
            } else {
                startForegroundService(new Intent(this, BriefingService.class)
                        .setAction(BriefingService.ACTION_PLAY_NOW));
                toast("Getting the weather and news…");
            }
            handler.postDelayed(this::renderFab, 300);
        });

        tts = new TextToSpeech(this, s -> {
            ttsReady = s == TextToSpeech.SUCCESS;
            runOnUiThread(this::renderAttention);
        });
        new Thread(() -> AiVoice.removeObsolete(getApplicationContext()), "voice-cleanup").start();
        Scheduler.ensurePeriodicCheck(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status = Scheduler.reschedule(this);
        lastRing = 0;
        renderHero();
        renderAttention();
        renderRundown();
        renderFab();
        loadPreview();
        handler.postDelayed(ticker, 1_000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        Scheduler.reschedule(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        renderAttention();
        renderRundown();
    }

    // ------------------------------------------------------------------ next briefing

    /** Bedside-clock card: label and switch, big numerals, sleep ring, one line of advice. */
    private void hero() {
        LinearLayout c = ui.card(content);
        c.setPadding(ui.dp(24), ui.dp(20), ui.dp(16), ui.dp(20));

        LinearLayout top = ui.row();
        heroLabel = ui.text("", T_LABEL_LARGE, ui.onSurfaceVariant);
        top.addView(heroLabel, Ui.weight(1));
        heroSwitch = new MaterialSwitch(this);
        heroSwitch.setContentDescription("Briefing with your alarm");
        heroSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (bindingHero) return;
            p.setEnabled(checked);
            status = Scheduler.reschedule(this);
            lastRing = 0;
            renderHero();
        });
        top.addView(heroSwitch);
        c.addView(top);

        LinearLayout body = ui.row();
        LinearLayout texts = ui.column();
        heroTime = ui.text("", T_DISPLAY, ui.onSurface);
        heroTime.setFontFeatureSettings("tnum"); // digits don't jump around as the minutes change
        heroTime.setIncludeFontPadding(false);
        ui.fitText(heroTime, 57, 32);
        texts.addView(heroTime);
        heroSub = ui.text("", T_BODY_LARGE, ui.onSurfaceVariant);
        heroSub.setPadding(0, ui.dp(4), 0, 0);
        texts.addView(heroSub);
        body.addView(texts, Ui.weight(1));
        // Sleep ring: time left until the alarm against 8 hours (see SleepGauge).
        heroRing = new Ui.Ring(ui, 96, (ui.onSurface & 0x00FFFFFF) | 0x1F000000, ui.onSurface);
        body.addView(heroRing, Ui.wrap());
        LinearLayout.LayoutParams bodyLp = Ui.matchWrap();
        bodyLp.topMargin = ui.dp(4);
        c.addView(body, bodyLp);

        // "● Good time to get ready for bed": the dot carries the ring's colour.
        LinearLayout advice = ui.row();
        heroDot = new View(this);
        advice.addView(heroDot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        heroAdvice = ui.text("", T_BODY, ui.onSurface);
        heroAdvice.setPadding(ui.dp(10), 0, 0, 0);
        advice.addView(heroAdvice, Ui.weight(1));
        heroAdviceRow = advice;
        LinearLayout.LayoutParams adviceLp = Ui.matchWrap();
        adviceLp.topMargin = ui.dp(16);
        c.addView(advice, adviceLp);

        heroNote = ui.text("", T_BODY, ui.onSurfaceVariant);
        LinearLayout.LayoutParams noteLp = Ui.matchWrap();
        noteLp.topMargin = ui.dp(12);
        c.addView(heroNote, noteLp);
        heroAction = ui.button("", Ui.TONAL, 0, null);
        LinearLayout.LayoutParams actionLp = Ui.wrap();
        actionLp.topMargin = ui.dp(8);
        c.addView(heroAction, actionLp);
    }

    private void renderHero() {
        if (status == null) return;
        long now = System.currentTimeMillis();
        if (lastRing != 0 && now - lastRing < 30_000) return; // the minutes don't change faster
        lastRing = now;
        bindingHero = true;
        heroSwitch.setChecked(p.enabled());
        bindingHero = false;
        heroAction.setVisibility(View.GONE);
        heroNote.setVisibility(View.GONE);
        long t = status.alarmTime;

        boolean showSleep = status.state == Scheduler.Status.State.ARMED
                || status.state == Scheduler.Status.State.OUTSIDE_WINDOW;
        heroRing.setVisibility(showSleep ? View.VISIBLE : View.GONE);
        heroAdviceRow.setVisibility(showSleep ? View.VISIBLE : View.GONE);
        if (showSleep) {
            SleepGauge sleep = SleepGauge.of(t > 0 ? t - now : -1);
            heroRing.set(sleep.fill, sleep.color, sleep.value, sleep.caption);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(sleep.color);
            heroDot.setBackground(dot);
            heroAdvice.setText(sleep.advice);
        }

        switch (status.state) {
            case ARMED: {
                heroLabel.setText("Next briefing");
                heroTime.setText(bigClock(t));
                String owner = Scheduler.nextAlarmOwner(this);
                heroSub.setText(dayName(t) + (owner.isEmpty() ? "" : " · " + owner + " alarm"));
                if (!status.exact) {
                    note("Android may start it a little late.");
                    showAction("Allow exact timing", v -> SettingsActivity.open(this, SettingsActivity.PERMISSIONS));
                }
                break;
            }
            case NO_ALARM:
                heroLabel.setText("No alarm set");
                heroTime.setText("Sleep in");
                heroSub.setText("The briefing follows your next alarm.");
                showAction("Open Clock", v -> {
                    try {
                        startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
                    } catch (Exception e) {
                        toast("Couldn't open the Clock app.");
                    }
                });
                break;
            case OUTSIDE_WINDOW:
                heroLabel.setText("Next alarm · no briefing");
                heroTime.setText(bigClock(t));
                heroSub.setText(dayName(t));
                note("Outside your morning window (" + SettingsActivity.clockOfDay(this, p.windowStart())
                        + "–" + SettingsActivity.clockOfDay(this, p.windowEnd()) + "), so it stays quiet.");
                showAction("Change window", v -> SettingsActivity.open(this, SettingsActivity.SCHEDULE));
                break;
            case DONE_TODAY:
                heroLabel.setText("Today");
                heroTime.setText("Done");
                heroSub.setText("Next alarm " + clock(t) + " is a backup, so it's skipped.");
                break;
            case OFF:
            default:
                heroLabel.setText("Briefing off");
                heroTime.setText("Paused");
                heroSub.setText("Turn it on to hear it with your alarm.");
                break;
        }
    }

    private void note(String s) {
        heroNote.setText(s);
        heroNote.setVisibility(View.VISIBLE);
    }

    private void showAction(String label, View.OnClickListener onClick) {
        heroAction.setText(label);
        heroAction.setOnClickListener(onClick);
        heroAction.setVisibility(View.VISIBLE);
    }

    private void renderFab() {
        boolean active = BriefingService.active;
        fab.setText(active ? "Stop" : "Play briefing");
        fab.setIconResource(active ? R.drawable.ic_stop : R.drawable.ic_play_arrow);
    }

    // ------------------------------------------------------------------ setup banner

    /** Shown only while setup is unfinished; a calm tertiary tone, not an error. */
    private void renderAttention() {
        attention.removeAllViews();
        List<Setup.Item> items = Setup.items(this, p, tts, ttsReady);
        int missing = Setup.missing(items);
        if (missing == 0) return;
        int on = ui.color(com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF31111D);
        StringBuilder names = new StringBuilder(missing == 1 ? "1 step left" : missing + " steps left");
        for (Setup.Item i : items) if (!i.ok) names.append(" · ").append(i.title);

        LinearLayout texts = ui.column();
        texts.addView(ui.text("Finish setting up", com.google.android.material.R.attr.textAppearanceTitleMedium, on));
        texts.addView(ui.text(names, T_BODY, on));
        LinearLayout row = ui.row();
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.rightMargin = ui.dp(16);
        row.addView(ui.icon(R.drawable.ic_verified_user, on), lp);
        row.addView(texts, Ui.weight(1));
        row.addView(ui.icon(R.drawable.ic_chevron_right, on));
        View banner = ui.segment(row, v -> SettingsActivity.open(this, SettingsActivity.PERMISSIONS));
        Ui.cardOf(banner).setCardBackgroundColor(
                ui.color(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4));
        Ui.Group g = ui.group(attention);
        ((LinearLayout.LayoutParams) g.getLayoutParams()).topMargin = ui.dp(8);
        g.addView(banner);
    }

    // ------------------------------------------------------------------ running order

    /** The briefing's parts in the order they're read, each with a preview of its content. */
    private void renderRundown() {
        rundown.removeAllViews();
        readBy.removeAllViews();

        long day = status != null && status.alarmTime > 0 ? status.alarmTime : System.currentTimeMillis();
        boolean calendarOk = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        List<AgendaReader.Event> events = null;
        if (calendarOk) {
            try {
                events = AgendaReader.onDay(this, day);
            } catch (RuntimeException e) {
                events = null;
            }
        }

        chapter(1, "Weather", weatherLine(), SettingsActivity.WEATHER);
        chapter(2, dayName(day).equals("Today") ? "Your day" : dayName(day), agendaLine(calendarOk, events),
                calendarOk ? null : SettingsActivity.PERMISSIONS);
        chapter(3, "To-dos", todoLine(), SettingsActivity.TODOS);
        chapter(4, "News", newsLine(), SettingsActivity.NEWS);

        String engine = AiVoice.willUse(this, p) ? "AI voice" : "Phone voice";
        readBy.addView(ui.link(R.drawable.ic_record_voice_over, ScriptWriter.personaName(p.persona()),
                engine + " · " + ("pt".equals(p.lang()) ? "Português" : "English"),
                v -> SettingsActivity.open(this, SettingsActivity.VOICE)));

        duration.setText(estimate(calendarOk, events));
    }

    /** A numbered chapter row; opens {@code page} in Settings when set. */
    private void chapter(int n, String title, String preview, String page) {
        TextView marker = ui.text(String.valueOf(n), T_TITLE_SMALL, ui.primary);
        marker.setGravity(Gravity.CENTER);
        marker.setFontFeatureSettings("tnum");
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(ui.dp(1.5f), ui.primary);
        marker.setBackground(ring);
        marker.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        View trailing = page == null ? null : ui.icon(R.drawable.ic_chevron_right, ui.onSurfaceVariant);
        rundown.addView(ui.item(marker, title, preview, trailing,
                page == null ? null : v -> SettingsActivity.open(this, page)));
    }

    private String weatherLine() {
        if (!p.hasCity()) return "Set your city";
        if (preview == null) return previewLoading ? "Checking the forecast…" : "Forecast not loaded yet";
        WeatherClient.Weather w = preview.weather;
        if (w == null) return "Couldn't reach the forecast just now";
        String city = p.cityLabel();
        int comma = city.indexOf(',');
        if (comma > 0) city = city.substring(0, comma);
        String sky = condition(w.code);
        StringBuilder s = new StringBuilder("Now in ").append(city).append(" · ")
                .append(Math.round(w.temp)).append("°, ").append(sky)
                .append(" · high ").append(Math.round(w.max)).append("°");
        boolean wetAlready = sky.contains("rain") || sky.contains("shower") || sky.contains("drizzle") || sky.contains("thunder");
        if (w.rainChance >= 50 && !wetAlready) s.append(" · rain likely");
        return s.toString();
    }

    private String agendaLine(boolean calendarOk, List<AgendaReader.Event> events) {
        if (!calendarOk) return "Allow calendar access to hear your events";
        if (events == null) return "Couldn't read the calendar";
        if (events.isEmpty()) return "Nothing on the calendar";
        String count = events.size() == 1 ? "1 event" : events.size() + " events";
        for (AgendaReader.Event e : events) {
            if (!e.allDay) return count + " · first at " + clock(e.begin) + ", " + shorten(title(e), 32);
        }
        return count + " · " + shorten(title(events.get(0)), 40);
    }

    private static String title(AgendaReader.Event e) {
        return e.title.isEmpty() ? "untitled" : e.title;
    }

    private String todoLine() {
        String first = null;
        int n = 0;
        for (String t : p.todos().split("\\n")) {
            if (t.trim().isEmpty()) continue;
            if (first == null) first = t.trim();
            n++;
        }
        if (n == 0) return "None. Add reminders to hear them every morning";
        return shorten(first, 40) + (n > 1 ? "  +" + (n - 1) + " more" : "");
    }

    private String newsLine() {
        int n = p.headlines();
        if (n == 0) return "Off";
        String count = n == 1 ? "1 headline" : n + " headlines";
        if (preview == null || preview.news == null || preview.news.isEmpty()) return count;
        return count + " · “" + shorten(preview.news.get(0), 44) + "”";
    }

    /** Rough length of the spoken briefing, from its script: words at the voice's pace plus pauses. */
    private String estimate(boolean calendarOk, List<AgendaReader.Event> events) {
        if (preview == null) return "";
        ScriptWriter.Data d = new ScriptWriter.Data();
        d.weather = preview.weather;
        d.news = preview.news;
        d.agenda = events;
        d.noCalendarPermission = !calendarOk;
        try {
            float wordsPerSecond = 2.5f * ScriptWriter.rate(p.persona());
            float secs = 0;
            for (ScriptWriter.Line l : ScriptWriter.build(p, d)) {
                secs += l.text.trim().split("\\s+").length / wordsPerSecond + l.pauseAfterMs / 1000f;
            }
            return "≈ " + Math.max(1, Math.round(secs / 60f)) + " min";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Downloads the weather and headlines for the preview, at most every 30 minutes. */
    private void loadPreview() {
        String key = p.lang() + "|" + p.cityLabel() + "|" + String.join(",", p.feeds()) + "|" + p.headlines();
        long now = System.currentTimeMillis();
        if (previewLoading) return;
        if (preview != null && key.equals(previewFor) && now - previewAt < PREVIEW_MAX_AGE) return;
        previewLoading = true;
        renderRundown();
        Prefs prefs = p;
        new Thread(() -> {
            ScriptWriter.Data d = new ScriptWriter.Data();
            if (prefs.hasCity()) {
                try {
                    d.weather = WeatherClient.forecast(prefs.lat(), prefs.lon());
                } catch (Exception ignored) {
                    // shown as "couldn't reach the forecast"
                }
            }
            if (prefs.headlines() > 0) d.news = NewsClient.headlines(prefs.feeds(), prefs.headlines(), prefs.lang());
            handler.post(() -> {
                preview = d;
                previewAt = System.currentTimeMillis();
                previewFor = key;
                previewLoading = false;
                if (!isDestroyed()) renderRundown();
            });
        }, "home-preview").start();
    }

    // ------------------------------------------------------------------ formatting

    /** The big clock: like a clock app, AM/PM is set small next to the digits. */
    private CharSequence bigClock(long t) {
        String s = clock(t);
        int space = s.indexOf(' ');
        if (space < 0) return s;
        android.text.SpannableString span = new android.text.SpannableString(s);
        span.setSpan(new android.text.style.RelativeSizeSpan(0.4f), space, s.length(), 0);
        return span;
    }

    private String clock(long t) {
        return new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", Locale.US).format(new Date(t));
    }

    private static String dayName(long t) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(t);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) {
            int diff = b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
            if (diff == 0) return "Today";
            if (diff == 1) return "Tomorrow";
        }
        return new SimpleDateFormat("EEEE", Locale.US).format(new Date(t));
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    /** WMO weather code as a few words, for the preview line. */
    private static String condition(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "overcast";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code == 85 || code == 86) return "snow showers";
        if (code >= 95) return "thunderstorms";
        return "mixed skies";
    }
}
