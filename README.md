# Wakecast

**Your alarm, with a morning briefing.** When you dismiss your alarm, Wakecast reads out the weather, your day and the news, in the voice and personality you pick.

It's a small native Android app (Java, no accounts, no ads, no tracking). Everything runs on your phone.

## What it reads out

1. **Weather**: current conditions, high and low, when rain starts, and what to wear (umbrella, coat, sunscreen, wind), plus sunset.
2. **Your day**: today's events from every calendar on the phone, and how long until the first one.
3. **To-dos**: reminders you type into the app.
4. **News**: headlines from any sites you choose. Paste a website (for example `economist.com`) and Wakecast finds its news feed.

## Features

- **Five personalities**: Butler, Drill Sergeant, Radio Host, Pirate Captain and Zen Guide. Each has its own wording for every line.
- **Two languages**: English and European Portuguese. A headline in the other language is read by that language's voice, then it switches back.
- **Voices**:
  - **Phone voice**: your phone's own text-to-speech, using the best installed voice.
  - **AI voice (free, optional download)**: natural on-device neural voices (Kokoro for English, Piper "Dii" for Portuguese). No account, works offline.
- **Sleep ring**: shows how much sleep you'd get if you went to bed now, measured against 8 hours:
  - **blue**: more than 12 h
  - **green**: 8–12 h
  - **yellow**: under 8 h
  - **red**: under 5 h

  It comes with a short tip.
- **Snooze-aware**: if you snooze, it waits for the next ring.
- **Morning window**: only alarms inside it (for example 04:00–12:00) get a briefing, so nap alarms stay quiet.
- **Looks like a built-in Android app**: Material 3 Expressive components (segmented lists, connected button groups, sliders, wavy progress), Material You colours taken from your wallpaper (or a colour you pick), light / dark theme, and a proper Settings screen.

## How it knows when you wake up

1. Android tells the app whenever an alarm is added or changed in *any* clock app.
2. A minute before the alarm, Wakecast downloads the weather and news.
3. It waits while the alarm rings (detected through Android's audio system).
4. When you dismiss it, it waits a few seconds and starts talking. A notification offers **Play now** and **Stop**.
5. If the alarm is vibrate-only, it starts when you unlock the phone.

## Privacy

- No accounts, no analytics, no ads.
- The only network requests are the weather ([Open-Meteo](https://open-meteo.com), no key), the news feeds you choose, and the one-time AI voice download.
- Calendar events and to-dos never leave the phone.
- The AI voices run on the phone. Nothing you hear is generated in the cloud.

## Install

Download `Wakecast.apk` from the [Releases](../../releases) page and open it on your phone:

1. Allow installing from the app you opened it with (Files, Drive, WhatsApp…) when asked.
2. If Play Protect warns about an unknown app: **More details → Install anyway**.
3. Open Wakecast. If anything is missing (notifications, calendar, exact alarms, unrestricted battery, your city), a **Needs attention** card shows up; tap it to fix everything in **Settings › Permissions & battery**.

Requires Android 8.0 or newer.

> **Tip:** many phones close apps overnight to save battery. In *App info › Battery* allow background activity (or choose *Unrestricted*).

## Build

1. Open the project in Android Studio (**File › Open**) and let Gradle sync.
2. Select the **app** run configuration and your device, then press **Run**.

To build an APK: **Build › Build App Bundle(s) / APK(s) › Build APK(s)**. The file lands in `app/build/outputs/apk/debug/`.

## Project layout

| File | What it does |
|---|---|
| `MainActivity` | Home: next briefing, sleep ring, tomorrow's briefing, Play / Stop |
| `SettingsActivity` | Settings: voice, weather, news, to-dos, schedule, appearance, permissions, about |
| `BaseActivity`, `Ui` | Shared screen frame (Material You colours, top app bar) and Material 3 Expressive building blocks |
| `Setup` | What the app needs (permissions, city, voice) and how to fix each |
| `Scheduler`, `AlarmTriggerReceiver`, `SystemEventsReceiver`, `RescheduleJob` | Follow the phone's next alarm |
| `BriefingService` | Fetches data, waits for the alarm, speaks |
| `ScriptWriter` | Turns the data into each persona's script |
| `WeatherClient`, `AgendaReader`, `NewsClient`, `FeedFinder`, `Net` | Weather, calendar and news |
| `LanguageGuesser` | Tells English and Portuguese headlines apart |
| `DeviceVoice`, `DeviceSynth` | The phone's own voice |
| `AiVoice`, `VoiceDownloader`, `PcmPlayer`, `VoiceLeveler` | On-device AI voices: download, playback, loudness |
| `SleepGauge` | The sleep ring's colours and tips |
| `Prefs` | All settings |

## Credits

- Speech engine: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0)
- Voices (downloaded on demand):
  - [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) by hexgrad (Apache 2.0)
  - Piper "Dii" by OpenVoiceOS (CC BY-NC-SA 4.0, free for personal use)
- Weather: [Open-Meteo](https://open-meteo.com)
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0)
- UI components: [Material Components for Android](https://github.com/material-components/material-components-android) (Apache 2.0)
