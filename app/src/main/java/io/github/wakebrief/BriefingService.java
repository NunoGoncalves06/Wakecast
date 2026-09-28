package io.github.wakebrief;

import android.Manifest;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs the morning briefing:
 *  1. starts a minute before your alarm and downloads weather + news,
 *  2. waits while the alarm rings (detected through the audio system),
 *  3. once you dismiss it, reads everything out loud with the phone's own text-to-speech.
 * If you snooze, it steps back and tries again after the snoozed alarm.
 */
public class BriefingService extends Service {

    static final String ACTION_AUTO = "io.github.wakebrief.AUTO";
    static final String ACTION_PLAY_NOW = "io.github.wakebrief.PLAY_NOW";
    static final String ACTION_STOP = "io.github.wakebrief.STOP";

    private static final String TAG = "Wakecast";
    private static final String CH_RUNNING = "briefing";
    private static final String CH_ALERTS = "alerts";
    private static final int NOTIF_RUNNING = 1;
    private static final int NOTIF_ALERT = 2;

    /** How long after the alarm time we keep looking for the alarm sound. */
    private static final long RING_DETECT_WINDOW_MS = 90_000;
    /** Give up waiting (and leave a "tap to hear" notification) after this long. */
    private static final long MAX_WAIT_MS = 45L * 60 * 1000;
    /** A new alarm this soon after the ringing stopped is treated as a snooze. */
    private static final long SNOOZE_MAX_MS = 30L * 60 * 1000;

