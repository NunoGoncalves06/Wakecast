package io.github.wakebrief;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tells English and Portuguese headlines apart, offline and instantly, from their most common
 * little words and from letters only Portuguese uses. Says "don't know" rather than guess.
 */
final class LanguageGuesser {

    // Words that are frequent in one language and rare or absent in the other. Words both
    // languages share ("a", "as", "do", "no", "on"…) are left out on purpose.
    private static final Set<String> PT = new HashSet<>(Arrays.asList(
            "o", "os", "e", "é", "de", "da", "das", "dos", "em", "na", "nas", "nos", "ao", "aos", "à", "às",
            "um", "uma", "uns", "umas", "que", "não", "com", "para", "pelo", "pela", "pelos", "pelas",
            "mais", "sobre", "após", "até", "entre", "contra", "como", "seu", "sua", "seus", "suas",
            "foi", "ser", "está", "estão", "são", "há", "já", "diz", "dizem", "anos", "ano", "também",
            "quando", "onde", "porque", "sem", "ainda", "muito", "novo", "nova", "vai", "vão", "pode",
            "português", "portugal", "governo", "câmara", "presidente", "ministro", "hoje"));
    private static final Set<String> EN = new HashSet<>(Arrays.asList(
            "the", "of", "and", "to", "in", "for", "with", "at", "by", "from", "after", "over", "into",
            "says", "said", "is", "are", "was", "were", "will", "would", "new", "how", "what", "why",
            "who", "his", "her", "their", "it", "its", "this", "that", "be", "been", "has", "have", "had",
            "amid", "against", "about", "than", "up", "out", "not", "more", "could", "should",
            "government", "minister", "president", "police", "people", "year", "years", "today"));

    private LanguageGuesser() {}

    /** "en", "pt", or null when it isn't clear enough to be worth switching voice. */
    static String guess(String text) {
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        int pt = 0, en = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == 'ã' || c == 'õ' || c == 'ç') pt += 2;             // only Portuguese has these
            else if ("áéíóúâêôà".indexOf(c) >= 0) pt += 1;            // accents: Portuguese-leaning
        }
        for (String w : lower.split("[^\\p{L}']+")) {
            if (w.isEmpty()) continue;
            if (PT.contains(w)) pt += 1;
            if (EN.contains(w)) en += 1;
            if (w.endsWith("'s")) en += 1;
        }
        if (pt >= 2 && pt >= en + 2) return "pt";
        if (en >= 2 && en >= pt + 2) return "en";
        return null;
    }

    /** The language to read {@code text} in: its own if clearly detected, otherwise {@code fallback}. */
    static String pick(String text, String fallback) {
        String g = guess(text);
        return g != null ? g : fallback;
    }
}
