import Foundation

/// Single controlled WKWebView navigation contract for school captcha / secondary-challenge fallback.
enum ControlledWebViewPolicy: Sendable {
    private static let maxURLLength = 16_384

    static let allowedHTTPSHosts: Set<String> = [
        "cas.upc.edu.cn",
        "app.upc.edu.cn",
        "libspace.upc.edu.cn",
        "mb-xyk.upc.edu.cn",
        "office.chaoxing.com"
    ]

    static let timetableScopeHosts: Set<String> = [
        "cas.upc.edu.cn",
        "app.upc.edu.cn"
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

    static func inspectEntryURL(
        rawURL: String,
        scopeHosts: Set<String> = allowedHTTPSHosts
    ) -> Decision {
        if rawURL.caseInsensitiveCompare("about:blank") == .orderedSame {
            return .block
        }
        return inspectNavigation(
            rawURL: rawURL,
            method: "GET",
            isMainFrame: true,
            scopeHosts: scopeHosts
        )
    }

    static func inspectNavigation(
        rawURL: String,
        method: String = "GET",
        isMainFrame: Bool = true,
        scopeHosts: Set<String> = allowedHTTPSHosts
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
              allowedHTTPSHosts.contains(host),
              scopeHosts.contains(host) else {
            return .block
        }

        if let port = components.port, port != 443 {
            return .block
        }

        let path = components.percentEncodedPath
        if path.contains("..") || path.contains("\\") {
            return .block
        }

        let normalizedMethod = method.uppercased()
        guard normalizedMethod == "GET" || normalizedMethod == "POST" else {
            return .block
        }

        if host == "cas.upc.edu.cn" {
            return path.hasPrefix("/cas/") ? .allow : .block
        }

        return .allow
    }
}
