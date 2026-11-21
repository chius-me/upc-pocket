import Foundation

actor CardBalanceStore {
    typealias Requester = @Sendable ([(String, String)]) async throws -> String

    private static let filename = "card-balance-v1.json"
    private static let maxCacheBytes = 16 * 1024

    private let fileURL: URL?
    private let identityProvider: @Sendable () async -> SchoolStudentIdentity?
    private let requester: Requester
    private let clock: @Sendable () -> Int64
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private var businessEpoch: Int64 = 0
    private var currentState = CardBalanceUiState()

    init(
        fileURL: URL? = nil,
        identityProvider: @escaping @Sendable () async -> SchoolStudentIdentity?,
        requester: @escaping Requester = CardBalanceStore.defaultRequest,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.fileURL = fileURL
        self.identityProvider = identityProvider
        self.requester = requester
        self.clock = clock
    }

    func state() -> CardBalanceUiState {
        currentState
    }

    func restore() async -> CardBalanceUiState {
        let epochAtStart = businessEpoch
        guard let identity = await identityProvider(),
              let targetURL = await resolveFileURL() else {
            currentState = CardBalanceUiState()
            return currentState
        }

        let loaded: CardBalanceSnapshot? = if let data = try? Data(contentsOf: targetURL),
                                              data.count <= Self.maxCacheBytes,
                                              let decoded = try? decoder.decode(CardBalanceSnapshot.self, from: data),
                                              decoded.sameOwner(accountId: identity.accountId, studentId: identity.studentId) {
            decoded
        } else {
            nil
        }

        guard await identityProvider() == identity, businessEpoch == epochAtStart else {
            return currentState
        }

        currentState = CardBalanceUiState(
            snapshot: loaded,
            isRefreshing: false,
            errorMessage: nil,
            isHistoricalGeneration: loaded.map { $0.credentialGeneration != identity.generation } ?? false
        )
        return currentState
    }

    func refresh() async -> (Result<CardBalanceSnapshot, Error>, CardBalanceUiState) {
        guard let identity = await identityProvider() else {
            currentState.isRefreshing = false
            currentState.errorMessage = CardBalanceError.invalidStudentId.localizedDescription
            return (.failure(CardBalanceError.invalidStudentId), currentState)
        }

        let epochAtStart = businessEpoch
        currentState.isRefreshing = true
        currentState.errorMessage = nil

        do {
            let formItems = try CardBalanceProtocol.formItems(studentId: identity.studentId)
            let body = try await requester(formItems)
            let snapshot = try CardBalanceProtocol.parse(
                body: body,
                expectedStudentId: identity.studentId,
                accountId: identity.accountId,
                credentialGeneration: identity.generation,
                fetchedAtEpochMillis: max(1, clock())
            )

            guard await identityProvider() == identity, businessEpoch == epochAtStart else {
                currentState.isRefreshing = false
                return (.failure(CardBalanceError.accountChanged), currentState)
            }

            if let targetURL = await resolveFileURL() {
                let data = try encoder.encode(snapshot)
                try data.write(to: targetURL, options: .atomic)
                try? await LocalStorageExclusion.shared.excludeFromBackup(url: targetURL)
            }

            currentState = CardBalanceUiState(
                snapshot: snapshot,
                isRefreshing: false,
                errorMessage: nil,
                isHistoricalGeneration: false
            )
            return (.success(snapshot), currentState)
        } catch {
            currentState.isRefreshing = false
            currentState.errorMessage = error.localizedDescription
            return (.failure(error), currentState)
        }
    }

    func clearCache() async -> CardBalanceUiState {
        businessEpoch += 1
        currentState.snapshot = nil
        currentState.errorMessage = nil
        currentState.isHistoricalGeneration = false
        if let targetURL = await resolveFileURL() {
            try? FileManager.default.removeItem(at: targetURL)
        }
        return currentState
    }

    func clearForAccountChange() async -> CardBalanceUiState {
        businessEpoch += 1
        currentState = CardBalanceUiState()
        if let targetURL = await resolveFileURL() {
            try? FileManager.default.removeItem(at: targetURL)
        }
        return currentState
    }

    private func resolveFileURL() async -> URL? {
        if let fileURL {
            let parent = fileURL.deletingLastPathComponent()
            try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: parent)
            return fileURL
        }
        guard let base = try? await LocalStorageExclusion.shared.noBackupBaseDirectory() else {
            return nil
        }
        return base.appendingPathComponent(Self.filename, isDirectory: false)
    }

    private static func defaultRequest(_ formItems: [(String, String)]) async throws -> String {
        try await StrictHTTPClient.shared.postForm(
            url: CardBalanceProtocol.endpoint,
            formItems: formItems,
            headers: [
                "Accept": "*/*",
                "X-Requested-With": "XMLHttpRequest",
                "User-Agent": "UPCPocket-iOS/0.1",
                "Cache-Control": "no-cache, no-store"
            ],
            maxBytes: CardBalanceProtocol.maxResponseBytes
        )
    }
}
