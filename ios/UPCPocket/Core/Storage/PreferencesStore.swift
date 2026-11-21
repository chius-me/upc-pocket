import Foundation

struct AppPreferences: Codable, Equatable, Sendable {
    var campus: CampusPreference = .all
    var theme: ThemePreference = .system
}

actor PreferencesStore {
    private let fileURL: URL?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(fileURL: URL? = nil) {
        self.fileURL = fileURL
    }

    func load() async -> AppPreferences {
        guard let targetURL = await resolvedURL() else {
            return AppPreferences()
        }
        guard let data = try? Data(contentsOf: targetURL),
              let decoded = try? decoder.decode(AppPreferences.self, from: data) else {
            return AppPreferences()
        }
        return decoded
    }

    func save(_ preferences: AppPreferences) async {
        guard let targetURL = await resolvedURL() else { return }
        guard let data = try? encoder.encode(preferences) else { return }
        try? data.write(to: targetURL, options: .atomic)
        try? await LocalStorageExclusion.shared.excludeFromBackup(url: targetURL)
    }

    private func resolvedURL() async -> URL? {
        if let fileURL {
            let parent = fileURL.deletingLastPathComponent()
            try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: parent)
            return fileURL
        }
        guard let base = try? await LocalStorageExclusion.shared.noBackupBaseDirectory() else {
            return nil
        }
        return base.appendingPathComponent("preferences.json", isDirectory: false)
    }
}
