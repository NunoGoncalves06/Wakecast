package io.github.wakebrief;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;

/**
 * Picks the best voice the phone's own text-to-speech has for a language. Left alone, the
 * engine often uses a compact default voice, which is where most of the "robot" sound comes from.
 */
final class DeviceVoice {

    private DeviceVoice() {}

    /** Sets the language and, when there is one, the best installed voice for it. */
    static void apply(TextToSpeech t, Locale wanted) {
        if (t.setLanguage(wanted) < TextToSpeech.LANG_AVAILABLE) {
            t.setLanguage(Locale.forLanguageTag(wanted.getLanguage()));
        }
        Voice best = best(t, wanted);
        if (best != null) t.setVoice(best);
    }

    static Voice best(TextToSpeech t, Locale wanted) {
        Set<Voice> voices;
        try {
            voices = t.getVoices();
        } catch (RuntimeException e) {
            return null; // some engines throw instead of returning null
        }
        if (voices == null) return null;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice v : voices) {
            int s = score(v, wanted);
            if (s > bestScore) {
                best = v;
                bestScore = s;
            }
        }
        return bestScore >= 0 ? best : null;
    }

    /** Negative = unusable. Accent first, then working offline (the alarm may ring with no signal), then quality. */
    private static int score(Voice v, Locale wanted) {
        Set<String> features = v.getFeatures();
        if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) return -1;
        Locale l = v.getLocale();
        if (l == null || !iso3(l.getLanguage()).equals(iso3(wanted.getLanguage()))) return -1;
        int s = 0;
        if (!wanted.getCountry().isEmpty() && iso3Country(l).equals(iso3Country(wanted))) s += 10_000;
        if (!v.isNetworkConnectionRequired()) s += 5_000;
        s += v.getQuality();          // 100 (very low) … 500 (very high)
        s -= v.getLatency() / 10;     // tie-break: quicker to start
        return s;
    }

    private static String iso3(String lang) {
        try {
            return new Locale(lang).getISO3Language();
        } catch (MissingResourceException e) {
            return lang;
        }
    }

    private static String iso3Country(Locale l) {
        try {
            return l.getISO3Country();
        } catch (MissingResourceException e) {
            return l.getCountry();
        }
    }
}
