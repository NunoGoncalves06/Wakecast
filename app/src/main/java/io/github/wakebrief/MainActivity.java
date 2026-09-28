package io.github.wakebrief;

import android.Manifest;
import androidx.fragment.app.FragmentActivity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * The only screen. Everything saves as you change it; nothing is sent anywhere except the
 * weather and news requests themselves.
 */
public class MainActivity extends FragmentActivity {

    private static final String[] LANG_KEYS = {"en", "pt"};
    private static final String[] THEME_KEYS = {"system", "light", "dark"};

    /** One-tap news sources (all checked to answer without redirects). */
    private static final String[][] SOURCES = {
            {"BBC News", "https://feeds.bbci.co.uk/news/rss.xml"},
            {"BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"},
            {"The Guardian", "https://www.theguardian.com/world/rss"},
            {"Google News PT", Prefs.DEFAULT_FEEDS_PT},
            {"RTP Notícias", "https://www.rtp.pt/noticias/rss"},
            {"Público", "https://feeds.feedburner.com/PublicoRSS"},
            {"Observador", "https://observador.pt/feed/"},
            {"Notícias ao Minuto", "https://www.noticiasaominuto.com/rss/ultima-hora"},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderHero();
            handler.postDelayed(this, 30_000);
        }
    };

    private Prefs p;
    private Ui ui;
    private ScrollView scroll;
    private Scheduler.Status status;

    // hero
    private TextView heroLabel, heroTime, heroSub, heroNote, heroAction;
    private Ui.Ring heroRing;
    private LinearLayout heroRingBox, heroCard;
    private TextView heroAdvice;
    private GradientDrawable heroBg;
    private final List<View> swatches = new ArrayList<>();
    private Switch heroSwitch;
    private boolean bindingHero;

    // sections
    private LinearLayout checklist;
    private final List<View> personaRows = new ArrayList<>();
    private TextView sampleButton;
    private LinearLayout aiVoiceBox;
    private final Object sampleLock = new Object();
    private OfflineTts sampleEngine;          // guarded by sampleLock
    private String sampleEngineId;            // guarded by sampleLock
    private volatile PcmPlayer samplePlayer;
    private LinearLayout weatherCard;
    private EditText cityInput;
    private TextView cityResult;
    private EditText feedsInput;
    private TextView checkFeedsButton, feedsStatus;
    private String lastCheckedFeeds = "";
    private final List<TextView> chips = new ArrayList<>();
    private boolean bindingFeeds;

    private TextToSpeech tts;
    private boolean ttsReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        p = new Prefs(this);
        boolean dark = Ui.isDark(this, p.theme());
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
        ui = new Ui(this, dark);

        LinearLayout frame = ui.column();
        frame.setFitsSystemWindows(true);
        frame.setBackground(ui.glowBackground());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28));
        scroll.addView(content);
        frame.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        frame.addView(bottomBar());
        setContentView(frame);
        getWindow().getDecorView().setBackgroundColor(ui.bg);
        styleSystemBars();

        content.addView(header());
        content.addView(hero(), ui.gapTop(18));
        content.addView(checklistCard(), ui.gapTop(18));
        content.addView(voiceCard(), ui.gapTop(18));
        weatherCard = weatherCard();
        content.addView(weatherCard, ui.gapTop(18));
        content.addView(newsCard(), ui.gapTop(18));
        content.addView(todoCard(), ui.gapTop(18));
        content.addView(scheduleCard(), ui.gapTop(18));
        content.addView(appearanceCard(), ui.gapTop(18));
        TextView footer = ui.text("Free and open. Weather from Open-Meteo · voice from your phone "
                + "or a free AI voice that runs on it · no accounts.", 11.5f, ui.muted);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(ui.dp(12), ui.dp(22), ui.dp(12), 0);
        content.addView(footer);
        if (savedInstanceState != null) { // keep the place after a color or theme change
            int y = savedInstanceState.getInt("scrollY");
            scroll.post(() -> scroll.scrollTo(0, y));
        }

        tts = new TextToSpeech(this, s -> {
            ttsReady = s == TextToSpeech.SUCCESS;
            runOnUiThread(this::refreshChecklist);
        });
        VoiceDownloader.setListener(() -> {
            renderAiVoice();
            refreshChecklist();
        });
        new Thread(() -> AiVoice.removeObsolete(getApplicationContext()), "voice-cleanup").start();
        Scheduler.ensurePeriodicCheck(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("scrollY", scroll.getScrollY());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        handler.postDelayed(ticker, 30_000);
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
        VoiceDownloader.setListener(null); // the download itself carries on
        stopSample();
        new Thread(() -> {
            synchronized (sampleLock) {
                if (sampleEngine != null) sampleEngine.release();
                sampleEngine = null;
            }
        }).start();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshAll();
    }

    private void refreshAll() {
        status = Scheduler.reschedule(this);
        renderHero();
        refreshChecklist();
    }

    // ================================================================== header & hero

    private View header() {
        LinearLayout r = ui.row();
        r.setPadding(0, ui.dp(10), 0, ui.dp(2));

        // Avatar: your initial (or a person icon) on lavender, like the kit's profile photo.
        LinearLayout avatar = ui.row();
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = ui.shape(ui.accentSoft, 0, 0);
        circle.setShape(GradientDrawable.OVAL);
        avatar.setBackground(circle);
        String name = p.name().trim();
        if (name.isEmpty()) {
            avatar.addView(ui.icon(R.drawable.ic_person, ui.accent, 24));
        } else {
            avatar.addView(ui.heavy(name.substring(0, 1).toUpperCase(Locale.ROOT), 19, ui.accent));
        }
        r.addView(avatar, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        LinearLayout texts = ui.column();
        texts.setPadding(ui.dp(14), 0, 0, 0);
        texts.addView(ui.text(greeting(), 13.5f, ui.muted));
        TextView title = ui.bold(name.isEmpty() ? "Wake Briefing" : name, 21, ui.text);
        title.setPadding(0, ui.dp(3), 0, 0);
        texts.addView(title);
        r.addView(texts, Ui.weight(1));

        int[] icons = {R.drawable.ic_contrast, R.drawable.ic_light_mode, R.drawable.ic_dark_mode};
        int cur = Math.max(0, Arrays.asList(THEME_KEYS).indexOf(p.theme()));
        LinearLayout themeBtn = ui.row();
        themeBtn.setGravity(Gravity.CENTER);
        themeBtn.setContentDescription("Switch theme: system, light or dark");
        themeBtn.setBackground(ui.pressable(ui.surface, 16, 0));
        ui.lift(themeBtn, 3);
        themeBtn.addView(ui.icon(icons[cur], ui.text, 22));
        themeBtn.setOnClickListener(v -> setThemePref(THEME_KEYS[(cur + 1) % THEME_KEYS.length]));
        r.addView(themeBtn, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
        return r;
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 5 ? "Up late?" : h < 12 ? "Good morning!" : h < 18 ? "Good afternoon!" : "Good evening!";
    }

    /**
     * The kit's "Your today's task almost done!" card, in the color picked under Appearance:
     * the next briefing on the left, a sleep ring on the right.
     */
    private View hero() {
        LinearLayout c = ui.column();
        heroCard = c;
        heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ui.heroStart, ui.heroEnd});
        heroBg.setCornerRadius(ui.dp(24));
        c.setBackground(heroBg);
        c.setElevation(ui.dp(12));
        applyHeroColor();
        c.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(22));

        LinearLayout top = ui.row();
        heroLabel = ui.medium("", 13.5f, Ui.HERO_MUTED);
        top.addView(heroLabel, Ui.weight(1));
        heroSwitch = ui.toggle(p.enabled(), true);
        heroSwitch.setContentDescription("Morning briefing on or off");
        heroSwitch.setOnCheckedChangeListener((b, on) -> {
            if (bindingHero) return;
            p.setEnabled(on);
            refreshAll();
        });
        top.addView(heroSwitch);
        c.addView(top);

        LinearLayout body = ui.row();
        body.setGravity(Gravity.TOP); // the time stays where it was, whatever the ring's height
        LinearLayout texts = ui.column();
        heroTime = ui.heavy("", 40, Ui.HERO_TEXT);
        heroTime.setPadding(0, ui.dp(4), 0, 0);
        ui.fitText(heroTime, 40, 26, null); // never wrap "8:30 / AM"
        texts.addView(heroTime);
        heroSub = ui.text("", 14, Ui.HERO_MUTED);
        heroSub.setPadding(0, ui.dp(6), 0, 0);
        texts.addView(heroSub);
        body.addView(texts, Ui.weight(1));

        // Sleep ring: time left until the alarm, measured against 8 hours, colored by how much
        // sleep that leaves (see SleepGauge), with one line of advice under it.
        heroRingBox = ui.column();
        heroRingBox.setGravity(Gravity.CENTER_HORIZONTAL);
        heroRing = new Ui.Ring(ui, 0x40FFFFFF, 0xFFFFFFFF, Ui.HERO_TEXT);
        heroRingBox.addView(heroRing, new LinearLayout.LayoutParams(ui.dp(86), ui.dp(86)));
        heroAdvice = ui.bold("", 11.5f, Ui.HERO_TEXT);
        heroAdvice.setGravity(Gravity.CENTER);
        heroAdvice.setMaxWidth(ui.dp(112));
        heroAdvice.setMaxLines(3);
        heroAdvice.setPadding(0, ui.dp(8), 0, 0);
        heroRingBox.addView(heroAdvice, Ui.wrap());
        LinearLayout.LayoutParams ringLp = Ui.wrap();
        ringLp.leftMargin = ui.dp(8);
        body.addView(heroRingBox, ringLp);
        c.addView(body);

        heroNote = ui.text("", 13, Ui.HERO_MUTED);
        heroNote.setPadding(0, ui.dp(14), 0, 0);
        c.addView(heroNote);

        // Like the kit's "View Task" button: lavender with violet text.
        heroAction = ui.pill("", 0xFF5F33E1, 0xFFEEE9FF, null);
        heroAction.setMinHeight(ui.dp(42));
        c.addView(heroAction, ui.wrapGapTop(16));
        return c;
    }

    private void renderHero() {
        if (status == null) return;
        bindingHero = true;
        heroSwitch.setChecked(p.enabled());
        bindingHero = false;
        heroAction.setVisibility(View.GONE);
        heroTime.setTextSize(40);
        long t = status.alarmTime;

        // The ring is about tonight's sleep, whatever the briefing is doing.
        SleepGauge sleep = SleepGauge.of(t > 0 ? t - System.currentTimeMillis() : -1);
        heroRing.setArcColor(sleep.color);
        heroRing.set(sleep.fill, sleep.value, sleep.caption);
        heroAdvice.setText(sleep.advice);

        switch (status.state) {
            case ARMED: {
                heroLabel.setText("Next briefing");
                heroTime.setText(clock(t));
                heroSub.setText(dayName(t));
                String owner = Scheduler.nextAlarmOwner(this);
                heroNote.setText("I'll fetch everything a minute early, then start talking as soon "
                        + "as you dismiss " + (owner.isEmpty() ? "the alarm" : "the " + owner + " alarm") + ".");
                if (!status.exact) {
                    showHeroAction("Timing may drift · Allow exact alarms", v -> openExactAlarmSettings());
                }
                break;
            }
            case NO_ALARM:
                heroLabel.setText("No alarm set");
                heroTime.setTextSize(30);
                heroTime.setText("Sleep in?");
                heroSub.setText("Set an alarm and the briefing follows it automatically.");
                heroNote.setText("No alarm means no briefing. Nothing runs in the background.");
                showHeroAction("Open Clock", v -> openClock());
                break;
            case OUTSIDE_WINDOW:
                heroLabel.setText("Next alarm · no briefing");
                heroTime.setText(clock(t));
                heroSub.setText(dayName(t) + " · in " + until(t));
                heroNote.setText("This alarm is outside your morning window (" + clockOfDay(p.windowStart())
                        + "–" + clockOfDay(p.windowEnd()) + "), so it stays silent. "
                        + "Change the window under Schedule.");
                break;
            case DONE_TODAY:
                heroLabel.setText("Today");
                heroTime.setTextSize(30);
                heroTime.setText("Done ✓");
                heroSub.setText("You've had today's briefing.");
                heroNote.setText("Your next alarm (" + clock(t) + ") is a backup, so it's skipped. "
                        + "Tap Play below to hear it again.");
                break;
            case OFF:
            default:
                heroLabel.setText("Paused");
                heroTime.setTextSize(30);
                heroTime.setText("Briefing off");
                heroSub.setText("Flip the switch to get briefed with your alarm.");
                heroNote.setText("You can still play it any time with the button below.");
                break;
        }
    }

    private void showHeroAction(String label, View.OnClickListener onClick) {
        heroAction.setText(label);
        heroAction.setOnClickListener(onClick);
        heroAction.setVisibility(View.VISIBLE);
    }

    private String clock(long t) {
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(t));
    }

    private String dayName(long t) {
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

    /** Paints the top card in the color chosen under Appearance. */
    private void applyHeroColor() {
        int[] g = Ui.heroGradient(p.heroColor(), p.heroHue());
        heroBg.setColors(g);
        if (Build.VERSION.SDK_INT >= 28) {
            heroCard.setOutlineSpotShadowColor(g[0]);
            heroCard.setOutlineAmbientShadowColor(g[0]);
        }
    }

    private static String until(long t) {
        long mins = Math.max(0, (t - System.currentTimeMillis()) / 60_000);
        long h = mins / 60;
        long m = mins % 60;
        if (h == 0) return m + " min";
        if (h >= 24) return (h / 24) + " d " + (h % 24) + " h";
        return h + " h " + m + " min";
    }

    // ================================================================== checklist

    private View checklistCard() {
        checklist = ui.column();
        return checklist;
    }

    private void refreshChecklist() {
        if (checklist == null) return;
        checklist.removeAllViews();
        checklist.setBackground(ui.shape(ui.surface, 20, 0));
        ui.lift(checklist, 3);
        checklist.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));

        String pkg = getPackageName();
        List<Object[]> todo = new ArrayList<>(); // {title, why, button label, Runnable, icon, color}
        int total = 0;

        total++;
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            todo.add(new Object[]{"Calendar access", "So I can read today's events", "Allow",
                    (Runnable) () -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1),
                    R.drawable.ic_event, Ui.BLUE});
        }
        if (Build.VERSION.SDK_INT >= 33) {
            total++;
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                todo.add(new Object[]{"Notifications", "Play and Stop buttons while I talk", "Allow",
                        (Runnable) () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2),
                        R.drawable.ic_notifications, Ui.ORANGE});
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            total++;
            if (!getSystemService(AlarmManager.class).canScheduleExactAlarms()) {
                todo.add(new Object[]{"Exact alarms", "Start right on time", "Allow",
                        (Runnable) this::openExactAlarmSettings, R.drawable.ic_alarm, Ui.VIOLET});
            }
        }
        total++;
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg)) {
            todo.add(new Object[]{"Battery: unrestricted", "Stops your phone closing me overnight", "Fix",
                    (Runnable) () -> startActivity(new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg))),
                    R.drawable.ic_battery_charging_full, Ui.GREEN});
        }
        total++;
        if (!p.hasCity()) {
            todo.add(new Object[]{"Your city", "Needed for the weather", "Set", (Runnable) this::focusCity,
                    R.drawable.ic_location_on, Ui.PINK});
        }
        if (ttsReady && !AiVoice.willUse(this, p)) { // the AI voice doesn't need the phone's
            total++;
            Locale loc = ScriptWriter.locale(p.lang(), p.persona());
            if (tts.isLanguageAvailable(loc) < TextToSpeech.LANG_AVAILABLE) {
                todo.add(new Object[]{"pt".equals(p.lang()) ? "Portuguese voice" : "English voice",
                        "Not installed on this phone yet", "Install", (Runnable) this::openVoiceSettings,
                        R.drawable.ic_record_voice_over, Ui.YELLOW});
            }
        }

        int done = total - todo.size();
        float fraction = total == 0 ? 1f : (float) done / total;

        LinearLayout head = ui.row();
        boolean allSet = todo.isEmpty();
        head.addView(ui.tile(allSet ? R.drawable.ic_check_circle : R.drawable.ic_task_alt,
                allSet ? Ui.GREEN : Ui.VIOLET, 36));
        TextView title = ui.bold(allSet ? "All set" : "Setup", 17, ui.text);
        title.setPadding(ui.dp(12), 0, 0, 0);
        head.addView(title, Ui.weight(1));
        head.addView(ui.badge(done + " of " + total, allSet ? ui.success : ui.accent));
        checklist.addView(head);

        if (allSet) {
            checklist.addView(ui.hint("You'll hear your briefing the next time your morning alarm rings."));
        } else {
            checklist.addView(ui.progressBar(fraction, ui.accent), ui.gapTop(14));
        }
        for (Object[] item : todo) {
            Runnable action = (Runnable) item[3];
            TextView button = ui.pill((String) item[2], ui.accent, ui.accentSoft, v -> action.run());
            checklist.addView(ui.listRow((Integer) item[4], (Integer) item[5], (String) item[0],
                    (String) item[1], button), ui.gapTop(16));
        }

        TextView tip = ui.hint("Android tip: many phones close apps overnight to save battery. In App info › "
                + "Battery, allow background activity (or choose \"Unrestricted\"), and lock Wake Briefing "
                + "in Recents if your phone offers it.");
        tip.setPadding(0, ui.dp(16), 0, 0);
        checklist.addView(tip);
        TextView appInfo = ui.pill("Open App info", ui.accent, ui.accentSoft, v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        checklist.addView(appInfo, ui.wrapGapTop(10));
    }

    // ================================================================== voice

    private View voiceCard() {
        LinearLayout c = ui.card(R.drawable.ic_mic, Ui.VIOLET, "Voice");

        c.addView(ui.label("Language"));
        c.addView(ui.options(new String[]{"English", "Português"},
                Math.max(0, Arrays.asList(LANG_KEYS).indexOf(p.lang())), i -> {
                    p.setLang(LANG_KEYS[i]);
                    syncChips();
                    renderAiVoice(); // each language has its own AI voice
                    refreshChecklist();
                }), Ui.matchWrap());

        // Personalities as a strip of pastel cards, like the kit's "In Progress" projects.
        c.addView(ui.label("Personality"));
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        LinearLayout cards = ui.row();
        cards.setPadding(0, ui.dp(2), ui.dp(4), ui.dp(10));
        for (String key : ScriptWriter.PERSONA_KEYS) {
            View card = personaCard(key);
            personaRows.add(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(158), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP;
            lp.rightMargin = ui.dp(12);
            cards.addView(card, lp);
        }
        strip.addView(cards);
        c.addView(strip, Ui.matchWrap());
        stylePersonaRows();

        c.addView(ui.label("Voice"));
        c.addView(ui.options(new String[]{"Phone voice", "AI voice"},
                "ai".equals(p.voiceEngine()) ? 1 : 0, i -> {
                    p.setVoiceEngine(i == 1 ? "ai" : "device");
                    renderAiVoice();
                    refreshChecklist();
                }), Ui.matchWrap());
        aiVoiceBox = ui.column();
        c.addView(aiVoiceBox);
        renderAiVoice();

        EditText name = ui.input("Optional. How should I greet you?", false);
        name.setText(p.name());
        name.addTextChangedListener(watcher(s -> p.setName(s)));
        c.addView(ui.field(R.drawable.ic_person, Ui.PINK, "Your name", name, false), ui.gapTop(20));

        sampleButton = ui.secondaryButton("", v -> speakSample());
        ui.withIcon(sampleButton, R.drawable.ic_volume_up, ui.accent);
        updateSampleLabel();
        c.addView(sampleButton, ui.gapTop(14));
        return c;
    }

    private static int personaColor(String key) {
        switch (key) {
            case "sergeant": return Ui.GREEN;
            case "radio": return Ui.ORANGE;
            case "pirate": return Ui.BLUE;
            case "zen": return Ui.PINK;
            default: return Ui.VIOLET;
        }
    }

    private View personaCard(String key) {
        int color = personaColor(key);
        LinearLayout card = ui.column();
        card.setTag(key);
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));

        LinearLayout top = ui.row();
        TextView emoji = ui.text(ScriptWriter.emoji(key), 20, ui.text);
        emoji.setGravity(Gravity.CENTER);
        emoji.setBackground(ui.shape(ui.surface, 12, 0));
        top.addView(emoji, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        top.addView(new View(this), Ui.weight(1));
        ImageView check = ui.icon(R.drawable.ic_check_circle, ui.accent, 22);
        check.setTag("persona_indicator"); // never a persona key, so findViewWithTag can't hit the card
        top.addView(check);
        card.addView(top, Ui.matchWrap());

        TextView name = ui.bold(ScriptWriter.personaName(key), 15, ui.text);
        name.setPadding(0, ui.dp(12), 0, 0);
        card.addView(name, Ui.matchWrap());
        TextView line = ui.text(ScriptWriter.tagline(key), 12, ui.muted);
        line.setPadding(0, ui.dp(4), 0, 0);
        line.setLines(2); // same height for every card
        card.addView(line, Ui.matchWrap());
        card.addView(ui.progressBar(1f, color), ui.gapTop(12));

        card.setOnClickListener(v -> {
            p.setPersona(key);
            stylePersonaRows();
            updateSampleLabel();
            refreshChecklist();
        });
        return card;
    }

    private void stylePersonaRows() {
        for (View card : personaRows) {
            String key = (String) card.getTag();
            boolean on = key.equals(p.persona());
            GradientDrawable d = ui.shape(ui.tint(personaColor(key)), 18, 0);
            if (on) d.setStroke(ui.dp(2), ui.accent);
            card.setBackground(d);
            card.setSelected(on);
            View check = card.findViewWithTag("persona_indicator");
            check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void updateSampleLabel() {
        sampleButton.setText("Hear " + ScriptWriter.personaName(p.persona()));
    }

    private void speakSample() {
        if (AiVoice.willUse(this, p)) {
            speakSampleAi();
            return;
        }
        speakSampleDevice();
    }

    private void speakSampleDevice() {
        if (!ttsReady) {
            toast("The voice engine is still starting…");
            return;
        }
        String lang = p.lang();
        String persona = p.persona();
        Locale loc = ScriptWriter.locale(lang, persona);
        if (tts.isLanguageAvailable(loc) < TextToSpeech.LANG_AVAILABLE) {
            toast("That voice isn't installed yet. See the Setup card.");
        }
        DeviceVoice.apply(tts, loc);
        tts.setSpeechRate(ScriptWriter.rate(persona));
        tts.speak(ScriptWriter.sample(lang, persona, p.name()), TextToSpeech.QUEUE_FLUSH, null, "sample");
    }

    /** Plays the greeting with the AI voice. The model stays loaded while the screen is open. */
    private void speakSampleAi() {
        stopSample();
        if (tts != null) tts.stop();
        AiVoice.Pack pack = AiVoice.forLang(p.lang());
        String persona = p.persona();
        String text = ScriptWriter.sample(p.lang(), persona, p.name());
        sampleButton.setText("Warming up the AI voice…");
        sampleButton.setEnabled(false);
        new Thread(() -> {
            PcmPlayer player = null;
            try {
                GeneratedAudio audio;
                synchronized (sampleLock) {
                    if (sampleEngine == null || !pack.id.equals(sampleEngineId)) {
                        if (sampleEngine != null) sampleEngine.release();
                        sampleEngine = null;
                        sampleEngine = AiVoice.load(this, pack);
                        sampleEngineId = pack.id;
                    }
                    audio = sampleEngine.generate(text, AiVoice.speaker(pack, persona), ScriptWriter.rate(persona));
                }
                runOnUiThread(this::resetSampleButton);
                player = new PcmPlayer(audio.getSampleRate(), new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                samplePlayer = player;
                VoiceLeveler.Stats level = new VoiceLeveler.Stats();
                float[] samples = VoiceLeveler.process(audio.getSamples(), audio.getSampleRate(), level);
                Log.d("WakeBriefing", "AI voice sample level: " + level);
                if (player.write(samples)) player.drain();
            } catch (Throwable e) {
                Log.e("WakeBriefing", "AI voice sample failed", e);
                runOnUiThread(() -> {
                    resetSampleButton();
                    toast("The AI voice couldn't start, so here's the phone's voice.");
                    speakSampleDevice();
                });
            } finally {
                if (player != null) {
                    if (samplePlayer == player) samplePlayer = null;
                    player.release();
                }
            }
        }, "ai-sample").start();
    }

    private void resetSampleButton() {
        sampleButton.setEnabled(true);
        updateSampleLabel();
    }

    private void stopSample() {
        PcmPlayer pl = samplePlayer;
        if (pl != null) pl.stop();
    }

    /** The AI voice panel under the Phone voice / AI voice switch. */
    private void renderAiVoice() {
        if (aiVoiceBox == null) return;
        aiVoiceBox.removeAllViews();
        if (!"ai".equals(p.voiceEngine())) {
            aiVoiceBox.addView(ui.hint("Your phone's built-in voice. For a more natural, human one, "
                    + "try the free AI voice."));
            return;
        }
        AiVoice.Pack pack = AiVoice.forLang(p.lang());
        String language = "pt".equals(p.lang()) ? "Portuguese (Portugal)" : "English";

        if (VoiceDownloader.isRunning(pack)) {
            int pct = VoiceDownloader.percent(pack);
            TextView cancel = ui.pill("Cancel", ui.accent, ui.accentSoft, v -> VoiceDownloader.cancel(pack));
            aiVoiceBox.addView(ui.listRow(R.drawable.ic_download, Ui.VIOLET,
                    "Downloading… " + pct + "%", "Keep the app open until it finishes", cancel), ui.gapTop(16));
            aiVoiceBox.addView(ui.progressBar(pct / 100f, ui.accent), ui.gapTop(14));
        } else if (AiVoice.isInstalled(this, pack)) {
            TextView remove = ui.pill("Remove", ui.warn, ui.tint(ui.warn), v -> confirmRemoveVoice(pack));
            aiVoiceBox.addView(ui.listRow(R.drawable.ic_auto_awesome, Ui.GREEN,
                    language + " AI voice ready", pack.name + " · runs on your phone, works offline", remove),
                    ui.gapTop(16));
            aiVoiceBox.addView(ui.hint(pack.credit + " Frees " + pack.megabytes + " MB if removed."));
        } else {
            String error = VoiceDownloader.error(pack);
            aiVoiceBox.addView(ui.listRow(R.drawable.ic_auto_awesome, Ui.VIOLET,
                    "Natural " + language + " AI voice",
                    "Free and private, works offline. Until it's downloaded, I'll use the phone's voice.",
                    null), ui.gapTop(16));
            if (error != null) {
                TextView e = ui.hint(error);
                e.setTextColor(ui.warn);
                aiVoiceBox.addView(e);
            }
            TextView download = ui.primaryButton("Download · " + pack.megabytes + " MB",
                    v -> VoiceDownloader.start(this, pack));
            ui.withIcon(download, R.drawable.ic_download, ui.onAccent);
            aiVoiceBox.addView(download, ui.gapTop(16));
            aiVoiceBox.addView(ui.hint("Best on Wi-Fi. " + pack.credit));
        }
    }

    private void confirmRemoveVoice(AiVoice.Pack pack) {
        new AlertDialog.Builder(this)
                .setTitle("Remove the AI voice?")
                .setMessage("Frees " + pack.megabytes + " MB. You can download it again at any time; "
                        + "until then I'll use the phone's voice.")
                .setPositiveButton("Remove", (d, w) -> {
                    stopSample();
                    new Thread(() -> {
                        synchronized (sampleLock) { // not while the sample is using it
                            if (sampleEngine != null) sampleEngine.release();
                            sampleEngine = null;
                            AiVoice.remove(this, pack);
                        }
                        runOnUiThread(() -> {
                            renderAiVoice();
                            refreshChecklist();
                        });
                    }).start();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    // ================================================================== weather

    private LinearLayout weatherCard() {
        LinearLayout c = ui.card(R.drawable.ic_partly_cloudy_day, Ui.YELLOW, "Weather");
        cityInput = ui.input("e.g. Porto", false);
        cityInput.setText(p.cityLabel());
        cityInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        cityInput.setOnEditorActionListener((v, actionId, e) -> {
            findCity();
            return true;
        });
        LinearLayout field = ui.field(R.drawable.ic_location_on, Ui.PINK, "City", cityInput, false);
        LinearLayout find = ui.row();
        find.setGravity(Gravity.CENTER);
        find.setContentDescription("Find city");
        find.setBackground(ui.pressable(ui.accent, 13, 0));
        find.addView(ui.icon(R.drawable.ic_search, ui.onAccent, 22));
        find.setOnClickListener(v -> findCity());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42));
        lp.leftMargin = ui.dp(8);
        field.addView(find, lp);
        c.addView(field, ui.gapTop(18));
        cityResult = ui.hint("");
        showCityResult(p.hasCity() ? "📍  " + p.cityLabel() : "Not set yet.", p.hasCity());
        c.addView(cityResult);
        c.addView(ui.hint("Forecast from Open-Meteo, built on national weather-service models. "
                + "I'll mention rain timing, what to wear, UV and wind."));
        return c;
    }

    private void showCityResult(String msg, boolean ok) {
        cityResult.setText(msg);
        cityResult.setTextColor(ok ? ui.success : ui.muted);
    }

    private void focusCity() {
        scroll.smoothScrollTo(0, Math.max(0, weatherCard.getTop() - ui.dp(12)));
        cityInput.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(cityInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void findCity() {
        String q = cityInput.getText().toString().trim();
        if (q.isEmpty()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(cityInput.getWindowToken(), 0);
        showCityResult("Searching…", false);
        String language = p.lang();
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                WeatherClient.Place place = WeatherClient.geocode(q, language);
                if (place == null) {
                    msg = "Couldn't find \"" + q + "\". Try another spelling.";
                } else {
                    p.setCity(place.label, place.lat, place.lon);
                    msg = "📍  " + place.label;
                    ok = true;
                }
            } catch (Exception e) {
                msg = "Couldn't search. Are you online?";
            }
            final String m = msg;
            final boolean success = ok;
            runOnUiThread(() -> {
                showCityResult(m, success);
                if (success) {
                    cityInput.setText(p.cityLabel());
                    refreshChecklist();
                }
            });
        }).start();
    }

    // ================================================================== news

    private View newsCard() {
        LinearLayout c = ui.card(R.drawable.ic_newspaper, Ui.BLUE, "News");

        c.addView(ui.label("Headlines to read"));
        int[] counts = withValue(new int[]{0, 3, 5, 7, 10}, p.headlines());
        c.addView(ui.options(labels(counts, v -> v == 0 ? "Off" : String.valueOf(v)),
                indexOf(counts, p.headlines()), i -> p.setHeadlines(counts[i])), Ui.matchWrap());

        c.addView(ui.label("Sources"));
        Ui.Flow flow = new Ui.Flow(this, ui.dp(8));
        for (String[] s : SOURCES) {
            TextView chip = ui.chip(s[0]);
            chip.setTag(s);
            chip.setOnClickListener(v -> toggleSource(s[1]));
            chips.add(chip);
            flow.addView(chip);
        }
        c.addView(flow, Ui.matchWrap());
        c.addView(ui.hint("Tip: pick sources in the same language as the voice."));

        feedsInput = ui.input("One website or RSS link per line, e.g. economist.com", true);
        feedsInput.setText(p.feedsRaw());
        feedsInput.addTextChangedListener(watcher(s -> {
            if (bindingFeeds) return;
            p.setFeedsRaw(s);
            syncChips();
        }));
        LinearLayout feedsField = ui.field(R.drawable.ic_link, Ui.BLUE, "Your links", feedsInput, false);
        feedsField.setVisibility(View.GONE);
        checkFeedsButton = ui.pill("Check links", ui.accent, ui.accentSoft, v -> checkFeeds());
        checkFeedsButton.setVisibility(View.GONE);
        TextView edit = ui.pill("Edit links by hand", ui.accent, ui.accentSoft, null);
        ui.withIcon(edit, R.drawable.ic_edit, ui.accent);
        edit.setOnClickListener(v -> {
            boolean show = feedsField.getVisibility() != View.VISIBLE;
            feedsField.setVisibility(show ? View.VISIBLE : View.GONE);
            checkFeedsButton.setVisibility(show ? View.VISIBLE : View.GONE);
            edit.setText(show ? "Hide links" : "Edit links by hand");
            // Closing the editor checks anything typed since the last check.
            String typed = feedsInput.getText().toString().trim();
            if (!show && !typed.isEmpty() && !typed.equals(lastCheckedFeeds)) checkFeeds();
        });
        LinearLayout buttons = ui.row();
        buttons.addView(edit);
        LinearLayout.LayoutParams gap = Ui.wrap();
        gap.leftMargin = ui.dp(8);
        buttons.addView(checkFeedsButton, gap);
        c.addView(buttons, ui.wrapGapTop(14));
        c.addView(feedsField, ui.gapTop(12));
        feedsStatus = ui.hint("");
        feedsStatus.setVisibility(View.GONE);
        c.addView(feedsStatus);
        lastCheckedFeeds = feedsInput.getText().toString().trim();
        syncChips();
        return c;
    }

    /**
     * Finds the feed behind each typed link (a home page like economist.com becomes its RSS
     * link) and swaps it into the list, so the morning briefing doesn't have to.
     */
    private void checkFeeds() {
        String snapshot = feedsInput.getText().toString();
        List<String> lines = new ArrayList<>();
        for (String s : snapshot.split("\\s+")) if (!s.isEmpty()) lines.add(s);
        if (lines.isEmpty()) {
            showFeedsStatus("Add a website or RSS link first.", false);
            return;
        }
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(feedsInput.getWindowToken(), 0);
        checkFeedsButton.setEnabled(false);
        showFeedsStatus(lines.size() == 1 ? "Looking for the news feed…"
                : "Looking for news feeds in " + lines.size() + " links…", false);
        String lang = p.lang();
        new Thread(() -> {
            List<String> resolved = new ArrayList<>();
            StringBuilder report = new StringBuilder();
            boolean allOk = true;
            for (String line : lines) {
                FeedFinder.Result r = FeedFinder.find(line, lang);
                if (report.length() > 0) report.append('\n');
                if (r == null) {
                    allOk = false;
                    if (!resolved.contains(line)) resolved.add(line);
                    report.append("✗  ").append(line).append(" — no news feed found");
                } else {
                    if (!resolved.contains(r.feedUrl)) resolved.add(r.feedUrl);
                    String site = FeedFinder.siteName(line);
                    report.append("✓  ").append(site);
                    if (r.viaGoogleNews && !FeedFinder.siteName(r.feedUrl).equals(site)) {
                        report.append(" · via Google News");
                    } else if (!r.title.equalsIgnoreCase(site)) {
                        report.append(" · ").append(r.title);
                    }
                    report.append(" · ").append(r.headlines)
                            .append(r.headlines == 1 ? " headline" : " headlines");
                }
            }
            String joined = String.join("\n", resolved);
            String msg = allOk ? report.toString()
                    : report + "\nCheck the address, or that you're online.";
            boolean ok = allOk;
            runOnUiThread(() -> {
                checkFeedsButton.setEnabled(true);
                // Only swap in the feed links if the text wasn't edited while we were checking.
                if (feedsInput.getText().toString().equals(snapshot)) {
                    p.setFeedsRaw(joined);
                    bindingFeeds = true;
                    feedsInput.setText(joined);
                    bindingFeeds = false;
                    lastCheckedFeeds = joined;
                    syncChips();
                }
                showFeedsStatus(msg, ok);
            });
        }).start();
    }

    private void showFeedsStatus(String msg, boolean ok) {
        feedsStatus.setVisibility(View.VISIBLE);
        feedsStatus.setText(msg);
        feedsStatus.setTextColor(ok ? ui.success : ui.muted);
    }

    private List<String> activeFeeds() {
        List<String> out = new ArrayList<>();
        for (String s : p.effectiveFeedsText().split("\\s+")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void toggleSource(String url) {
        List<String> list = activeFeeds();
        if (!list.remove(url)) list.add(url);
        String joined = String.join("\n", list);
        p.setFeedsRaw(joined);
        bindingFeeds = true;
        feedsInput.setText(joined);
        bindingFeeds = false;
        syncChips();
    }

    private void syncChips() {
        List<String> active = activeFeeds();
        for (TextView chip : chips) {
            String[] s = (String[]) chip.getTag();
            ui.styleChip(chip, s[0], active.contains(s[1]));
        }
    }

    // ================================================================== to-dos

    private View todoCard() {
        LinearLayout c = ui.card(R.drawable.ic_task_alt, Ui.GREEN, "To-dos");
        EditText todos = ui.input("One per line, e.g.\nTake the bins out\nCall the dentist", true);
        todos.setText(p.todos());
        todos.addTextChangedListener(watcher(s -> p.setTodos(s)));
        c.addView(ui.field(0, 0, "Read out every morning", todos, false), ui.gapTop(18));
        c.addView(ui.hint("After your calendar events, which are read automatically."));
        return c;
    }

    // ================================================================== schedule

    private View scheduleCard() {
        LinearLayout c = ui.card(R.drawable.ic_schedule, Ui.ORANGE, "Schedule");

        c.addView(ui.label("Morning window"));
        LinearLayout r = ui.row();
        TextView fromValue = ui.fieldValue(clockOfDay(p.windowStart()));
        LinearLayout from = ui.field(R.drawable.ic_bedtime, Ui.VIOLET, "From", fromValue, true);
        TextView toValue = ui.fieldValue(clockOfDay(p.windowEnd()));
        LinearLayout to = ui.field(R.drawable.ic_alarm, Ui.ORANGE, "To", toValue, true);
        from.setOnClickListener(v -> pickTime("Morning starts at", p.windowStart(), m -> {
            if (m >= p.windowEnd()) {
                toast("The start has to be before " + clockOfDay(p.windowEnd()) + ".");
                return;
            }
            p.setWindow(m, p.windowEnd());
            fromValue.setText(clockOfDay(p.windowStart()));
            refreshAll();
        }));
        to.setOnClickListener(v -> pickTime("Morning ends at", p.windowEnd() % 1440, m -> {
            int end = m == 0 ? 1440 : m; // 00:00 as an end means midnight, the end of the day
            if (end <= p.windowStart()) {
                toast("The end has to be after " + clockOfDay(p.windowStart()) + ".");
                return;
            }
            p.setWindow(p.windowStart(), end);
            toValue.setText(clockOfDay(p.windowEnd()));
            refreshAll();
        }));
        r.addView(from, Ui.weight(1));
        LinearLayout.LayoutParams right = Ui.weight(1);
        right.leftMargin = ui.dp(10);
        r.addView(to, right);
        c.addView(r, Ui.matchWrap());
        // Keep each time on one line: shrink it a little, and if even that won't fit (big font
        // or display size), put "To" under "From" instead of side by side.
        Runnable stack = () -> {
            if (r.getOrientation() == LinearLayout.VERTICAL) return;
            r.setOrientation(LinearLayout.VERTICAL);
            from.setLayoutParams(Ui.matchWrap());
            to.setLayoutParams(ui.gapTop(10)); // the fit check re-runs at the new width
        };
        ui.fitText(fromValue, 15, 12.5f, stack);
        ui.fitText(toValue, 15, 12.5f, stack);
        c.addView(ui.hint("Alarms outside this window, like a nap alarm, get no briefing."));

        c.addView(ui.label("Pause after you dismiss the alarm"));
        int[] delays = withValue(new int[]{0, 3, 5, 10, 30}, p.delaySeconds());
        c.addView(ui.options(labels(delays, v -> v + " s"), indexOf(delays, p.delaySeconds()),
                i -> p.setDelaySeconds(delays[i])), Ui.matchWrap());

        c.addView(ui.toggleRow(R.drawable.ic_event, Ui.BLUE, "Once per day",
                "Backup alarms after the first briefing stay quiet", p.oncePerDay(), v -> {
                    p.setOncePerDay(v == 1);
                    refreshAll();
                }), ui.gapTop(6));
        c.addView(ui.toggleRow(R.drawable.ic_volume_up, Ui.PINK, "Use alarm volume",
                "Audible even when media volume is off", p.alarmVolume(), v -> p.setAlarmVolume(v == 1)));
        return c;
    }

    /** Material 3 time picker (clock dial, or type it in), in the phone's 12/24-hour format. */
    private void pickTime(String title, int minuteOfDay, IntConsumer onPick) {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(minuteOfDay / 60 % 24)
                .setMinute(minuteOfDay % 60)
                .setTitleText(title)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .build();
        picker.addOnPositiveButtonClickListener(v -> onPick.accept(picker.getHour() * 60 + picker.getMinute()));
        picker.show(getSupportFragmentManager(), "time-picker");
    }

    /** "04:30" / "4:30 AM", in the phone's format; 1440 is the end of the day (midnight). */
    private String clockOfDay(int minuteOfDay) {
        boolean h24 = DateFormat.is24HourFormat(this);
        if (minuteOfDay >= 1440) return h24 ? "24:00" : "12:00 AM";
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        return new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(c.getTime());
    }

    // ================================================================== appearance

    private View appearanceCard() {
        LinearLayout c = ui.card(R.drawable.ic_palette, Ui.PINK, "Appearance");
        c.addView(ui.label("Theme"));
        c.addView(ui.options(new String[]{"System", "Light", "Dark"},
                Math.max(0, Arrays.asList(THEME_KEYS).indexOf(p.theme())),
                i -> setThemePref(THEME_KEYS[i])), Ui.matchWrap());

        // The top card's color: presets, or any hue with the slider.
        c.addView(ui.label("Top card color"));
        Ui.Flow row = new Ui.Flow(this, ui.dp(12));
        SeekBar hue = hueSlider();
        for (int i = 0; i < Ui.HERO_KEYS.length; i++) {
            String key = Ui.HERO_KEYS[i];
            View sw = swatch(key);
            sw.setContentDescription(Ui.HERO_NAMES[i] + " card color");
            sw.setOnClickListener(v -> {
                p.setHeroColor(key);
                hue.setVisibility("custom".equals(key) ? View.VISIBLE : View.GONE);
                applyHeroColor();
                recreate(); // the whole app takes the new color
            });
            swatches.add(sw);
            row.addView(sw, new ViewGroup.LayoutParams(ui.dp(40), ui.dp(40)));
        }
        c.addView(row, Ui.matchWrap());
        hue.setVisibility("custom".equals(p.heroColor()) ? View.VISIBLE : View.GONE);
        c.addView(hue, ui.gapTop(16));
        styleSwatches();
        return c;
    }

    /** A round color sample; "custom" shows the whole rainbow. */
    private View swatch(String key) {
        View v = new View(this);
        v.setTag(key);
        return v;
    }

    private void styleSwatches() {
        for (View v : swatches) {
            String key = (String) v.getTag();
            GradientDrawable d;
            if ("custom".equals(key)) {
                d = new GradientDrawable();
                d.setGradientType(GradientDrawable.SWEEP_GRADIENT);
                d.setColors(RAINBOW);
            } else {
                d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, Ui.heroGradient(key, 0));
            }
            d.setShape(GradientDrawable.OVAL);
            boolean on = key.equals(p.heroColor());
            // The chosen one gets a ring in the page's text color, with a gap like a focus ring.
            if (on) d.setStroke(ui.dp(3), ui.surface);
            LayerDrawable layers = new LayerDrawable(new Drawable[]{ringDrawable(on), d});
            int inset = ui.dp(on ? 3 : 0);
            layers.setLayerInset(1, inset, inset, inset, inset);
            v.setBackground(layers);
            v.setSelected(on);
        }
    }

    private Drawable ringDrawable(boolean on) {
        GradientDrawable r = new GradientDrawable();
        r.setShape(GradientDrawable.OVAL);
        r.setColor(on ? ui.text : 0x00000000);
        return r;
    }

    private static final int[] RAINBOW = {0xFFFF5252, 0xFFFFB300, 0xFF66BB6A, 0xFF26C6DA,
            0xFF5C6BC0, 0xFFAB47BC, 0xFFFF5252};

    /** Picks any hue for the "custom" card color; the card follows the thumb live. */
    private SeekBar hueSlider() {
        SeekBar s = new SeekBar(this);
        s.setMax(359);
        s.setProgress(p.heroHue());
        GradientDrawable track = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, RAINBOW);
        track.setCornerRadius(ui.dp(6));
        track.setSize(0, ui.dp(12));
        s.setProgressDrawable(track);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setStroke(ui.dp(3), ui.text);
        thumb.setSize(ui.dp(26), ui.dp(26));
        s.setThumb(thumb);
        s.setSplitTrack(false);
        s.setMinimumHeight(ui.dp(34));
        s.setContentDescription("Custom card color");
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                p.setHeroHue(value);
                applyHeroColor();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { recreate(); }
        });
        return s;
    }

    private void setThemePref(String key) {
        if (key.equals(p.theme())) return;
        p.setTheme(key);
        recreate();
    }

    // ================================================================== bottom bar

    /** Rounded white bar with the kit's glowing violet button and a soft round Stop. */
    private View bottomBar() {
        LinearLayout bar = ui.row();
        GradientDrawable bgd = ui.shape(ui.surface, 0, 0);
        float r = ui.dp(26);
        bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bar.setBackground(bgd);
        ui.lift(bar, 16);
        bar.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        TextView play = ui.primaryButton("Play briefing now", v -> {
            if (tts != null) tts.stop();
            startForegroundService(new Intent(this, BriefingService.class)
                    .setAction(BriefingService.ACTION_PLAY_NOW));
            toast("Getting the weather and news…");
        });
        ui.withIcon(play, R.drawable.ic_play_arrow, ui.onAccent);
        bar.addView(play, Ui.weight(1));

        LinearLayout stop = ui.row();
        stop.setGravity(Gravity.CENTER);
        stop.setContentDescription("Stop");
        stop.setBackground(ui.pressable(ui.accentSoft, 16, 0));
        stop.addView(ui.icon(R.drawable.ic_stop, ui.accent, 26));
        stop.setOnClickListener(v -> {
            if (tts != null) tts.stop();
            startService(new Intent(this, BriefingService.class).setAction(BriefingService.ACTION_STOP));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(54), ui.dp(54));
        lp.leftMargin = ui.dp(12);
        bar.addView(stop, lp);
        return bar;
    }

    // ================================================================== system

    private void styleSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(ui.bg);
        w.setNavigationBarColor(ui.surface); // continues the white bottom bar
        boolean lightIcons = !ui.dark;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(lightIcons ? mask : 0, mask);
            }
        } else {
            View d = w.getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int cur = d.getSystemUiVisibility();
            d.setSystemUiVisibility(lightIcons ? (cur | flags) : (cur & ~flags));
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openClock() {
        try {
            startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
        } catch (Exception e) {
            toast("Couldn't open the Clock app.");
        }
    }

    private void openVoiceSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ================================================================== helpers

    /** The preset choices, plus the saved value if it isn't one of them (e.g. from an older version). */
    private static int[] withValue(int[] presets, int value) {
        for (int v : presets) if (v == value) return presets;
        int[] out = Arrays.copyOf(presets, presets.length + 1);
        out[presets.length] = value;
        Arrays.sort(out);
        return out;
    }

    private static String[] labels(int[] values, IntFunction<String> fmt) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = fmt.apply(values[i]);
        return out;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }

    private static TextWatcher watcher(Consumer<String> onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onChange.accept(s.toString()); }
        };
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
