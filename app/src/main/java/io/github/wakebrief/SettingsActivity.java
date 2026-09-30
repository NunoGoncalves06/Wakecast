package io.github.wakebrief;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Settings, laid out like the phone's own Settings app: segmented lists of categories, each
 * opening its own page. Everything saves as soon as it changes.
 */
public class SettingsActivity extends BaseActivity {

    static final String ROOT = "root", VOICE = "voice", WEATHER = "weather", NEWS = "news",
            TODOS = "todos", SCHEDULE = "schedule", APPEARANCE = "appearance",
            PERMISSIONS = "permissions", ABOUT = "about";
    private static final String EXTRA_PAGE = "page";
    private static final String REPO = "https://github.com/NunoGoncalves06/Wakecast";

    private static final String[] LANG_KEYS = {"en", "pt"};
    private static final String[] THEME_KEYS = {"system", "light", "dark"};

    /** One-tap news sources. */
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

    static void open(Activity from, String page) {
        from.startActivity(new Intent(from, SettingsActivity.class).putExtra(EXTRA_PAGE, page));
    }

    private String page;
    private TextToSpeech tts;
    private boolean ttsReady;

    // voice page
    private LinearLayout aiBox;
    private MaterialButton sampleButton;
    private final List<View> personaRows = new ArrayList<>();
    private final Object sampleLock = new Object();
    private OfflineTts sampleEngine;          // guarded by sampleLock
    private String sampleEngineId;            // guarded by sampleLock
    private volatile PcmPlayer samplePlayer;

