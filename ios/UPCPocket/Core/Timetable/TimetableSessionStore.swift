import Foundation

struct TimetableSession: Codable, Equatable, Sendable, CustomStringConvertible {
    let accountId: String
    let credentialGeneration: Int64
    let savedAtEpochMillis: Int64
    let cookies: [String: String]

    var description: String {
        "TimetableSession(accountId: \(accountId), generation: \(credentialGeneration), cookieCount: \(cookies.count))"
    }
}

/// Encrypted local storage for verified `app.upc.edu.cn` timetable cookies via Keychain (`ThisDeviceOnly`).
actor TimetableSessionStore {
    private static let accountKey = "timetable_session_v1"
    private static let maxCookies = 64

    private let backingStore: any CredentialBackingStore
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(backingStore: any CredentialBackingStore = KeychainCredentialStore()) {
        self.backingStore = backingStore
    }

    func load(accountId: String, credentialGeneration: Int64) async -> TimetableSession? {
        guard let data = try? await backingStore.load(accountKey: Self.accountKey),
              let decoded = try? decoder.decode(TimetableSession.self, from: data),
              decoded.accountId == accountId,
              decoded.credentialGeneration == credentialGeneration,
              !decoded.cookies.isEmpty,
              decoded.cookies.count <= Self.maxCookies else {
            return nil
        }
        return decoded
    }

    @discardableResult
    func save(_ session: TimetableSession) async -> Bool {
        guard !session.cookies.isEmpty,
              session.cookies.count <= Self.maxCookies,
              let data = try? encoder.encode(session) else {
            return false
        }
        do {
            try await backingStore.save(accountKey: Self.accountKey, data: data)
            return true
        } catch {
            return false
        }
    }

    func clear() async {
        try? await backingStore.delete(accountKey: Self.accountKey)
    }
}
