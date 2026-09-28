package io.github.wakebrief;

import android.content.Intent;
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
 * Home: just what matters day to day. The next briefing with a sleep ring, a banner only when
 * something needs attention, and what tomorrow's briefing will contain. Everything else lives in
 * Settings.
 */
public class MainActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderHero();
            renderFab();
            handler.postDelayed(this, 1_000);
        }
    };

    private Scheduler.Status status;
    private TextView heroLabel, heroTime, heroSub, heroNote, heroAdvice;
    private Ui.Ring heroRing;
    private MaterialSwitch heroSwitch;
    private MaterialButton heroAction;
    private boolean bindingHero;
    private LinearLayout attention, summary;
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
        content.addView(ui.header("Tomorrow's briefing"));
        summary = ui.group(content);

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
        renderSummary();
        renderFab();
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
    }

    // ------------------------------------------------------------------ next briefing

    private void hero() {
        LinearLayout c = ui.card(content);
        Ui.cardView(c).setCardBackgroundColor(ui.primaryContainer);
        c.setPadding(ui.dp(20), ui.dp(16), ui.dp(12), ui.dp(16));
        int on = ui.onPrimaryContainer;

        LinearLayout top = ui.row();
        heroLabel = ui.text("", com.google.android.material.R.attr.textAppearanceLabelLarge, on);
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
        body.setGravity(Gravity.TOP);
        LinearLayout texts = ui.column();
        heroTime = ui.text("", com.google.android.material.R.attr.textAppearanceDisplaySmall, on);
        ui.fitText(heroTime, 36, 24);
        texts.addView(heroTime);
        heroSub = ui.text("", com.google.android.material.R.attr.textAppearanceBodyLarge, on);
        texts.addView(heroSub);
        body.addView(texts, Ui.weight(1));

        // Sleep ring: time left until the alarm against 8 hours (see SleepGauge).
        LinearLayout ringBox = ui.column();
        ringBox.setGravity(Gravity.CENTER_HORIZONTAL);
        heroRing = new Ui.Ring(ui, 88, (on & 0x00FFFFFF) | 0x29000000, on);
        ringBox.addView(heroRing, Ui.wrap());
        heroAdvice = ui.text("", com.google.android.material.R.attr.textAppearanceLabelMedium, on);
        heroAdvice.setGravity(Gravity.CENTER);
        heroAdvice.setMaxWidth(ui.dp(112));
        heroAdvice.setPadding(0, ui.dp(6), 0, 0);
        ringBox.addView(heroAdvice, Ui.wrap());
        body.addView(ringBox, Ui.wrap());
        c.addView(body);

        heroNote = ui.text("", com.google.android.material.R.attr.textAppearanceBodyMedium, on);
        heroNote.setPadding(0, ui.dp(12), ui.dp(8), 0);
        c.addView(heroNote);
        heroAction = ui.button("", Ui.FILLED, 0, null);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = ui.dp(8);
        c.addView(heroAction, lp);
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
        long t = status.alarmTime;

        SleepGauge sleep = SleepGauge.of(t > 0 ? t - now : -1);
        heroRing.set(sleep.fill, sleep.color, sleep.value, sleep.caption);
        heroAdvice.setText(sleep.advice);

        switch (status.state) {
            case ARMED: {
                heroLabel.setText("Next briefing");
                heroTime.setText(clock(t));
                heroSub.setText(dayName(t));
                String owner = Scheduler.nextAlarmOwner(this);
                heroNote.setText("Starts when you dismiss " + (owner.isEmpty() ? "the alarm" : "the " + owner + " alarm") + ".");
                if (!status.exact) showAction("Allow exact timing", v -> SettingsActivity.open(this, SettingsActivity.PERMISSIONS));
                break;
            }
            case NO_ALARM:
                heroLabel.setText("No alarm set");
                heroTime.setText("Sleep in?");
                heroSub.setText("The briefing follows your next alarm.");
                heroNote.setText("Set an alarm in your Clock app.");
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
                heroTime.setText(clock(t));
                heroSub.setText(dayName(t));
                heroNote.setText("Outside your morning window (" + SettingsActivity.clockOfDay(this, p.windowStart())
                        + "–" + SettingsActivity.clockOfDay(this, p.windowEnd()) + "), so it stays quiet.");
                break;
            case DONE_TODAY:
                heroLabel.setText("Today");
                heroTime.setText("Done");
                heroSub.setText("You've had today's briefing.");
                heroNote.setText("The next alarm (" + clock(t) + ") is a backup, so it's skipped.");
                break;
            case OFF:
            default:
                heroLabel.setText("Briefing off");
                heroTime.setText("Paused");
                heroSub.setText("Turn it on to hear it with your alarm.");
                heroNote.setText("You can still play it any time.");
                break;
        }
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

    // ------------------------------------------------------------------ attention banner

    /** Shown only when something the app needs is missing. */
    private void renderAttention() {
        attention.removeAllViews();
        List<Setup.Item> items = Setup.items(this, p, tts, ttsReady);
        int missing = Setup.missing(items);
        if (missing == 0) return;
        int on = ui.color(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410E0B);
        StringBuilder names = new StringBuilder();
        for (Setup.Item i : items) {
            if (i.ok) continue;
            if (names.length() > 0) names.append(" · ");
            names.append(i.title);
        }
        LinearLayout texts = ui.column();
        TextView title = ui.text(missing == 1 ? "1 thing needs your attention" : missing + " things need your attention",
                com.google.android.material.R.attr.textAppearanceTitleMedium, on);
        texts.addView(title);
        texts.addView(ui.text(names, com.google.android.material.R.attr.textAppearanceBodyMedium, on));
        LinearLayout row = ui.row();
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.rightMargin = ui.dp(16);
        row.addView(ui.icon(R.drawable.ic_warning, on), lp);
        row.addView(texts, Ui.weight(1));
        row.addView(ui.icon(R.drawable.ic_chevron_right, on));
        View banner = ui.segment(row, v -> SettingsActivity.open(this, SettingsActivity.PERMISSIONS));
        Ui.cardOf(banner).setCardBackgroundColor(ui.color(com.google.android.material.R.attr.colorErrorContainer, 0xFFF9DEDC));
        Ui.Group g = ui.group(attention);
        ((LinearLayout.LayoutParams) g.getLayoutParams()).topMargin = ui.dp(8);
        g.addView(banner);
    }

    // ------------------------------------------------------------------ summary

    private void renderSummary() {
        summary.removeAllViews();
        summary.addView(ui.link(R.drawable.ic_partly_cloudy_day, "Weather",
                p.hasCity() ? p.cityLabel() : "Set your city",
                v -> SettingsActivity.open(this, SettingsActivity.WEATHER)));
        String engine = AiVoice.willUse(this, p) ? "AI voice" : "Phone voice";
        summary.addView(ui.link(R.drawable.ic_record_voice_over, "Voice",
                ScriptWriter.personaName(p.persona()) + " · " + engine + " · " + ("pt".equals(p.lang()) ? "Português" : "English"),
                v -> SettingsActivity.open(this, SettingsActivity.VOICE)));
        int feeds = p.feeds().length;
        summary.addView(ui.link(R.drawable.ic_newspaper, "News",
                p.headlines() == 0 ? "Off" : p.headlines() + " headlines · " + feeds + (feeds == 1 ? " source" : " sources"),
                v -> SettingsActivity.open(this, SettingsActivity.NEWS)));
        int todos = 0;
        for (String t : p.todos().split("\\n")) if (!t.trim().isEmpty()) todos++;
        summary.addView(ui.link(R.drawable.ic_task_alt, "To-dos",
                todos == 0 ? "None" : todos + (todos == 1 ? " reminder" : " reminders"),
                v -> SettingsActivity.open(this, SettingsActivity.TODOS)));
        summary.addView(ui.item(ui.badge(R.drawable.ic_event), "Calendar", "Today's events are read automatically", null, null));
    }

    // ------------------------------------------------------------------ formatting

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
        return new SimpleDateFormat("EEEE, d MMM", Locale.US).format(new Date(t));
    }
}
