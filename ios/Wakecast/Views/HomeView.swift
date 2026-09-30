import SwiftUI

/// Home. For now a skeleton of the final layout: the running order of the briefing,
/// in the order the Android app reads it (see MainActivity.renderRundown).
struct HomeView: View {
    private let chapters: [(symbol: String, title: String, detail: String)] = [
        ("1.circle", "Weather", "Conditions, rain and what to wear"),
        ("2.circle", "Your day", "Events from your calendars"),
        ("3.circle", "To-dos", "Reminders you type in"),
        ("4.circle", "News", "Headlines from your sources"),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section("Running order") {
                    ForEach(chapters, id: \.title) { chapter in
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chapter.title)
                                Text(chapter.detail)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: chapter.symbol)
                                .foregroundStyle(.tint)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Wakecast")
            .accessibilityIdentifier("home")
        }
    }
}

#Preview {
    HomeView()
}
