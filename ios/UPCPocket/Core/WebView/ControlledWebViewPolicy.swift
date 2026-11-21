import Foundation

/// Single controlled WKWebView navigation contract for future school captcha / secondary-challenge fallback.
enum ControlledWebViewPolicy: Sendable {
    private static let maxURLLength = 16_384

    static let allowedHTTPSHosts: Set<String> = [
        "cas.upc.edu.cn",
        "app.upc.edu.cn",
        "libspace.upc.edu.cn",
        "mb-xyk.upc.edu.cn",
        "office.chaoxing.com"
    ]

    enum Decision: Equatable, Sendable {
        case allow
        case block
    }

    struct SessionContext: Equatable, Sendable {
        let accountId: String?
        let generation: Int64
        let entryURL: URL

        func isStillValid(for state: SchoolAccountState) -> Bool {
            state.accountId == accountId && state.generation == generation
        }
    }

    static func inspectNavigation(
        rawURL: String,
        method: String = "GET",
        isMainFrame: Bool = true
    ) -> Decision {
        guard !rawURL.isEmpty, rawURL.count <= maxURLLength else {
            return .block
        }
        if rawURL.unicodeScalars.contains(where: { $0.value <= 0x1F || $0.value == 0x7F }) {
            return .block
        }

        guard let components = URLComponents(string: rawURL),
              let scheme = components.scheme?.lowercased(),
              scheme == "https",
              components.user == nil,
              components.password == nil,
              let host = components.host?.lowercased(),
              !host.isEmpty,
              !host.hasSuffix("."),
              allowedHTTPSHosts.contains(host) else {
            return .block
        }

        if let port = components.port, port != 443 {
            return .block
        }

        let normalizedMethod = method.uppercased()
        let path = components.percentEncodedPath

        if host == "cas.upc.edu.cn" {
            let allowedPost = path == "/cas/login" && normalizedMethod == "POST"
            let allowedGet = normalizedMethod == "GET" &&
                (path == "/cas/login" || (!isMainFrame && path.hasPrefix("/cas/")))
            return (allowedPost || allowedGet) ? .allow : .block
        }

        return (normalizedMethod == "GET" || normalizedMethod == "POST") ? .allow : .block
    }
}
