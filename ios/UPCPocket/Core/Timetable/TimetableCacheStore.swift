import Foundation

/// Per-account offline cache for verified complete timetable weeks in backup-excluded storage.
actor TimetableCacheStore {
    private static let cacheFileName = "cache-v1.json"
    private static let maxCachedWeeks = 32
    private static let maxCacheBytes = 2 * 1024 * 1024

    private let directoryURL: URL?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(directoryURL: URL? = nil) {
        self.directoryURL = directoryURL
    }

    func load(accountId: String) async -> TimetableOfflineCache? {
        guard let fileURL = await resolveFileURL(),
              FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              data.count <= Self.maxCacheBytes,
              let decoded = try? decoder.decode(TimetableOfflineCache.self, from: data),
              decoded.accountId == accountId else {
            return nil
        }
        return TimetableOfflineCache(
            accountId: decoded.accountId,
            cachedAtEpochMillis: decoded.cachedAtEpochMillis,
            index: decoded.index,
            weeks: decoded.weeks.filter(\.complete)
        )
    }

    @discardableResult
    func save(
        accountId: String,
        index: TimetableIndex?,
        week: TimetableWeek?,
        nowEpochMillis: Int64
    ) async -> Bool {
        if index == nil && week == nil { return false }
        if let week, !week.complete { return false }
        guard let fileURL = await resolveFileURL() else { return false }

        let existing = await load(accountId: accountId)
        var mergedWeeks = (existing?.weeks ?? [])
            .filter(\.complete)
            .filter { item in
                guard let week else { return true }
                return !(item.week == week.week &&
                    item.term.year == week.term.year &&
                    item.term.term == week.term.term)
            }
        if let week {
            mergedWeeks.append(week)
        }
        let boundedWeeks = Array(
            mergedWeeks
                .sorted { $0.fetchedAtEpochMillis > $1.fetchedAtEpochMillis }
                .prefix(Self.maxCachedWeeks)
        )
        let payload = TimetableOfflineCache(
            accountId: accountId,
            cachedAtEpochMillis: max(1, nowEpochMillis),
            index: index ?? existing?.index,
            weeks: boundedWeeks
        )

        guard let data = try? encoder.encode(payload), data.count <= Self.maxCacheBytes else {
            return false
        }
        do {
            try data.write(to: fileURL, options: .atomic)
            try? await LocalStorageExclusion.shared.excludeFromBackup(url: fileURL)
            return true
        } catch {
            return false
        }
    }

    func clear() async {
        guard let fileURL = await resolveFileURL() else { return }
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func resolveFileURL() async -> URL? {
        if let directoryURL {
            try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: directoryURL)
            return directoryURL.appendingPathComponent(Self.cacheFileName, isDirectory: false)
        }
        guard let base = try? await LocalStorageExclusion.shared.noBackupBaseDirectory() else {
            return nil
        }
        let dir = base.appendingPathComponent("timetable", isDirectory: true)
        try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: dir)
        return dir.appendingPathComponent(Self.cacheFileName, isDirectory: false)
    }
}
