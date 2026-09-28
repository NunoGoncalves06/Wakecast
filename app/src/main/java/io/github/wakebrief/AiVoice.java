package io.github.wakebrief;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Free AI voices that run on the phone (sherpa-onnx, open source). Nothing is sent anywhere:
 * the voice is downloaded once and then works offline. One pack per language.
 */
final class AiVoice {

    private static final String BASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";
    private static final String INSTALLED_MARKER = ".installed";

    private AiVoice() {}

    static final class Pack {
        final String id;          // archive name, also the folder it unpacks into
        final String name;        // shown to the user
        final int megabytes;      // download size
        final boolean kokoro;     // Kokoro (many speakers) vs Piper/VITS (one speaker)
        final String credit;      // author and licence, shown next to the voice

        Pack(String id, String name, int megabytes, boolean kokoro, String credit) {
            this.id = id;
            this.name = name;
            this.megabytes = megabytes;
            this.kokoro = kokoro;
            this.credit = credit;
        }

        String url() { return BASE + id + ".tar.bz2"; }
    }

    /** Kokoro: among the most natural free English voices, with British and American speakers. */
    static final Pack ENGLISH = new Pack("kokoro-int8-en-v0_19", "Kokoro", 103, true,
            "Voice: Kokoro-82M by hexgrad, Apache 2.0 licence.");
    /**
     * Piper, trained on European Portuguese (not Brazilian). "Dii", not "Miro": measured side by
     * side, Miro is ~15 dB quieter and carries a static hiss (pauses only ~14 dB under the
     * voice, strong 7-11 kHz noise), while Dii is clean (~38 dB) and speaks at a normal level.
     */
    static final Pack PORTUGUESE = new Pack("vits-piper-pt_PT-miro-high", "Piper", 67, false,
            "Voice: \"Miro\" by OpenVoiceOS, CC BY-NC-SA 4.0, free for personal use.");

    static Pack forLang(String lang) {
        return "pt".equals(lang) ? PORTUGUESE : ENGLISH;
    }

    static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "voices");
    }

    static File dir(Context ctx, Pack p) {
        return new File(root(ctx), p.id);
    }

    static boolean isInstalled(Context ctx, Pack p) {
        return new File(dir(ctx, p), INSTALLED_MARKER).exists();
    }

    static void markInstalled(File dir) throws IOException {
        File marker = new File(dir, INSTALLED_MARKER);
        if (!marker.createNewFile() && !marker.exists()) {
            throw new IOException("Couldn't finish installing the voice");
        }
    }

    /** True when the briefing will use the AI voice (chosen and downloaded). */
    static boolean willUse(Context ctx, Prefs p) {
        return "ai".equals(p.voiceEngine()) && isInstalled(ctx, forLang(p.lang()));
    }

    static void remove(Context ctx, Pack p) {
        deleteRecursively(dir(ctx, p));
    }

    /**
     * Deletes voices the app no longer uses (e.g. the old Portuguese "Miro" pack) so they don't
     * keep taking space. Leaves current packs and their in-progress downloads alone.
     */
    static void removeObsolete(Context ctx) {
        File[] dirs = root(ctx).listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            String n = d.getName();
            if (n.startsWith(ENGLISH.id) || n.startsWith(PORTUGUESE.id)) continue;
            deleteRecursively(d);
        }
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * Each persona gets its own Kokoro speaker; the Portuguese pack has one voice, so there the
     * personas differ by their words and pace only.
     */
    static int speaker(Pack p, String persona) {
        if (!p.kokoro) return 0;
        // kokoro-en-v0_19 speakers: 0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky,
        // 5 am_adam, 6 am_michael, 7 bf_emma, 8 bf_isabella, 9 bm_george, 10 bm_lewis
        switch (persona) {
            case "sergeant": return 5;  // am_adam: deep American male
            case "radio": return 1;     // af_bella: bright, upbeat
            case "pirate": return 10;   // bm_lewis: gravelly British male
            case "zen": return 4;       // af_sky: soft and calm
            default: return 9;          // bm_george: the British butler
        }
    }

    /** Loads the voice. Takes a few seconds and a good chunk of memory: never on the main thread. */
    static OfflineTts load(Context ctx, Pack p) throws FileNotFoundException {
        File d = dir(ctx, p);
        File model = findModel(d);
        File tokens = require(new File(d, "tokens.txt"));
        File espeak = require(new File(d, "espeak-ng-data"));

        OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
        if (p.kokoro) {
            OfflineTtsKokoroModelConfig k = new OfflineTtsKokoroModelConfig();
            k.setModel(model.getAbsolutePath());
            k.setVoices(require(new File(d, "voices.bin")).getAbsolutePath());
            k.setTokens(tokens.getAbsolutePath());
            k.setDataDir(espeak.getAbsolutePath());
            mc.setKokoro(k);
        } else {
            OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
            v.setModel(model.getAbsolutePath());
            v.setTokens(tokens.getAbsolutePath());
            v.setDataDir(espeak.getAbsolutePath());
            mc.setVits(v);
        }
        // Leave a core free so the phone stays responsive while it talks.
        mc.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(mc);
        return new OfflineTts(null, config);
    }

    /** The pack's .onnx file (the int8 one when there's a choice: it's what we downloaded). */
    private static File findModel(File d) throws FileNotFoundException {
        File[] onnx = d.listFiles((dir, name) -> name.endsWith(".onnx"));
        if (onnx == null || onnx.length == 0) throw new FileNotFoundException("No model in " + d);
        for (File f : onnx) if (f.getName().contains("int8")) return f;
        return onnx[0];
    }

    private static File require(File f) throws FileNotFoundException {
        if (!f.exists()) throw new FileNotFoundException("Missing " + f);
        return f;
    }
}
