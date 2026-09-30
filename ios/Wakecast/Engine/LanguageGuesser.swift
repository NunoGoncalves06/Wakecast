import Foundation

/// Tells English and Portuguese headlines apart, offline and instantly, from their most common
/// little words and from letters only Portuguese uses. Says "don't know" rather than guess.
/// A line-for-line port of LanguageGuesser.java.
enum LanguageGuesser {

    // Words that are frequent in one language and rare or absent in the other. Words both
    // languages share ("a", "as", "do", "no", "on"…) are left out on purpose.
    private static let pt: Set<String> = [
        "o", "os", "e", "é", "de", "da", "das", "dos", "em", "na", "nas", "nos", "ao", "aos", "à", "às",
        "um", "uma", "uns", "umas", "que", "não", "com", "para", "pelo", "pela", "pelos", "pelas",
        "mais", "sobre", "após", "até", "entre", "contra", "como", "seu", "sua", "seus", "suas",
        "foi", "ser", "está", "estão", "são", "há", "já", "diz", "dizem", "anos", "ano", "também",
        "quando", "onde", "porque", "sem", "ainda", "muito", "novo", "nova", "vai", "vão", "pode",
        "português", "portugal", "governo", "câmara", "presidente", "ministro", "hoje",
    ]
    private static let en: Set<String> = [
        "the", "of", "and", "to", "in", "for", "with", "at", "by", "from", "after", "over", "into",
        "says", "said", "is", "are", "was", "were", "will", "would", "new", "how", "what", "why",
        "who", "his", "her", "their", "it", "its", "this", "that", "be", "been", "has", "have", "had",
        "amid", "against", "about", "than", "up", "out", "not", "more", "could", "should",
        "government", "minister", "president", "police", "people", "year", "years", "today",
    ]
    private static let wordBreaks = CharacterSet.letters.union(CharacterSet(charactersIn: "'")).inverted

    /// "en", "pt", or nil when it isn't clear enough to be worth switching voice.
    static func guess(_ text: String) -> String? {
        guard !text.isEmpty else { return nil }
        let lower = text.lowercased()
        var ptScore = 0, enScore = 0
        for c in lower {
            if "ãõç".contains(c) { ptScore += 2 }            // only Portuguese has these
            else if "áéíóúâêôà".contains(c) { ptScore += 1 } // accents: Portuguese-leaning
        }
        for w in lower.components(separatedBy: wordBreaks) where !w.isEmpty {
            if pt.contains(w) { ptScore += 1 }
            if en.contains(w) { enScore += 1 }
            if w.hasSuffix("'s") { enScore += 1 }
        }
        if ptScore >= 2 && ptScore >= enScore + 2 { return "pt" }
        if enScore >= 2 && enScore >= ptScore + 2 { return "en" }
        return nil
    }

    /// The language to read `text` in: its own if clearly detected, otherwise `fallback`.
    static func pick(_ text: String, fallback: String) -> String {
        guess(text) ?? fallback
    }
}
