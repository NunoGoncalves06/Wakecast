package io.github.wakebrief;

import java.util.Locale;

/**
 * Brings every AI-voice sentence to the same speaking loudness. Some voices (the Portuguese one)
 * come out much quieter than others. Turning them up naively also turns up the hiss between
 * words, so:
 * <ul>
 *   <li>the gain is measured on the speech only, not on the pauses;</li>
 *   <li>the pauses are not boosted (a gentle gate), so background noise stays where it was;</li>
 *   <li>a soft limiter keeps loud syllables from clipping into distortion.</li>
 * </ul>
 */
final class VoiceLeveler {

    /** Speaking loudness to aim for: about -18 dBFS RMS, a normal level for speech. */
    private static final float TARGET_RMS = 0.125f;
    /** Never boost more than this (about +18 dB): past that it's mostly noise we'd be raising. */
    private static final float MAX_GAIN = 8f;
    /** Frames this far below the loudest one count as pause/noise, not speech (-30 dB). */
    private static final float SPEECH_THRESHOLD = 0.0316f;
    /** Where the soft limiter starts bending the waveform. */
    private static final float LIMIT_KNEE = 0.85f;
    private static final int FRAME_MS = 20;

    private VoiceLeveler() {}

    /** What {@link #process} measured, for the log. */
    static final class Stats {
        float speechRmsBefore, speechRmsAfter, pauseRmsBefore, pauseRmsAfter, peakBefore, peakAfter, gain;

        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "gain %+.1f dB | speech %.1f -> %.1f dBFS | pauses %.1f -> %.1f dBFS | peak %.1f -> %.1f dBFS",
                    db(gain), db(speechRmsBefore), db(speechRmsAfter), db(pauseRmsBefore), db(pauseRmsAfter),
                    db(peakBefore), db(peakAfter));
        }
    }

    /** RMS of the frames that are (or aren't) speech, and the peak. */
    private static float[] measure(float[] s, int frame, float[] rmsBefore, float threshold) {
        double speech = 0, pause = 0;
        int ns = 0, np = 0;
        float peak = 0;
        for (int f = 0; f < rmsBefore.length; f++) {
            int from = f * frame, to = Math.min(s.length, from + frame);
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += s[i] * s[i];
                peak = Math.max(peak, Math.abs(s[i]));
            }
            if (rmsBefore[f] >= threshold) {
                speech += sum / (to - from);
                ns++;
            } else {
                pause += sum / (to - from);
                np++;
            }
        }
        return new float[]{(float) Math.sqrt(speech / Math.max(1, ns)),
                (float) Math.sqrt(pause / Math.max(1, np)), peak};
    }

    /** Levels {@code s} in place and returns it. */
    static float[] process(float[] s, int sampleRate, Stats stats) {
        int frame = Math.max(1, sampleRate * FRAME_MS / 1000);
        int frames = (s.length + frame - 1) / frame;
        if (frames == 0) return s;

        // 1. Loudness of each 20 ms frame, and of the loudest one.
        float[] rms = new float[frames];
        float loudest = 0f, peak = 0f;
        for (int f = 0; f < frames; f++) {
            int from = f * frame, to = Math.min(s.length, from + frame);
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += s[i] * s[i];
                peak = Math.max(peak, Math.abs(s[i]));
            }
            rms[f] = (float) Math.sqrt(sum / (to - from));
            loudest = Math.max(loudest, rms[f]);
        }
        if (loudest < 1e-5f) return s; // silence

        // 2. How loud the speech itself is (pauses left out), hence the gain it needs.
        float threshold = loudest * SPEECH_THRESHOLD;
        double speechSum = 0;
        int speechFrames = 0;
        for (float r : rms) {
            if (r >= threshold) {
                speechSum += r * r;
                speechFrames++;
            }
        }
        float speechRms = (float) Math.sqrt(speechSum / Math.max(1, speechFrames));
        float gain = Math.max(1f, Math.min(MAX_GAIN, TARGET_RMS / speechRms));
        if (stats != null) {
            float[] m = measure(s, frame, rms, threshold);
            stats.speechRmsBefore = m[0];
            stats.pauseRmsBefore = m[1];
            stats.peakBefore = peak;
            stats.gain = gain;
        }

        // 3. Per-frame gain: full on speech, fading back to 1x (no boost) in the pauses, so
        //    the hiss between words isn't raised. Smoothed so it never clicks or pumps.
        float[] frameGain = new float[frames];
        for (int f = 0; f < frames; f++) {
            float openness = Math.min(1f, rms[f] / threshold); // 0 = silence, 1 = speech
            frameGain[f] = 1f + (gain - 1f) * openness * openness;
        }
        // Close slowly after speech (don't chop word tails), open slightly ahead of it
        // (don't clip word starts).
        for (int f = 1; f < frames; f++) {
            frameGain[f] = Math.max(frameGain[f], frameGain[f - 1] * 0.85f + frameGain[f] * 0.15f);
        }
        for (int f = frames - 2; f >= 0; f--) {
            frameGain[f] = Math.max(frameGain[f], frameGain[f + 1] * 0.6f + frameGain[f] * 0.4f);
        }

        // 4. Apply, interpolating between frames, then soft-limit the peaks.
        for (int i = 0; i < s.length; i++) {
            float pos = (float) i / frame - 0.5f;
            int f0 = Math.max(0, Math.min(frames - 1, (int) Math.floor(pos)));
            int f1 = Math.min(frames - 1, f0 + 1);
            float t = Math.max(0f, Math.min(1f, pos - f0));
            float g = frameGain[f0] + (frameGain[f1] - frameGain[f0]) * t;
            s[i] = limit(s[i] * g);
        }
        if (stats != null) {
            float[] m = measure(s, frame, rms, threshold);
            stats.speechRmsAfter = m[0];
            stats.pauseRmsAfter = m[1];
            stats.peakAfter = m[2];
        }
        return s;
    }

    /** Transparent below the knee, then bends smoothly so the output never reaches full scale. */
    private static float limit(float x) {
        float a = Math.abs(x);
        if (a <= LIMIT_KNEE) return x;
        float room = 0.99f - LIMIT_KNEE;
        float bent = LIMIT_KNEE + room * (float) Math.tanh((a - LIMIT_KNEE) / room);
        return Math.copySign(bent, x);
    }

    private static float db(float v) {
        return (float) (20 * Math.log10(Math.max(v, 1e-6f)));
    }
}