    // news page
    private ChipGroup sourceGroup;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = getIntent().getStringExtra(EXTRA_PAGE);
        if (page == null) page = ROOT;
        showBack();
        if (VOICE.equals(page) || PERMISSIONS.equals(page)) {
            tts = new TextToSpeech(this, s -> {
                ttsReady = s == TextToSpeech.SUCCESS;
                if (PERMISSIONS.equals(page)) runOnUiThread(this::render);
            });
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ROOT.equals(page) || PERMISSIONS.equals(page)) render(); // permissions may have changed
        if (VOICE.equals(page)) VoiceDownloader.setListener(this::renderAiVoice);
    }

    @Override
    protected void onPause() {
        if (VOICE.equals(page)) VoiceDownloader.setListener(null); // the download itself carries on
        Scheduler.reschedule(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
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
        render();
    }

    private void render() {
        content.removeAllViews();
        switch (page) {
            case VOICE: setTitleText("Voice"); voicePage(); break;
            case WEATHER: setTitleText("Weather"); weatherPage(); break;
            case NEWS: setTitleText("News"); newsPage(); break;
            case TODOS: setTitleText("To-dos"); todosPage(); break;
            case SCHEDULE: setTitleText("Schedule"); schedulePage(); break;
            case APPEARANCE: setTitleText("Appearance"); appearancePage(); break;
            case PERMISSIONS: setTitleText("Permissions & battery"); permissionsPage(); break;
            case ABOUT: setTitleText("About"); aboutPage(); break;
            default: setTitleText("Settings"); rootPage();
        }
    }

    /** A segmented list under a header (or, without one, after a small gap). */
    private Ui.Group section(String header) {
        if (header != null) content.addView(ui.header(header));
        else content.addView(new View(this), new LinearLayout.LayoutParams(1, ui.dp(16)));
        return ui.group(content);
    }

    // ================================================================== root

    private void rootPage() {
        int missing = Setup.missing(Setup.items(this, p, null, false));
        Ui.Group briefing = section(null);
        link(briefing, R.drawable.ic_record_voice_over, "Voice", "Language, personality, AI voice", VOICE);
        link(briefing, R.drawable.ic_partly_cloudy_day, "Weather", p.hasCity() ? p.cityLabel() : "Set your city", WEATHER);
        link(briefing, R.drawable.ic_newspaper, "News", "Headlines and sources", NEWS);
        link(briefing, R.drawable.ic_task_alt, "To-dos", "Reminders read every morning", TODOS);
        Ui.Group app = section(null);
        link(app, R.drawable.ic_schedule, "Schedule", "Morning window, pause, volume", SCHEDULE);
        link(app, R.drawable.ic_palette, "Appearance", "Theme and colours", APPEARANCE);
        Ui.Group system = section(null);
        link(system, R.drawable.ic_verified_user, "Permissions & battery",
                missing == 0 ? "Everything's allowed" : missing + " need attention", PERMISSIONS);
        link(system, R.drawable.ic_info, "About", "Version, source code, credits", ABOUT);
    }

    private void link(Ui.Group g, int icon, String title, String summary, String target) {
        g.addView(ui.link(icon, title, summary, v -> open(this, target)));
    }

    // ================================================================== voice

    private void voicePage() {
        content.addView(ui.header("Language"));
        content.addView(ui.choices(new String[]{"English", "Português"}, null,
                Math.max(0, Arrays.asList(LANG_KEYS).indexOf(p.lang())), i -> {
                    p.setLang(LANG_KEYS[i]);
                    renderAiVoice(); // each language has its own AI voice
                }));

        Ui.Group personas = section("Personality");
        personaRows.clear();
        for (String key : ScriptWriter.PERSONA_KEYS) {
            TextView emoji = ui.text(ScriptWriter.emoji(key), com.google.android.material.R.attr.textAppearanceHeadlineSmall, ui.onSurface);
            emoji.setGravity(Gravity.CENTER);
            emoji.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
            MaterialRadioButton radio = new MaterialRadioButton(this);
            radio.setClickable(false);
            radio.setTag("persona_indicator"); // never a persona key
            View row = ui.item(emoji, ScriptWriter.personaName(key), ScriptWriter.tagline(key), radio, v -> {
                p.setPersona(key);
                syncPersonas();
            });
            row.setTag(key);
            personaRows.add(row);
            personas.addView(row);
        }

        content.addView(ui.header("Voice engine"));
        content.addView(ui.choices(new String[]{"Phone voice", "AI voice"},
                new int[]{R.drawable.ic_smartphone, R.drawable.ic_auto_awesome},
                "ai".equals(p.voiceEngine()) ? 1 : 0, i -> {
                    p.setVoiceEngine(i == 1 ? "ai" : "device");
                    renderAiVoice();
                }));
        aiBox = ui.column();
        content.addView(aiBox, Ui.matchWrap());
        renderAiVoice();

        content.addView(ui.header("Your name"));
        TextInputLayout name = ui.field("Name", "Optional. How should it greet you?", false);
        Ui.edit(name).setText(p.name());
        Ui.edit(name).addTextChangedListener(watcher(s -> p.setName(s)));
        content.addView(name, ui.margins(16, 0));

        sampleButton = ui.button("", Ui.FILLED, R.drawable.ic_volume_up, v -> speakSample());
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.setMargins(ui.dp(16), ui.dp(24), ui.dp(16), 0);
        content.addView(sampleButton, lp);
        syncPersonas();
    }

    private void syncPersonas() {
        for (View row : personaRows) {
            MaterialRadioButton r = row.findViewWithTag("persona_indicator");
            r.setChecked(row.getTag().equals(p.persona()));
        }
        if (sampleButton != null) sampleButton.setText("Hear " + ScriptWriter.personaName(p.persona()));
    }

    /** Status of the AI voice for the chosen language: download, progress, or ready. */
    private void renderAiVoice() {
        if (aiBox == null) return;
        aiBox.removeAllViews();
        if (!"ai".equals(p.voiceEngine())) {
            aiBox.addView(ui.note("Your phone's built-in voice. For a more natural one, try the free AI voice."));
            return;
        }
        AiVoice.Pack pack = AiVoice.forLang(p.lang());
        String language = "pt".equals(p.lang()) ? "Portuguese (Portugal)" : "English";
        aiBox.addView(new View(this), new LinearLayout.LayoutParams(1, ui.dp(12)));
        Ui.Group g = ui.group(aiBox);
        if (VoiceDownloader.isRunning(pack)) {
            int pct = VoiceDownloader.percent(pack);
            g.addView(ui.item(R.drawable.ic_download, "Downloading… " + pct + "%",
                    "Keep the app open until it finishes",
                    ui.button("Cancel", Ui.TEXT, 0, v -> VoiceDownloader.cancel(pack)), null));
            LinearProgressIndicator bar = ui.progress();
            bar.setProgressCompat(pct, false);
            g.addView(ui.segment(bar, null));
        } else if (AiVoice.isInstalled(this, pack)) {
            g.addView(ui.item(R.drawable.ic_auto_awesome, language + " AI voice ready",
                    pack.name + " · runs on your phone, works offline",
                    ui.button("Remove", Ui.TEXT, 0, v -> confirmRemoveVoice(pack)), null));
            aiBox.addView(ui.note(pack.credit));
        } else {
            g.addView(ui.item(R.drawable.ic_auto_awesome, "Natural " + language + " AI voice",
                    "Free and private, works offline. Until it's downloaded, the phone's voice is used.", null, null));
            String error = VoiceDownloader.error(pack);
            if (error != null) {
                TextView e = ui.note(error);
                e.setTextColor(ui.error);
                aiBox.addView(e);
            }
            MaterialButton download = ui.button("Download · " + pack.megabytes + " MB", Ui.TONAL,
                    R.drawable.ic_download, v -> VoiceDownloader.start(this, pack));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
            aiBox.addView(download, lp);
            aiBox.addView(ui.note("Best on Wi-Fi. " + pack.credit));
        }
    }

    private void confirmRemoveVoice(AiVoice.Pack pack) {
        ui.dialog()
                .setTitle("Remove the AI voice?")
                .setMessage("Frees " + pack.megabytes + " MB. You can download it again at any time; "
                        + "until then the phone's voice is used.")
                .setPositiveButton("Remove", (d, w) -> {
                    stopSample();
                    new Thread(() -> {
                        synchronized (sampleLock) { // not while the sample is using it
                            if (sampleEngine != null) sampleEngine.release();
                            sampleEngine = null;
                            AiVoice.remove(this, pack);
                        }
                        runOnUiThread(this::renderAiVoice);
                    }).start();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void speakSample() {
        if (AiVoice.willUse(this, p)) speakSampleAi();
        else speakSampleDevice();
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
            toast("That voice isn't installed yet. See Permissions & battery.");
        }
        DeviceVoice.apply(tts, loc);
        tts.setSpeechRate(ScriptWriter.rate(persona));
        tts.speak(ScriptWriter.sample(lang, persona, p.name()), TextToSpeech.QUEUE_FLUSH, null, "sample");
    }

    /** Plays the greeting with the AI voice. The model stays loaded while this page is open. */
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
                float[] samples = VoiceLeveler.process(audio.getSamples(), audio.getSampleRate(), null);
                if (player.write(samples)) player.drain();
            } catch (Throwable e) {
                Log.e("Wakecast", "AI voice sample failed", e);
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
        syncPersonas();
    }

    private void stopSample() {
        PcmPlayer pl = samplePlayer;
        if (pl != null) pl.stop();
    }

    // ================================================================== weather

    private void weatherPage() {
        content.addView(ui.header("Location"));
        TextInputLayout city = ui.field("City", null, false);
        EditText input = Ui.edit(city);
        input.setText(p.cityLabel());
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        city.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        city.setEndIconDrawable(R.drawable.ic_search);
        city.setEndIconContentDescription("Find city");
        content.addView(city, ui.margins(16, 0));
        Status result = new Status(p.hasCity() ? "Using " + p.cityLabel() : "Not set yet.");
        content.addView(result.row, Ui.matchWrap());
        Runnable find = () -> findCity(input, result);
        city.setEndIconOnClickListener(v -> find.run());
        input.setOnEditorActionListener((v, actionId, e) -> {
            find.run();
            return true;
        });
        content.addView(ui.note("Forecast from Open-Meteo, built on national weather-service models. "
                + "The briefing mentions rain timing, what to wear, UV and wind."));
    }

    private void findCity(EditText input, Status result) {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        result.busy("Searching…");
        String language = p.lang();
        new Thread(() -> {
            String msg;
            try {
                WeatherClient.Place place = WeatherClient.geocode(q, language);
                if (place == null) {
                    msg = "Couldn't find \"" + q + "\". Try another spelling.";
                } else {
                    p.setCity(place.label, place.lat, place.lon);
                    msg = "Using " + place.label;
                }
            } catch (Exception e) {
                msg = "Couldn't search. Are you online?";
            }
            String m = msg;
            runOnUiThread(() -> {
                result.done(m);
                if (p.hasCity()) input.setText(p.cityLabel());
            });
        }).start();
    }

    /** A line of status text, with a loading indicator in front while something is running. */
    private final class Status {
        final LinearLayout row = ui.row();
        final LoadingIndicator spinner = ui.loading();
        final TextView text = ui.supporting("");

        Status(String initial) {
            row.setPadding(ui.dp(28), ui.dp(8), ui.dp(28), ui.dp(8));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = ui.dp(12);
            row.addView(spinner, lp);
            row.addView(text, Ui.weight(1));
            text.setText(initial);
        }

        void busy(String s) {
            row.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            text.setText(s);
        }

        void done(String s) {
            row.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.GONE);
            text.setText(s);
        }
    }

    // ================================================================== news

    private void newsPage() {
        section("Headlines").addView(ui.slider("Headlines to read", 0, 15, p.headlines(),
                v -> v == 0 ? "Off" : String.valueOf(v), p::setHeadlines));

        content.addView(ui.header("Sources"));
        sourceGroup = new ChipGroup(this);
        sourceGroup.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        content.addView(sourceGroup, Ui.matchWrap());
        content.addView(ui.note("Tap a source to switch it on or off. Touch and hold to delete it. "
                + "Pick sources in the same language as the voice."));

        content.addView(ui.header("Add a news site"));
        TextInputLayout field = ui.field("Website or RSS link", "e.g. economist.com", false);
        EditText input = Ui.edit(field);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        content.addView(field, ui.margins(16, 0));
        Status status = new Status("");
        status.row.setVisibility(View.GONE);
        MaterialButton add = ui.button("Add", Ui.TONAL, R.drawable.ic_add, null);
        add.setOnClickListener(v -> addSource(input, add, status));
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (add.isEnabled()) addSource(input, add, status);
            return true;
        });
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
        content.addView(add, lp);
        content.addView(status.row, Ui.matchWrap());

        adoptActiveFeeds();
        renderSources();
    }

    /** Every source chip, {name, feed URL}: the built-in ones (unless deleted), then the user's. */
    private List<String[]> sources() {
        Set<String> hidden = p.hiddenSources();
        List<String[]> out = new ArrayList<>();
        for (String[] s : SOURCES) if (!hidden.contains(s[1])) out.add(s);
        out.addAll(p.customSources());
        return out;
    }

    /** Feeds switched on without a chip (links typed in earlier versions) get one. */
    private void adoptActiveFeeds() {
        Set<String> known = new HashSet<>();
        for (String[] s : sources()) known.add(s[1]);
        Set<String> hidden = p.hiddenSources();
        List<String[]> custom = p.customSources();
        boolean changed = false;
        for (String url : activeFeeds()) {
            if (known.contains(url)) continue;
            if (!hidden.remove(url)) custom.add(new String[]{FeedFinder.siteName(url), url});
            known.add(url);
            changed = true;
        }
        if (changed) {
            p.setHiddenSources(hidden);
            p.setCustomSources(custom);
        }
    }

    private void renderSources() {
        sourceGroup.removeAllViews();
        List<String> active = activeFeeds();
        for (String[] s : sources()) {
            Chip chip = ui.filterChip(s[0]);
            chip.setChecked(active.contains(s[1]));
            chip.setOnClickListener(v -> toggleSource(chip, s[1]));
            chip.setOnLongClickListener(v -> {
                confirmDeleteSource(s);
                return true;
            });
            sourceGroup.addView(chip);
        }
    }

    private List<String> activeFeeds() {
        List<String> out = new ArrayList<>();
        for (String s : p.effectiveFeedsText().split("\\s+")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void toggleSource(Chip chip, String url) {
        List<String> list = activeFeeds();
        if (list.contains(url)) {
            if (list.size() == 1) { // with none on, the language's default feed would come back
                chip.setChecked(true);
                toast("Keep at least one source on. To skip the news, set Headlines to Off.");
                return;
            }
            list.remove(url);
        } else {
            list.add(url);
        }
        p.setFeedsRaw(String.join("\n", list));
        chip.setChecked(list.contains(url));
    }

    private void confirmDeleteSource(String[] source) {
        ui.dialog()
                .setIcon(R.drawable.ic_delete)
                .setTitle("Delete " + source[0] + "?")
                .setMessage("It's removed from your sources. You can add it again at any time.")
                .setPositiveButton("Delete", (d, w) -> {
                    List<String> list = activeFeeds();
                    if (list.remove(source[1])) p.setFeedsRaw(String.join("\n", list));
                    boolean builtIn = false;
                    for (String[] s : SOURCES) if (s[1].equals(source[1])) builtIn = true;
                    if (builtIn) {
                        Set<String> hidden = p.hiddenSources();
                        hidden.add(source[1]);
                        p.setHiddenSources(hidden);
                    } else {
                        List<String[]> custom = p.customSources();
                        custom.removeIf(s -> s[1].equals(source[1]));
                        p.setCustomSources(custom);
                    }
                    adoptActiveFeeds(); // if that was the last one, the default feed shows up again
                    renderSources();
                    toast("Deleted " + source[0]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Finds the news feed behind a typed website; if there is one, it becomes a chip, switched on. */
    private void addSource(EditText input, MaterialButton add, Status status) {
        String typed = input.getText().toString().trim();
        if (typed.isEmpty()) {
            status.done("Type a website first, e.g. economist.com.");
            return;
        }
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        add.setEnabled(false);
        status.busy("Looking for the news feed…");
        String lang = p.lang();
        new Thread(() -> {
            FeedFinder.Result r = FeedFinder.find(typed, lang);
            runOnUiThread(() -> {
                add.setEnabled(true);
                if (r == null) {
                    status.done("No news feed found at " + typed + ". Check the address, or that you're online.");
                    return;
                }
                String name = null;
                for (String[] s : sources()) if (s[1].equals(r.feedUrl)) name = s[0];
                boolean isNew = name == null;
                if (isNew) {
                    Set<String> hidden = p.hiddenSources();
                    for (String[] s : SOURCES) if (s[1].equals(r.feedUrl) && hidden.remove(s[1])) name = s[0];
                    if (name != null) {
                        p.setHiddenSources(hidden);
                    } else {
                        name = FeedFinder.siteName(typed);
                        List<String[]> custom = p.customSources();
                        custom.add(new String[]{name, r.feedUrl});
                        p.setCustomSources(custom);
                    }
                }
                List<String> list = activeFeeds();
                if (!list.contains(r.feedUrl)) list.add(r.feedUrl);
                p.setFeedsRaw(String.join("\n", list));
                input.setText("");
                renderSources();
                String site = FeedFinder.siteName(typed);
                String via = r.viaGoogleNews && !FeedFinder.siteName(r.feedUrl).equals(site) ? " via Google News" : "";
                status.done(isNew
                        ? "Added " + name + via + " · " + r.headlines + (r.headlines == 1 ? " headline" : " headlines") + " right now."
                        : name + " is already in your sources, and it's switched on.");
            });
        }).start();
    }

    // ================================================================== to-dos

    private void todosPage() {
        content.addView(ui.header("Read out every morning"));
        TextInputLayout todos = ui.field("To-dos", "One per line. Read after your calendar events.", true);
        Ui.edit(todos).setText(p.todos());
        Ui.edit(todos).addTextChangedListener(watcher(s -> p.setTodos(s)));
        content.addView(todos, ui.margins(16, 0));
    }

    // ================================================================== schedule

    private void schedulePage() {
        Ui.Group window = section("Morning window");
        window.addView(ui.item(R.drawable.ic_bedtime, "Starts at", null, time(p.windowStart()),
                v -> pickTime("Morning starts at", p.windowStart(), m -> {
                    if (m >= p.windowEnd()) {
                        toast("The start has to be before " + clockOfDay(this, p.windowEnd()) + ".");
                        return;
                    }
                    p.setWindow(m, p.windowEnd());
                    render();
                })));
        window.addView(ui.item(R.drawable.ic_alarm, "Ends at", null, time(p.windowEnd()),
                v -> pickTime("Morning ends at", p.windowEnd() % 1440, m -> {
                    int end = m == 0 ? 1440 : m; // 00:00 as an end means midnight
                    if (end <= p.windowStart()) {
                        toast("The end has to be after " + clockOfDay(this, p.windowStart()) + ".");
                        return;
                    }
                    p.setWindow(p.windowStart(), end);
                    render();
                })));
        content.addView(ui.note("Alarms outside this window, like a nap alarm, get no briefing."));

        section("After the alarm").addView(ui.slider("Pause before talking", 0, 30, p.delaySeconds(),
                v -> v + " s", p::setDelaySeconds));

        Ui.Group behaviour = section("Behaviour");
        behaviour.addView(ui.switchItem(R.drawable.ic_event, "Once per day",
                "Backup alarms after the first briefing stay quiet", p.oncePerDay(), v -> p.setOncePerDay(v == 1)));
        behaviour.addView(ui.switchItem(R.drawable.ic_volume_up, "Use alarm volume",
                "Audible even when media volume is off", p.alarmVolume(), v -> p.setAlarmVolume(v == 1)));
    }

    /** A time shown at the end of a row, in the accent colour. */
    private TextView time(int minuteOfDay) {
        return ui.text(clockOfDay(this, minuteOfDay), com.google.android.material.R.attr.textAppearanceTitleLarge, ui.primary);
    }

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
    static String clockOfDay(Activity a, int minuteOfDay) {
        boolean h24 = DateFormat.is24HourFormat(a);
        if (minuteOfDay >= 1440) return h24 ? "24:00" : "12:00 AM";
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        return new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(c.getTime());
    }

    // ================================================================== appearance

    private void appearancePage() {
        content.addView(ui.header("Theme"));
        content.addView(ui.choices(new String[]{"System", "Light", "Dark"},
                new int[]{R.drawable.ic_contrast, R.drawable.ic_light_mode, R.drawable.ic_dark_mode},
                Math.max(0, Arrays.asList(THEME_KEYS).indexOf(p.theme())), i -> {
                    if (THEME_KEYS[i].equals(p.theme())) return;
                    p.setTheme(THEME_KEYS[i]);
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode(THEME_KEYS[i]));
                }));

        if (!DynamicColors.isDynamicColorAvailable()) {
            content.addView(ui.header("Colours"));
            content.addView(ui.note("Custom colours need Android 12 or newer."));
            return;
        }
        Ui.Group colours = section("Colours");
        colours.addView(ui.switchItem(R.drawable.ic_palette, "Wallpaper colours",
                "Match the colours of your wallpaper, like the phone's own apps", p.dynamicColor(), v -> {
                    p.setDynamicColor(v == 1);
                    recreate();
                }));
        if (p.dynamicColor()) return;

        // Preset colours in rows that wrap, so none hide off the edge of the screen. The last
        // key ("custom") gets its own row below, which opens the colour picker.
        final int perRow = 4;
        int presets = Ui.HERO_KEYS.length - 1;
        LinearLayout grid = ui.column();
        for (int start = 0; start < presets; start += perRow) {
            LinearLayout row = ui.row();
            for (int i = start; i < start + perRow; i++) {
                LinearLayout cell = ui.column();
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, ui.dp(4), 0, ui.dp(4));
                if (i < presets) {
                    cell.addView(swatch(Ui.HERO_KEYS[i], Ui.HERO_NAMES[i]),
                            new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
                }
                row.addView(cell, Ui.weight(1)); // empty cells keep the columns aligned
            }
            grid.addView(row, Ui.matchWrap());
        }
        colours.addView(ui.segment(grid, null));

        // Any colour at all, with the colour picker.
        boolean customOn = "custom".equals(p.heroColor());
        LinearLayout customRow = ui.row();
        LinearLayout texts = ui.column();
        texts.addView(ui.body("Custom colour"));
        texts.addView(ui.supporting(customOn
                ? ColourPicker.hexOf(p.heroCustom()) + " · tap to change"
                : "Pick any colour you like"));
        customRow.addView(texts, Ui.weight(1));
        MaterialButton customSwatch = swatch("custom", "Custom");
        customSwatch.setOnClickListener(v -> pickCustomColour());
        customRow.addView(customSwatch, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        colours.addView(ui.segment(customRow, v -> pickCustomColour()));
    }

    /** Opens the colour picker; the whole app switches to the picked colour. */
    private void pickCustomColour() {
        ColourPicker.show(this, p.heroCustom(), c -> {
            p.setHeroCustom(c);
            p.setHeroColor("custom");
            recreate();
        });
    }

    /** A round colour button (Material icon button); the chosen one is ticked. */
    private MaterialButton swatch(String key, String name) {
        boolean selected = key.equals(p.heroColor());
        int c = Ui.seed(key, p.heroCustom());
        int on = ColorUtils.calculateLuminance(c) > 0.5 ? 0xFF1C1B1F : 0xFFFFFFFF;
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonFilledStyle);
        b.setBackgroundTintList(ColorStateList.valueOf(c));
        b.setIconTint(ColorStateList.valueOf(on));
        if (selected) b.setIconResource(R.drawable.ic_check);
        else if ("custom".equals(key)) b.setIconResource(R.drawable.ic_palette);
        b.setCheckable(true);
        b.setChecked(selected);
        b.setContentDescription(name + " colour");
        b.setOnClickListener(v -> {
            if (selected) {
                b.setChecked(true);
                return;
            }
            p.setHeroColor(key);
            recreate();
        });
        return b;
    }

    // ================================================================== permissions

    private void permissionsPage() {
        Ui.Group needed = section("Needed for the briefing");
        for (Setup.Item item : Setup.items(this, p, tts, ttsReady)) {
            View trailing = item.ok
                    ? ui.icon(R.drawable.ic_check_circle, ui.primary)
                    : ui.button(item.action, Ui.FILLED, 0, v -> item.fix.run());
            needed.addView(ui.item(item.icon, item.title, item.ok ? "Allowed" : item.why, trailing, null));
        }
        content.addView(ui.header("Battery"));
        content.addView(ui.note("Many phones close apps overnight to save battery. In App info › Battery, "
                + "allow background activity (or choose \"Unrestricted\"), and lock Wakecast in Recents "
                + "if your phone offers it."));
        ui.group(content).addView(ui.item(R.drawable.ic_open_in_new, "Open App info", null, null,
                v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));
    }

    // ================================================================== about

    private void aboutPage() {
        String version = "";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
        } catch (Exception ignored) {
            // not shown then
        }
        Ui.Group app = section(null);
        app.addView(ui.item(R.drawable.ic_info, getString(R.string.app_name), "Version " + version, null, null));
        app.addView(ui.item(R.drawable.ic_open_in_new, "Source code", "github.com/NunoGoncalves06/Wakecast", null,
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)))));
        content.addView(ui.header("Credits"));
        content.addView(ui.note("Weather: Open-Meteo.\n"
                + "Speech engine: sherpa-onnx (Apache 2.0).\n"
                + "Voices: Kokoro-82M by hexgrad (Apache 2.0); Piper \"Dii\" by OpenVoiceOS (CC BY-NC-SA 4.0, personal use).\n"
                + "Icons: Material Symbols (Apache 2.0). Components: Material Components for Android (Apache 2.0)."));
        content.addView(ui.note("No accounts, no ads, no tracking. Calendar events and to-dos never leave the phone."));
    }

    // ================================================================== helpers

    private static TextWatcher watcher(Consumer<String> onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onChange.accept(s.toString()); }
        };
    }
}
