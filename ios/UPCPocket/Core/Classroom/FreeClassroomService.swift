import Foundation

actor FreeClassroomService {
    typealias Requester = @Sendable ([(String, String)]) async throws -> String

    private let requester: Requester
    private let clock: @Sendable () -> Int64

    init(
        requester: @escaping Requester = FreeClassroomService.defaultRequest,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.requester = requester
        self.clock = clock
    }

    func query(_ query: FreeClassroomQuery) async throws -> FreeClassroomResult {
        let items = try FreeClassroomProtocol.formItems(query: query)
        let body = try await requester(items)
        return try FreeClassroomProtocol.parse(
            body: body,
            query: query,
            fetchedAtEpochMillis: max(1, clock())
        )
    }

    private static func defaultRequest(_ items: [(String, String)]) async throws -> String {
        try await StrictHTTPClient.shared.postForm(
            url: FreeClassroomProtocol.endpoint,
            formItems: items,
            headers: ["Accept": "application/json"],
            timeout: 15,
            maxBytes: FreeClassroomProtocol.maxResponseBytes
        )
    }
}
