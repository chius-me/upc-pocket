import Foundation

/// Ensures private storage directories exist and are excluded from iCloud / device backups.
actor LocalStorageExclusion {
    static let shared = LocalStorageExclusion()

    private let fileManager = FileManager.default

    func noBackupBaseDirectory() throws -> URL {
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = appSupport.appendingPathComponent("UPCPocketNoBackup", isDirectory: true)
        try ensureExcludedDirectory(at: dir)
        return dir
    }

    func applyDefaultExclusions() async {
        _ = try? noBackupBaseDirectory()
        if let docs = try? fileManager.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) {
            try? excludeFromBackup(url: docs)
        }
    }

    func ensureExcludedDirectory(at url: URL) throws {
        if !fileManager.fileExists(atPath: url.path) {
            try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        }
        try excludeFromBackup(url: url)
    }

    func excludeFromBackup(url: URL) throws {
        var mutableURL = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try mutableURL.setResourceValues(values)
    }
}
