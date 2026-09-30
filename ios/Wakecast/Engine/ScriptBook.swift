import Foundation

/// The personalities and their lines, from shared/scripts.json (exported from the Android
/// ScriptWriter, so both apps say exactly the same things).
struct ScriptBook: Decodable {
    struct Persona: Decodable, Identifiable {
        let key: String
        let name: String
        let emoji: String
        let tagline: String
        /// Speech rate relative to normal (1.0).
        let rate: Double
        /// Voice locale per language, e.g. ["en": "en-GB", "pt": "pt-PT"].
        let locale: [String: String]
        /// How the persona addresses you when no name is set, per language.
        let address: [String: String]

        var id: String { key }
    }

    let personas: [Persona]
    /// persona > language > slot > variants
    let templates: [String: [String: [String: [String]]]]

    static let shared: ScriptBook = load(from: .main)

    static func load(from bundle: Bundle) -> ScriptBook {
        guard let url = bundle.url(forResource: "scripts", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let book = try? JSONDecoder().decode(ScriptBook.self, from: data) else {
            fatalError("scripts.json is missing or invalid in the app bundle")
        }
        return book
    }

    func persona(_ key: String) -> Persona? {
        personas.first { $0.key == key }
    }

    /// The variants for a slot, falling back to the butler's like the Android app.
    func variants(persona: String, lang: String, slot: String) -> [String] {
        if let v = templates[persona]?[lang]?[slot], !v.isEmpty { return v }
        return templates["butler"]?[lang]?[slot] ?? []
    }
}
