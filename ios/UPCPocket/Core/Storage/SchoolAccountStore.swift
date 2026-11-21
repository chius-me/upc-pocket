import CryptoKit
import Foundation

actor SchoolAccountStore {
    private struct CredentialPayload: Codable, Sendable {
        let version: Int
        let generation: Int64
        let savedAtEpochMillis: Int64
        let loginName: String
        let password: String
    }

    private struct PersistedMetadata: Codable, Sendable {
        let status: AccountStatus
        let generation: Int64
        let accountId: String?
        let maskedLoginName: String?
        let isValidStudentId: Bool
        let savedAtEpochMillis: Int64?
    }

    private static let credentialAccountKey = "primary_school_account_v1"
    private static let metadataFilename = "school_account_metadata.json"

    private let directoryURL: URL?
    private let credentialStore: any CredentialBackingStore
    private let clock: @Sendable () -> Int64
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private var currentState = SchoolAccountState()
    private var currentIdentity: SchoolStudentIdentity?
    private var initialized = false

    func currentStudentIdentitySnapshot() async -> SchoolStudentIdentity? {
        if !initialized {
            _ = await restore()
        }
        return currentIdentity
    }

    func currentAccountStateSnapshot() async -> SchoolAccountState {
        if !initialized {
            _ = await restore()
        }
        return currentState
    }

    func currentLoginCredentialSnapshot() async -> SchoolLoginCredential? {
        if !initialized {
            _ = await restore()
        }
        guard currentState.status == .savedUnverified,
              let expectedAccountId = currentState.accountId,
              let credentialData = try? await credentialStore.load(accountKey: Self.credentialAccountKey),
              let payload = try? decoder.decode(CredentialPayload.self, from: credentialData),
              payload.version == 1,
              payload.generation == currentState.generation,
              !payload.loginName.isEmpty,
              !payload.password.isEmpty,
              Self.stableAccountId(for: payload.loginName) == expectedAccountId else {
            return nil
        }
        return SchoolLoginCredential(
            accountId: expectedAccountId,
            generation: payload.generation,
            loginName: payload.loginName,
            password: payload.password
        )
    }

    func matches(accountId: String, generation: Int64) -> Bool {
        currentState.status == .savedUnverified &&
            currentState.accountId == accountId &&
            currentState.generation == generation
    }

    init(
        directoryURL: URL? = nil,
        credentialStore: any CredentialBackingStore = KeychainCredentialStore(),
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.directoryURL = directoryURL
        self.credentialStore = credentialStore
        self.clock = clock
    }

    func restore() async -> SchoolAccountState {
        initialized = true
        guard let metadataURL = await resolveMetadataURL() else {
            currentIdentity = nil
            currentState = SchoolAccountState(status: .unset, generation: 0)
            return currentState
        }

        let fileExists = FileManager.default.fileExists(atPath: metadataURL.path)
        let credentialData = try? await credentialStore.load(accountKey: Self.credentialAccountKey)

        if !fileExists {
            if credentialData != nil {
                await enterRecovery(baseGeneration: 0, metadataURL: metadataURL)
            } else {
                currentIdentity = nil
                currentState = SchoolAccountState(status: .unset, generation: 0)
            }
            return currentState
        }

        guard let metadataBytes = try? Data(contentsOf: metadataURL),
              let metadata = try? decoder.decode(PersistedMetadata.self, from: metadataBytes) else {
            await enterRecovery(baseGeneration: currentState.generation, metadataURL: metadataURL)
            return currentState
        }

        switch metadata.status {
        case .unset:
            if credentialData != nil {
                try? await credentialStore.delete(accountKey: Self.credentialAccountKey)
            }
            currentIdentity = nil
            currentState = SchoolAccountState(status: .unset, generation: metadata.generation)
            return currentState

        case .recoveryRequired:
            if credentialData != nil {
                try? await credentialStore.delete(accountKey: Self.credentialAccountKey)
            }
            currentIdentity = nil
            currentState = SchoolAccountState(
                status: .recoveryRequired,
                generation: metadata.generation
            )
            return currentState

        case .savedUnverified:
            guard let credentialData,
                  let payload = try? decoder.decode(CredentialPayload.self, from: credentialData),
                  payload.version == 1,
                  payload.generation == metadata.generation,
                  !payload.loginName.isEmpty,
                  !payload.password.isEmpty else {
                await enterRecovery(baseGeneration: metadata.generation, metadataURL: metadataURL)
                return currentState
            }
            let computedAccountId = Self.stableAccountId(for: payload.loginName)
            guard computedAccountId == metadata.accountId else {
                await enterRecovery(baseGeneration: metadata.generation, metadataURL: metadataURL)
                return currentState
            }
            let validStudentId = StudentIdValidator.isValid(payload.loginName)
            currentIdentity = validStudentId ? SchoolStudentIdentity(
                accountId: computedAccountId,
                studentId: payload.loginName,
                generation: payload.generation
            ) : nil
            currentState = SchoolAccountState(
                status: .savedUnverified,
                generation: payload.generation,
                accountId: computedAccountId,
                maskedLoginName: maskLoginName(payload.loginName),
                isValidStudentId: validStudentId,
                savedAtEpochMillis: payload.savedAtEpochMillis
            )
            return currentState
        }
    }

    func save(rawLoginName: String, password: String) async -> (AccountWriteResult, SchoolAccountState) {
        if !initialized {
            _ = await restore()
        }

        let loginName = rawLoginName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !loginName.isEmpty,
              loginName.count <= 128,
              !loginName.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            return (.failure(.invalidLoginName), currentState)
        }

        guard !password.isEmpty,
              password.count <= 512,
              !password.utf8.contains(0) else {
            return (.failure(.invalidPassword), currentState)
        }

        guard let metadataURL = await resolveMetadataURL() else {
            return (.failure(.storageFailed), currentState)
        }

        let nextGeneration = currentState.generation + 1
        let savedAt = max(1, clock())
        let accountId = Self.stableAccountId(for: loginName)
        let masked = maskLoginName(loginName)
        let validStudentId = StudentIdValidator.isValid(loginName)

        let payload = CredentialPayload(
            version: 1,
            generation: nextGeneration,
            savedAtEpochMillis: savedAt,
            loginName: loginName,
            password: password
        )

        guard let payloadData = try? encoder.encode(payload) else {
            return (.failure(.storageFailed), currentState)
        }

        do {
            try await credentialStore.save(accountKey: Self.credentialAccountKey, data: payloadData)
        } catch {
            return (.failure(.keychainFailed), currentState)
        }

        let metadata = PersistedMetadata(
            status: .savedUnverified,
            generation: nextGeneration,
            accountId: accountId,
            maskedLoginName: masked,
            isValidStudentId: validStudentId,
            savedAtEpochMillis: savedAt
        )

        do {
            let metadataData = try encoder.encode(metadata)
            try metadataData.write(to: metadataURL, options: .atomic)
            try? await LocalStorageExclusion.shared.excludeFromBackup(url: metadataURL)
        } catch {
            return (.failure(.storageFailed), currentState)
        }

        currentIdentity = validStudentId ? SchoolStudentIdentity(
            accountId: accountId,
            studentId: loginName,
            generation: nextGeneration
        ) : nil
        currentState = SchoolAccountState(
            status: .savedUnverified,
            generation: nextGeneration,
            accountId: accountId,
            maskedLoginName: masked,
            isValidStudentId: validStudentId,
            savedAtEpochMillis: savedAt
        )
        return (.success, currentState)
    }

    func clear() async -> (Bool, SchoolAccountState) {
        if !initialized {
            _ = await restore()
        }

        guard let metadataURL = await resolveMetadataURL() else {
            return (false, currentState)
        }

        let nextGeneration: Int64 = if currentState.status == .unset && currentState.accountId == nil {
            currentState.generation
        } else {
            currentState.generation + 1
        }

        do {
            try await credentialStore.delete(accountKey: Self.credentialAccountKey)
            let metadata = PersistedMetadata(
                status: .unset,
                generation: nextGeneration,
                accountId: nil,
                maskedLoginName: nil,
                isValidStudentId: false,
                savedAtEpochMillis: nil
            )
            let data = try encoder.encode(metadata)
            try data.write(to: metadataURL, options: .atomic)
            try? await LocalStorageExclusion.shared.excludeFromBackup(url: metadataURL)
            currentIdentity = nil
            currentState = SchoolAccountState(status: .unset, generation: nextGeneration)
            return (true, currentState)
        } catch {
            return (false, currentState)
        }
    }

    private func enterRecovery(baseGeneration: Int64, metadataURL: URL) async {
        let nextGeneration = max(0, baseGeneration) + 1
        try? await credentialStore.delete(accountKey: Self.credentialAccountKey)
        let metadata = PersistedMetadata(
            status: .recoveryRequired,
            generation: nextGeneration,
            accountId: nil,
            maskedLoginName: nil,
            isValidStudentId: false,
            savedAtEpochMillis: nil
        )
        if let data = try? encoder.encode(metadata) {
            try? data.write(to: metadataURL, options: .atomic)
            try? await LocalStorageExclusion.shared.excludeFromBackup(url: metadataURL)
        }
        currentIdentity = nil
        currentState = SchoolAccountState(status: .recoveryRequired, generation: nextGeneration)
    }

    private func resolveMetadataURL() async -> URL? {
        if let directoryURL {
            try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: directoryURL)
            return directoryURL.appendingPathComponent(Self.metadataFilename, isDirectory: false)
        }
        guard let base = try? await LocalStorageExclusion.shared.noBackupBaseDirectory() else {
            return nil
        }
        let dir = base.appendingPathComponent("school-account", isDirectory: true)
        try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: dir)
        return dir.appendingPathComponent(Self.metadataFilename, isDirectory: false)
    }

    private static func stableAccountId(for loginName: String) -> String {
        let digest = SHA256.hash(data: Data(loginName.utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}
