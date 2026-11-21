import Foundation

/// Scoped in-memory cookie jar restricted to allowed UPC hosts.
/// CAS cookies remain ephemeral in RAM; only verified `app.upc.edu.cn` cookies are exported.
actor UpcCasCookieJar {
    private struct StoredCookie: Sendable {
        let name: String
        let value: String
        let domain: String
        let path: String
        let secure: Boolean
        let expiresAtEpochSeconds: TimeInterval?

        typealias Boolean = Bool

        func isExpired(at now: TimeInterval) -> Bool {
            if let expiresAtEpochSeconds {
                return expiresAtEpochSeconds <= now
            }
            return false
        }

        func matches(
            host: String,
            requestPath: String,
            isHTTPS: Bool,
            now: TimeInterval,
            allowedHosts: Set<String>
        ) -> Bool {
            if isExpired(at: now) { return false }
            let domainMatches = domain == host ||
                (host.hasSuffix(".\(domain)") && allowedHosts.contains(domain) && allowedHosts.contains(host))
            if !domainMatches { return false }
            if secure && !isHTTPS { return false }
            if path == "/" { return true }
            if requestPath == path { return true }
            guard requestPath.hasPrefix(path) else { return false }
            if path.hasSuffix("/") { return true }
            let idx = requestPath.index(requestPath.startIndex, offsetBy: path.count)
            return requestPath[idx] == "/"
        }
    }

    private let allowedHosts: Set<String>
    private let clock: @Sendable () -> TimeInterval
    private var entries: [StoredCookie] = []

    init(
        allowedHosts: Set<String> = ["cas.upc.edu.cn", "app.upc.edu.cn"],
        clock: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 }
    ) {
        self.allowedHosts = allowedHosts
        self.clock = clock
    }

    func saveFromHeaders(requestURL: URL, setCookieHeaders: [String]) {
        guard let requestHost = requestURL.host?.lowercased().trimTrailingDots(),
              allowedHosts.contains(requestHost) else {
            return
        }
        let defaultPath = defaultCookiePath(for: requestURL.path)
        let now = clock()

        for header in setCookieHeaders {
            guard let parsed = parseSetCookie(
                rawHeader: header,
                requestHost: requestHost,
                defaultPath: defaultPath,
                now: now
            ),
                  allowedHosts.contains(parsed.domain) else {
                continue
            }
            entries.removeAll {
                $0.name == parsed.name && $0.domain == parsed.domain && $0.path == parsed.path
            }
            if !parsed.isExpired(at: now), entries.count < 64 {
                entries.append(parsed)
            }
        }
    }

    func cookieHeader(for requestURL: URL) -> String? {
        guard let host = requestURL.host?.lowercased().trimTrailingDots(),
              allowedHosts.contains(host) else {
            return nil
        }
        let path = requestURL.path.isEmpty ? "/" : requestURL.path
        let isHTTPS = requestURL.scheme?.lowercased() == "https"
        let now = clock()
        entries.removeAll { $0.isExpired(at: now) }

        let matched = entries
            .filter {
                $0.matches(host: host, requestPath: path, isHTTPS: isHTTPS, now: now, allowedHosts: allowedHosts)
            }
            .sorted { lhs, rhs in
                (lhs.domain == host ? 0 : 1) < (rhs.domain == host ? 0 : 1)
            }
        guard !matched.isEmpty else { return nil }
        return matched.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    func cookiesForHost(_ host: String) -> [String: String] {
        let normalized = host.lowercased().trimTrailingDots()
        let now = clock()
        entries.removeAll { $0.isExpired(at: now) }
        var result: [String: String] = [:]
        for entry in entries where normalized.hasSuffix(".\(entry.domain)") &&
            allowedHosts.contains(entry.domain) &&
            allowedHosts.contains(normalized) {
            result[entry.name] = entry.value
        }
        for entry in entries where entry.domain == normalized {
            result[entry.name] = entry.value
        }
        return result
    }

    func clear() {
        entries.removeAll()
    }

    private func parseSetCookie(
        rawHeader: String,
        requestHost: String,
        defaultPath: String,
        now: TimeInterval
    ) -> StoredCookie? {
        let parts = rawHeader.split(separator: ";", omittingEmptySubsequences: false).map {
            String($0).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let first = parts.first,
              let eqIdx = first.firstIndex(of: "="),
              eqIdx > first.startIndex else {
            return nil
        }
        let name = String(first[..<eqIdx]).trimmingCharacters(in: .whitespacesAndNewlines)
        let value = String(first[first.index(after: eqIdx)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidCookieName(name), value.count <= 4096 else {
            return nil
        }

        var domain = requestHost
        var path = defaultPath
        var secure = false
        var hasMaxAge = false
        var maxAgeExpires: TimeInterval?
        var expiresAt: TimeInterval?

        for attr in parts.dropFirst() where !attr.isEmpty {
            let attrEq = attr.firstIndex(of: "=")
            let attrName = String(attrEq.map { attr[..<$0] } ?? attr[...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            let attrVal = attrEq.map {
                String(attr[attr.index(after: $0)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            } ?? ""

            switch attrName {
            case "domain":
                let trimmed = attrVal.lowercased()
                    .trimLeadingDots()
                    .trimTrailingDots()
                if !trimmed.isEmpty {
                    domain = trimmed
                }
            case "path":
                if attrVal.hasPrefix("/") {
                    path = attrVal
                }
            case "secure":
                secure = true
            case "max-age":
                if let seconds = Double(attrVal) {
                    hasMaxAge = true
                    maxAgeExpires = seconds <= 0 ? -.infinity : now + seconds
                }
            case "expires":
                if let parsedDate = Self.parseHTTPDate(attrVal) {
                    expiresAt = parsedDate.timeIntervalSince1970
                }
            default:
                break
            }
        }

        let effectiveExpires = hasMaxAge ? maxAgeExpires : expiresAt
        return StoredCookie(
            name: name,
            value: value,
            domain: domain,
            path: path,
            secure: secure,
            expiresAtEpochSeconds: effectiveExpires
        )
    }

    private func defaultCookiePath(for rawPath: String) -> String {
        guard rawPath.hasPrefix("/"), let lastSlash = rawPath.lastIndex(of: "/"),
              lastSlash > rawPath.startIndex else {
            return "/"
        }
        return String(rawPath[..<lastSlash])
    }

    private static func isValidCookieName(_ name: String) -> Bool {
        guard !name.isEmpty, name.count <= 256 else { return false }
        let allowed = CharacterSet(
            charactersIn: "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        )
        return name.unicodeScalars.allSatisfy { allowed.contains($0) }
    }

    private static func parseHTTPDate(_ raw: String) -> Date? {
        let formats = [
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z"
        ]
        for format in formats {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(secondsFromGMT: 0)
            formatter.dateFormat = format
            if let date = formatter.date(from: raw) {
                return date
            }
        }
        let iso = ISO8601DateFormatter()
        return iso.date(from: raw)
    }
}

private extension String {
    func trimLeadingDots() -> String {
        var s = self
        while s.hasPrefix(".") { s.removeFirst() }
        return s
    }

    func trimTrailingDots() -> String {
        var s = self
        while s.hasSuffix(".") { s.removeLast() }
        return s
    }
}