    private enum Wake { READY, SNOOZED, TIMEOUT, CANCELLED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean playNow;
    /** True while a briefing is being prepared or spoken (the app shows Stop instead of Play). */
    static volatile boolean active;
    private volatile TextToSpeech tts;
    private volatile PcmPlayer aiPlayer;
    private volatile int aiLinesSpoken;
    private PowerManager.WakeLock wakeLock;
    private String currentText = "Getting your briefing ready…";
    private boolean currentWaiting;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            TextToSpeech t = tts;
            if (t != null) t.stop();
            PcmPlayer pl = aiPlayer;
            if (pl != null) pl.stop();
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            } else {
                finish();
            }
            return START_NOT_STICKY;
        }

        try {
            goForeground();
        } catch (RuntimeException e) {
            // Android refused to let us start from the background (e.g. heavy battery restrictions).
            Log.e(TAG, "startForeground refused", e);
            postTapToHear(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (worker != null && worker.isAlive()) {
            if (ACTION_PLAY_NOW.equals(action)) playNow = true;
            return START_NOT_STICKY;
        }

        stopRequested = false;
        playNow = false;
        final boolean auto = ACTION_AUTO.equals(action);
        final long alarmTime = intent != null
                ? intent.getLongExtra(Scheduler.EXTRA_ALARM_TIME, 0) : 0;
        getSystemService(NotificationManager.class).cancel(NOTIF_ALERT);
        acquireWakeLock();
        active = true;
        worker = new Thread(() -> runBriefing(auto, alarmTime), "briefing");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        if (worker != null) worker.interrupt();
        releaseWakeLock();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ main flow

    private void runBriefing(boolean auto, long alarmTime) {
        Prefs p = new Prefs(this);
        try {
            ScriptWriter.Data data = new ScriptWriter.Data();
            fetch(p, data);

            if (auto) {
                update("Ready. I'll speak after you dismiss the alarm.", true);
                Wake w = waitForWakeUp(p, alarmTime > 0 ? alarmTime : System.currentTimeMillis());
                if (w == Wake.CANCELLED || w == Wake.SNOOZED) return;
                if (w == Wake.TIMEOUT) {
                    postTapToHear(this);
                    return;
                }
                String today = Scheduler.dateKey(System.currentTimeMillis());
                if (!playNow && p.oncePerDay() && today.equals(p.lastBriefDate())) return;
                // Downloads can fail while the phone is in deep sleep; it's awake now, so retry.
                fetch(p, data);
            }
            if (stopRequested) return;

            update("Speaking your briefing…", false);
            List<ScriptWriter.Line> script = ScriptWriter.build(p, data);
            boolean finished;
            if (AiVoice.willUse(this, p)) {
                aiLinesSpoken = 0;
                try {
                    finished = speakAi(p, script);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Throwable e) {
                    // Never leave you in silence: carry on with the phone's own voice.
                    Log.e(TAG, "AI voice failed; using the phone's voice", e);
                    finished = !stopRequested
                            && speak(p, script.subList(Math.min(aiLinesSpoken, script.size()), script.size()));
                }
            } else {
                finished = speak(p, script);
            }
            if (auto && finished) p.setLastBriefDate(Scheduler.dateKey(System.currentTimeMillis()));
        } catch (InterruptedException e) {
            Log.i(TAG, "Briefing interrupted");
        } catch (Exception e) {
            Log.e(TAG, "Briefing failed", e);
        } finally {
            TextToSpeech t = tts;
            tts = null;
            if (t != null) {
                t.stop();
                t.shutdown();
            }
            releaseWakeLock();
            Scheduler.reschedule(this);
            main.post(this::finish);
        }
    }

    private void finish() {
        active = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Fetches whatever is still missing, in parallel. The calendar is always re-read (it's local). */
    private void fetch(Prefs p, ScriptWriter.Data d) throws InterruptedException {
        List<Thread> jobs = new ArrayList<>();
        if (d.weather == null && p.hasCity()) {
            jobs.add(new Thread(() -> {
                try {
                    d.weather = WeatherClient.forecast(p.lat(), p.lon());
                } catch (Exception e) {
                    Log.w(TAG, "Weather failed", e);
                }
            }));
        }
        if (d.news == null && p.headlines() > 0) {
            jobs.add(new Thread(() -> d.news = NewsClient.headlines(p.feeds(), p.headlines(), p.lang())));
        }
        for (Thread t : jobs) t.start();

        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            d.noCalendarPermission = true;
        } else {
            try {
                d.agenda = AgendaReader.today(this);
                d.noCalendarPermission = false;
            } catch (Exception e) {
                Log.w(TAG, "Calendar failed", e);
            }
        }
        for (Thread t : jobs) t.join(45_000);
    }

    private Wake waitForWakeUp(Prefs p, long alarmTime) throws InterruptedException {
        long deadline = Math.max(alarmTime, System.currentTimeMillis()) + MAX_WAIT_MS;
        boolean sawRinging = false;
        long quietSince = -1;

        while (true) {
            if (stopRequested) return Wake.CANCELLED;
            if (playNow) return Wake.READY;
            long now = System.currentTimeMillis();
            if (now > deadline) return Wake.TIMEOUT;

            if (isAlarmRinging()) {
                if (!sawRinging) update("Alarm ringing. Dismiss it and I'll start talking.", true);
                sawRinging = true;
                quietSince = -1;
            } else if (sawRinging) {
                if (quietSince < 0) quietSince = now;
                if (now - quietSince >= 2500) {
                    // The alarm stopped. Snoozed (a new alarm a few minutes away) or dismissed?
                    AlarmManager.AlarmClockInfo next =
                            getSystemService(AlarmManager.class).getNextAlarmClock();
                    if (next != null && next.getTriggerTime() > now
                            && next.getTriggerTime() - now <= SNOOZE_MAX_MS) {
                        postSnoozed(next.getTriggerTime());
                        return Wake.SNOOZED;
                    }
                    sleepChecking(p.delaySeconds() * 1000L);
                    return stopRequested ? Wake.CANCELLED : Wake.READY;
                }
            } else if (now > alarmTime + RING_DETECT_WINDOW_MS) {
                // Never heard the alarm (vibrate-only, or dismissed instantly):
                // start as soon as you're actually using the phone.
                if (isUserActive()) return Wake.READY;
            }
            Thread.sleep(500);
        }
    }

    private void sleepChecking(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !stopRequested && !playNow) Thread.sleep(200);
    }

    /** True while any app is playing a sound flagged as an alarm (your clock app ringing). */
    private boolean isAlarmRinging() {
        AudioManager am = getSystemService(AudioManager.class);
        for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
            AudioAttributes a = c.getAudioAttributes();
            if (a != null && a.getUsage() == AudioAttributes.USAGE_ALARM) return true;
        }
        return false;
    }

    private boolean isUserActive() {
        PowerManager pm = getSystemService(PowerManager.class);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return pm.isInteractive() && !km.isKeyguardLocked();
    }

    // ------------------------------------------------------------------ speech

    /** Speaks the script line by line. Returns true if it reached the end. */
    private boolean speak(Prefs p, List<ScriptWriter.Line> script) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        TextToSpeech t = new TextToSpeech(getApplicationContext(), s -> {
            status[0] = s;
            ready.countDown();
        });
        tts = t;
        if (!ready.await(20, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Text-to-speech engine did not start");
            return false;
        }

        String persona = p.persona();
        String voiceLang = p.lang();
        DeviceVoice.apply(t, ScriptWriter.locale(voiceLang, persona));
        t.setSpeechRate(ScriptWriter.rate(persona));

        AudioAttributes attrs = attributes(p);
        t.setAudioAttributes(attrs);

        Semaphore done = new Semaphore(0);
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { done.release(); }
            @Override @Deprecated public void onError(String id) { done.release(); }
            @Override public void onError(String id, int code) { done.release(); }
            @Override public void onStop(String id, boolean interrupted) { done.release(); }
        });

        AudioManager am = getSystemService(AudioManager.class);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build();
        am.requestAudioFocus(focus); // pauses music/podcasts while speaking
        try {
            Thread.sleep(400);
            int i = 0;
            for (ScriptWriter.Line line : script) {
                if (stopRequested) return false;
                if (!line.lang.equals(voiceLang)) {
                    // A headline in the other language (or back to yours after it): swap voice.
                    voiceLang = line.lang;
                    DeviceVoice.apply(t, ScriptWriter.locale(voiceLang, persona));
                }
                done.drainPermits();
                if (t.speak(line.text, TextToSpeech.QUEUE_ADD, null, "line" + (i++)) != TextToSpeech.SUCCESS) {
                    continue;
                }
                done.tryAcquire(15_000 + line.text.length() * 150L, TimeUnit.MILLISECONDS);
                if (line.pauseAfterMs > 0) Thread.sleep(line.pauseAfterMs);
            }
            return !stopRequested;
        } finally {
            am.abandonAudioFocusRequest(focus);
        }
    }

    private static AudioAttributes attributes(Prefs p) {
        return new AudioAttributes.Builder()
                .setUsage(p.alarmVolume() ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** A generated sentence, or the end of the script. */
    private static final class Clip {
        static final Clip END = new Clip(null, 0);
        final float[] samples;
        final int pauseAfterMs;

        Clip(float[] samples, int pauseAfterMs) {
            this.samples = samples;
            this.pauseAfterMs = pauseAfterMs;
        }
    }

    /**
     * Speaks with the downloaded AI voice. The next sentence is generated while the current one
     * plays, so there are no gaps. Throws if the voice can't be used, so the caller can fall
     * back to the phone's voice from {@link #aiLinesSpoken} on.
     */
    private boolean speakAi(Prefs p, List<ScriptWriter.Line> script) throws Exception {
        String mainLang = p.lang();
        AiVoice.Pack pack = AiVoice.forLang(mainLang);
        String persona = p.persona();
        int speaker = AiVoice.speaker(pack, persona);
        float speed = ScriptWriter.rate(persona);

        OfflineTts engine = AiVoice.load(this, pack);
        // Headlines in the other language get that language's voice. Get it ready now, in the
        // background, while the greeting and weather play: by the news it's loaded, so the
        // switch costs no extra pause.
        ExecutorService prep = Executors.newSingleThreadExecutor();
        Map<String, Future<Object>> otherVoices = new HashMap<>();
        for (ScriptWriter.Line line : script) {
            if (line.lang.equals(mainLang) || otherVoices.containsKey(line.lang)) continue;
            String lang = line.lang;
            otherVoices.put(lang, prep.submit(() -> prepareVoice(lang)));
        }
        prep.shutdown();

        AudioAttributes attrs = attributes(p);
        PcmPlayer player = null;
        Thread producer = null;
        AudioManager am = getSystemService(AudioManager.class);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build();
        try {
            player = new PcmPlayer(engine.sampleRate(), attrs);
            aiPlayer = player;
            int playerRate = player.sampleRate();
            BlockingQueue<Clip> clips = new ArrayBlockingQueue<>(2);
            Throwable[] failure = {null};
            producer = new Thread(() -> {
                try {
                    for (ScriptWriter.Line line : script) {
                        if (stopRequested) break;
                        float[] samples = null;
                        int rate = 0;
                        Object voice = line.lang.equals(mainLang) ? null : readyVoice(otherVoices.get(line.lang));
                        try {
                            if (voice instanceof OfflineTts) {
                                AiVoice.Pack other = AiVoice.forLang(line.lang);
                                GeneratedAudio a = ((OfflineTts) voice).generate(
                                        line.text, AiVoice.speaker(other, persona), speed);
                                samples = a.getSamples();
                                rate = a.getSampleRate();
                            } else if (voice instanceof DeviceSynth) {
                                DeviceSynth.Audio a = ((DeviceSynth) voice).synthesize(
                                        line.text, ScriptWriter.locale(line.lang, persona), speed);
                                samples = a.samples;
                                rate = a.sampleRate;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Other-language voice failed; using the main one", e);
                            samples = null;
                        }
                        if (samples == null) {
                            GeneratedAudio a = engine.generate(line.text, speaker, speed);
                            samples = a.getSamples();
                            rate = a.getSampleRate();
                        }
                        VoiceLeveler.Stats level = new VoiceLeveler.Stats();
                        samples = VoiceLeveler.process(samples, rate, level);
                        Log.d(TAG, "AI voice [" + line.lang + "] "
                                + line.text.substring(0, Math.min(40, line.text.length())) + " | " + level);
                        // Same loudness and one continuous stream whichever voice made it.
                        clips.put(new Clip(PcmPlayer.resample(samples, rate, playerRate), line.pauseAfterMs));
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    failure[0] = e;
                }
                try {
                    clips.put(Clip.END);
                } catch (InterruptedException ignored) {
                    // the speaking side has already given up
                }
            }, "ai-voice");
            producer.start();

            am.requestAudioFocus(focus); // pauses music/podcasts while speaking
            while (true) {
                long waitStart = System.nanoTime();
                Clip c = clips.take();
                if (c == Clip.END) break;
                long waitedMs = (System.nanoTime() - waitStart) / 1_000_000;
                // The player still has ~0.5 s buffered, so short waits are inaudible.
                if (aiLinesSpoken > 0 && waitedMs > 300) {
                    Log.d(TAG, "Waited " + waitedMs + " ms for sentence " + (aiLinesSpoken + 1));
                }
                if (!player.write(c.samples) || !player.silence(c.pauseAfterMs)) return false;
                aiLinesSpoken++;
            }
            player.drain();
            if (failure[0] != null) throw new Exception("AI voice stopped", failure[0]);
            Log.i(TAG, "AI voice spoke " + aiLinesSpoken + " of " + script.size() + " lines");
            return !stopRequested;
        } finally {
            aiPlayer = null;
            if (player != null) player.stop();
            // The engine may still be mid-sentence: wait before freeing it under its feet.
            if (producer != null) joinQuietly(producer);
            if (player != null) player.release();
            engine.release();
            for (Future<Object> f : otherVoices.values()) releaseVoice(f);
            am.abandonAudioFocusRequest(focus);
        }
    }

    /** The other language's AI voice if downloaded, else the phone's voice for it. */
    private Object prepareVoice(String lang) throws Exception {
        AiVoice.Pack pack = AiVoice.forLang(lang);
        if (AiVoice.isInstalled(this, pack)) {
            try {
                return AiVoice.load(this, pack);
            } catch (Throwable e) {
                Log.w(TAG, "Couldn't load the " + lang + " AI voice; using the phone's", e);
            }
        }
        return new DeviceSynth(this);
    }

    /** Waits for a voice being prepared (normally long done). Null if it couldn't be prepared. */
    private static Object readyVoice(Future<Object> f) throws InterruptedException {
        if (f == null) return null;
        try {
            return f.get();
        } catch (ExecutionException e) {
            Log.w(TAG, "Other-language voice unavailable", e.getCause());
            return null;
        }
    }

    /** Frees a prepared voice, waiting for it to finish loading if it's still loading. */
    private static void releaseVoice(Future<Object> f) {
        boolean interrupted = false;
        while (true) {
            try {
                Object v = f.get();
                if (v instanceof OfflineTts) ((OfflineTts) v).release();
                else if (v instanceof DeviceSynth) ((DeviceSynth) v).close();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (Exception e) {
                break; // it never got made: nothing to free
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void joinQuietly(Thread t) {
        boolean interrupted = false;
        t.interrupt();
        while (t.isAlive()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    // ------------------------------------------------------------------ notifications

    private void goForeground() {
        Notification n = runningNotification(this, currentText, currentWaiting);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_RUNNING, n);
        }
    }

    private void update(String text, boolean waiting) {
        currentText = text;
        currentWaiting = waiting;
        getSystemService(NotificationManager.class)
                .notify(NOTIF_RUNNING, runningNotification(this, text, waiting));
    }

    private static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH_RUNNING, "Briefing in progress", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(
                CH_ALERTS, "Briefing ready", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static PendingIntent playIntent(Context ctx, int requestCode) {
        Intent i = new Intent(ctx, BriefingService.class).setAction(ACTION_PLAY_NOW);
        return PendingIntent.getForegroundService(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification runningNotification(Context ctx, String text, boolean waiting) {
        createChannels(ctx);
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(ctx, 1,
                new Intent(ctx, BriefingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon icon = Icon.createWithResource(ctx, R.drawable.ic_notification);

        Notification.Builder b = new Notification.Builder(ctx, CH_RUNNING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Morning briefing")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false);
        if (waiting) b.addAction(new Notification.Action.Builder(icon, "Play now", playIntent(ctx, 2)).build());
        b.addAction(new Notification.Action.Builder(icon, "Stop", stop).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    /** Fallback when we couldn't speak automatically: one tap plays the briefing. */
    static void postTapToHear(Context ctx) {
        createChannels(ctx);
        Notification n = new Notification.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Your morning briefing is ready")
                .setContentText("Tap to hear the weather, your day and the news.")
                .setContentIntent(playIntent(ctx, 3))
                .setAutoCancel(true)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(NOTIF_ALERT, n);
    }

    private void postSnoozed(long nextAlarm) {
        Icon icon = Icon.createWithResource(this, R.drawable.ic_notification);
        Notification n = new Notification.Builder(this, CH_RUNNING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Snoozed")
                .setContentText("I'll brief you after the "
                        + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(nextAlarm)) + " alarm.")
                .addAction(new Notification.Action.Builder(icon, "Play now", playIntent(this, 4)).build())
                .setAutoCancel(true)
                .setTimeoutAfter(SNOOZE_MAX_MS)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_ALERT, n);
    }

    // ------------------------------------------------------------------ wake lock

    private void acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wakecast:briefing");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(MAX_WAIT_MS + 15L * 60 * 1000);
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
