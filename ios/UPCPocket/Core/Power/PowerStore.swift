import Foundation

struct SavedPowerReading: Codable, Equatable, Sendable {
    let building: String
    let room: String
    let systemNo: String
    let kwhText: String
    let fetchedAtEpochMillis: Int64
}

struct PowerUiState: Equatable, Sendable {
    var building: String = ""
    var room: String = ""
    var buildings: [String] = []
    var lastReading: SavedPowerReading? = nil
    var candidates: [ElectricityCandidate] = []
    var isLoadingBuildings: Bool = false
    var isQuerying: Bool = false
    var errorMessage: String? = nil
}

actor PowerStore {
    typealias FormFetcher = @Sendable () async throws -> String
    typealias QueryPoster = @Sendable ([(String, String)]) async throws -> String

    private struct PersistedState: Codable, Sendable {
        let draftBuilding: String
        let draftRoom: String
        let buildings: [String]
        let lastReading: SavedPowerReading?
    }

    private static let filename = "power-tangdaowan-v1.json"
    private static let maxCacheBytes = 64 * 1024

    private let fileURL: URL?
    private let fetchFormPage: FormFetcher
    private let postQueryForm: QueryPoster
    private let clock: @Sendable () -> Int64
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private var currentState = PowerUiState()

    init(
        fileURL: URL? = nil,
        fetchFormPage: @escaping FormFetcher = PowerStore.defaultFetchForm,
        postQueryForm: @escaping QueryPoster = PowerStore.defaultPostQuery,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.fileURL = fileURL
        self.fetchFormPage = fetchFormPage
        self.postQueryForm = postQueryForm
        self.clock = clock
    }

    func restore() async -> PowerUiState {
        guard let targetURL = await resolveFileURL(),
              let data = try? Data(contentsOf: targetURL),
              data.count <= Self.maxCacheBytes,
              let persisted = try? decoder.decode(PersistedState.self, from: data) else {
            return currentState
        }
        currentState.building = persisted.draftBuilding
        currentState.room = persisted.draftRoom
        currentState.buildings = persisted.buildings
        currentState.lastReading = persisted.lastReading
        return currentState
    }

    func loadBuildings() async -> (Result<[String], Error>, PowerUiState) {
        currentState.isLoadingBuildings = true
        currentState.errorMessage = nil

        do {
            let html = try await fetchFormPage()
            let form = try ElectricityProtocol.parseForm(html: html)
            currentState.buildings = form.buildings
            if currentState.building.isEmpty || !form.buildings.contains(currentState.building) {
                if currentState.building.isEmpty, let first = form.buildings.first {
                    currentState.building = first
                }
            }
            currentState.isLoadingBuildings = false
            await persist()
            return (.success(form.buildings), currentState)
        } catch {
            currentState.isLoadingBuildings = false
            currentState.errorMessage = error.localizedDescription
            return (.failure(error), currentState)
        }
    }

    func query(buildingInput: String, roomInput: String) async -> (Result<ElectricityOutcome, Error>, PowerUiState) {
        let building = buildingInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let room = roomInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !building.isEmpty && !room.isEmpty else {
            let error = ElectricityProtocolError.formatChanged("请先选择楼号并输入房间号")
            currentState.errorMessage = error.localizedDescription
            return (.failure(error), currentState)
        }

        currentState.building = building
        currentState.room = room
        currentState.isQuerying = true
        currentState.errorMessage = nil
        currentState.candidates = []
        await persist()

        do {
            let pageHtml = try await fetchFormPage()
            let form = try ElectricityProtocol.parseForm(html: pageHtml)
            guard let targetBuilding = form.buildings.first(where: { $0 == building }) else {
                throw ElectricityProtocolError.formatChanged("学校页面没有找到楼号“\(building)”，请刷新楼号列表后重试")
            }

            let formItems = ElectricityProtocol.formItems(form: form, building: targetBuilding, room: room)
            let responseHtml = try await postQueryForm(formItems)
            let outcome = try ElectricityProtocol.parseResult(html: responseHtml, requestedRoom: room)

            currentState.buildings = form.buildings
            switch outcome {
            case let .success(reading):
                let resolvedRoom = reading.room.isEmpty ? room : reading.room
                currentState.building = targetBuilding
                currentState.room = resolvedRoom
                currentState.lastReading = SavedPowerReading(
                    building: targetBuilding,
                    room: resolvedRoom,
                    systemNo: reading.systemNo,
                    kwhText: reading.kwhText,
                    fetchedAtEpochMillis: max(1, clock())
                )
                currentState.candidates = []
            case let .ambiguous(candidates):
                currentState.candidates = candidates
            }
            currentState.isQuerying = false
            await persist()
            return (.success(outcome), currentState)
        } catch {
            currentState.isQuerying = false
            currentState.errorMessage = error.localizedDescription
            return (.failure(error), currentState)
        }
    }

    func clearSaved() async -> PowerUiState {
        let preservedBuildings = currentState.buildings
        currentState = PowerUiState(buildings: preservedBuildings)
        if let targetURL = await resolveFileURL() {
            try? FileManager.default.removeItem(at: targetURL)
        }
        return currentState
    }

    private func persist() async {
        guard let targetURL = await resolveFileURL() else { return }
        let payload = PersistedState(
            draftBuilding: currentState.building,
            draftRoom: currentState.room,
            buildings: currentState.buildings,
            lastReading: currentState.lastReading
        )
        guard let data = try? encoder.encode(payload) else { return }
        try? data.write(to: targetURL, options: .atomic)
        try? await LocalStorageExclusion.shared.excludeFromBackup(url: targetURL)
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

    private static func defaultFetchForm() async throws -> String {
        try await StrictHTTPClient.shared.get(
            url: ElectricityProtocol.pageURL,
            headers: [
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "zh-CN,zh;q=0.9"
            ],
            maxBytes: ElectricityProtocol.maxResponseBytes
        )
    }

    private static func defaultPostQuery(_ items: [(String, String)]) async throws -> String {
        try await StrictHTTPClient.shared.postForm(
            url: ElectricityProtocol.postURL,
            formItems: items,
            headers: [
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "zh-CN,zh;q=0.9"
            ],
            maxBytes: ElectricityProtocol.maxResponseBytes
        )
    }
}
