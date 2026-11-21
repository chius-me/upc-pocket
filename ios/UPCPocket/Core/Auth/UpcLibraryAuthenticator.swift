import Foundation

enum UpcLibraryAuthEndpoints: Sendable {
    static let casHost = "cas.upc.edu.cn"
    static let libraryHost = "libspace.upc.edu.cn"
    static let entryURLString =
        "https://libspace.upc.edu.cn/remote/static/sso/login" +
            "?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Flibseat%2F%23%2Flogin"
    static let pageURLString = "https://libspace.upc.edu.cn/libseat/"
    static let service =
        "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
    static let ssoAuthURLString = "https://libspace.upc.edu.cn/rest/ssoAuth"
    static let userURLString = "https://libspace.upc.edu.cn/rest/v2/user"
    static let callbackPath = "/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
    static let maxRedirectHops = 8

    static let entryURL = URL(string: entryURLString)!
    static let pageURL = URL(string: pageURLString)!
}

struct UpcLibraryUser: Equatable, Sendable {
    let id: String
    let username: String
    let name: String
}

enum UpcLibraryAuthFailure: Equatable, Sendable {
    case invalidCredentials
    case interactionRequired
    case networkUnavailable
    case tlsError
    case upstreamError
    case protocolError
    case accessDenied
    case identityMismatch
}

enum UpcLibraryAuthResult: Sendable, CustomStringConvertible {
    case success(seatToken: String, user: UpcLibraryUser)
    case failure(reason: UpcLibraryAuthFailure, interactionURL: URL? = nil, message: String? = nil)

    var description: String {
        switch self {
        case .success:
            "UpcLibraryAuthResult.success(seatToken=<redacted>)"
        case let .failure(reason, _, _):
            "UpcLibraryAuthResult.failure(\(reason))"
        }
    }
}

struct UpcLibraryAuthenticator: Sendable {
    private let transport: CasHTTPTransport
    private let casService: UpcCasService

    init(transport: @escaping CasHTTPTransport = UpcCasLogin.defaultTransport) {
        self.transport = transport
        self.casService = UpcCasService(
            casHost: UpcLibraryAuthEndpoints.casHost,
            serviceURL: UpcLibraryAuthEndpoints.service,
            callbackHost: UpcLibraryAuthEndpoints.libraryHost,
            callbackPath: UpcLibraryAuthEndpoints.callbackPath,
            callbackParameterNames: ["ticket"],
            maxRedirectHops: UpcLibraryAuthEndpoints.maxRedirectHops
        )
    }

    func authenticate(credential: SchoolLoginCredential) async -> UpcLibraryAuthResult {
        let jar = UpcCasCookieJar(allowedHosts: [
            UpcLibraryAuthEndpoints.casHost,
            UpcLibraryAuthEndpoints.libraryHost
        ])
        defer {
            Task { await jar.clear() }
        }
        let result = await performLogin(credential: credential, jar: jar)
        await jar.clear()
        return result
    }

    private func performLogin(
        credential: SchoolLoginCredential,
        jar: UpcCasCookieJar
    ) async -> UpcLibraryAuthResult {
        let entry: RawHTTPResponse
        do {
            entry = try await executeWithJar(
                jar: jar,
                request: CasHTTPRequest(url: UpcLibraryAuthEndpoints.entryURL, method: "GET")
            )
        } catch let failure as LibraryStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        if [401, 403, 429].contains(entry.statusCode) {
            return .failure(reason: .accessDenied)
        }
        guard [301, 302, 303, 307, 308].contains(entry.statusCode),
              let location = entry.location,
              let casURL = URL(string: location, relativeTo: UpcLibraryAuthEndpoints.entryURL)?.absoluteURL,
              casService.isCasLoginURL(casURL) else {
            return .failure(reason: .protocolError)
        }

        let callbackURL: URL
        switch await UpcCasLogin(service: casService, transport: transport).authenticateToCallback(
            credential: credential,
            casURL: casURL,
            jar: jar
        ) {
        case let .callback(url):
            callbackURL = url
        case .invalidCredentials:
            return .failure(reason: .invalidCredentials)
        case let .interactionRequired(url, reason):
            return .failure(reason: .interactionRequired, interactionURL: url, message: reason)
        case let .networkError(message):
            return .failure(reason: .networkUnavailable, message: message)
        case let .upstreamError(message):
            return .failure(reason: .upstreamError, message: message)
        }

        let appToken: String
        do {
            appToken = try await followToAppToken(jar: jar, startURL: callbackURL)
        } catch let failure as LibraryStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .protocolError)
        }

        guard let encodedAppToken = Self.urlQueryEncode(appToken),
              let exchangeURL = URL(string: "\(UpcLibraryAuthEndpoints.ssoAuthURLString)?token=\(encodedAppToken)"),
              var exchangeHeaders = try? UpcLibrarySigning.signedHeaders(token: appToken, method: "POST") else {
            return .failure(reason: .protocolError)
        }
        exchangeHeaders["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"

        let exchange: RawHTTPResponse
        do {
            exchange = try await executeWithJar(
                jar: jar,
                request: CasHTTPRequest(
                    url: exchangeURL,
                    method: "POST",
                    headers: exchangeHeaders,
                    formItems: [("token", appToken)]
                )
            )
        } catch let failure as LibraryStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        if [401, 403, 429].contains(exchange.statusCode) {
            return .failure(reason: .accessDenied)
        }
        guard exchange.statusCode == 200 else {
            return .failure(reason: .upstreamError)
        }

        let seatToken: String
        switch Self.parseLibraryToken(exchange.body) {
        case let .success(token):
            seatToken = token
        case let .failure(reason):
            return .failure(reason: reason)
        }

        guard let encodedSeatToken = Self.urlQueryEncode(seatToken),
              let userURL = URL(string: "\(UpcLibraryAuthEndpoints.userURLString)?token=\(encodedSeatToken)"),
              let userHeaders = try? UpcLibrarySigning.signedHeaders(token: seatToken, method: "GET") else {
            return .failure(reason: .protocolError)
        }

        let userResponse: RawHTTPResponse
        do {
            userResponse = try await executeWithJar(
                jar: jar,
                request: CasHTTPRequest(url: userURL, method: "GET", headers: userHeaders)
            )
        } catch let failure as LibraryStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        if [401, 403, 429].contains(userResponse.statusCode) {
            return .failure(reason: .accessDenied)
        }
        guard userResponse.statusCode == 200 else {
            return .failure(reason: .upstreamError)
        }

        let user: UpcLibraryUser
        switch Self.parseLibraryUser(userResponse.body) {
        case let .success(parsed):
            user = parsed
        case let .failure(reason):
            return .failure(reason: reason)
        }

        let returnedName = user.username.trimmingCharacters(in: .whitespacesAndNewlines)
        let expectedName = credential.loginName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard returnedName == expectedName else {
            return .failure(reason: .identityMismatch)
        }
        return .success(seatToken: seatToken, user: user)
    }

    private func followToAppToken(jar: UpcCasCookieJar, startURL: URL) async throws -> String {
        var current = startURL
        for _ in 0..<UpcLibraryAuthEndpoints.maxRedirectHops {
            if let token = Self.tokenFromURL(current.absoluteString) {
                return token
            }
            guard Self.isAllowedLibraryPage(current) else {
                throw LibraryStepFailure(reason: .protocolError, message: nil)
            }
            let response = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: current, method: "GET"))
            if (300...399).contains(response.statusCode) {
                guard let location = response.location,
                      let target = URL(string: location, relativeTo: current)?.absoluteURL else {
                    throw LibraryStepFailure(reason: .protocolError, message: nil)
                }
                if let token = Self.tokenFromURL(target.absoluteString) {
                    return token
                }
                current = target
                continue
            }
            if [401, 403, 429].contains(response.statusCode) {
                throw LibraryStepFailure(reason: .accessDenied, message: nil)
            }
            guard response.statusCode == 200 else {
                throw LibraryStepFailure(reason: .upstreamError, message: nil)
            }
            if let token = Self.tokenFromURL(current.absoluteString) {
                return token
            }
            throw LibraryStepFailure(reason: .protocolError, message: nil)
        }
        throw LibraryStepFailure(reason: .protocolError, message: nil)
    }

    private func executeWithJar(
        jar: UpcCasCookieJar,
        request: CasHTTPRequest
    ) async throws -> RawHTTPResponse {
        let cookieHeader = await jar.cookieHeader(for: request.url)
        var mergedHeaders = request.headers.filter { $0.key.caseInsensitiveCompare("Cookie") != .orderedSame }
        if let cookieHeader {
            mergedHeaders["Cookie"] = cookieHeader
        }
        let updated = CasHTTPRequest(
            url: request.url,
            method: request.method,
            headers: mergedHeaders,
            formItems: request.formItems
        )
        do {
            let response = try await transport(updated)
            await jar.saveFromHeaders(requestURL: request.url, setCookieHeaders: response.setCookieHeaders)
            return response
        } catch let error as StrictHTTPError {
            switch error {
            case let .transport(msg):
                throw LibraryStepFailure(reason: .networkUnavailable, message: msg)
            case .redirectBlocked, .httpStatus, .responseTooLarge, .invalidEncoding:
                throw LibraryStepFailure(reason: .upstreamError, message: error.localizedDescription)
            }
        }
    }

    static func isAllowedLibraryPage(_ url: URL) -> Bool {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: true),
              comps.scheme?.lowercased() == "https",
              comps.host?.lowercased() == UpcLibraryAuthEndpoints.libraryHost,
              comps.port == nil || comps.port == 443,
              comps.user == nil,
              comps.password == nil else {
            return false
        }
        let path = comps.percentEncodedPath
        return path == UpcLibraryAuthEndpoints.callbackPath ||
            path == "/libseat" ||
            path.hasPrefix("/libseat/")
    }

    static func tokenFromURL(_ raw: String) -> String? {
        guard let comps = URLComponents(string: raw) else { return nil }
        var candidates: [String] = []
        if let query = comps.percentEncodedQuery, let found = extractTokenParam(query) {
            candidates.append(found)
        }
        if let fragment = comps.percentEncodedFragment, !fragment.isEmpty {
            let fragmentQuery = fragment.split(separator: "?", maxSplits: 1).count == 2
                ? String(fragment.split(separator: "?", maxSplits: 1)[1])
                : fragment
            if let found = extractTokenParam(fragmentQuery) {
                candidates.append(found)
            }
        }
        return candidates.first { !$0.isEmpty && $0.count <= 4096 }
    }

    private static func extractTokenParam(_ rawQuery: String) -> String? {
        for pair in rawQuery.split(separator: "&") {
            let str = String(pair)
            guard str.hasPrefix("token=") else { continue }
            let encodedVal = String(str.dropFirst("token=".count)).replacingOccurrences(of: "+", with: " ")
            if let decoded = encodedVal.removingPercentEncoding, !decoded.isEmpty {
                return decoded
            }
        }
        return nil
    }

    private static func parseLibraryToken(_ body: String) -> Result<String, FailureBox> {
        switch parseRoot(body) {
        case let .failure(box):
            return .failure(box)
        case let .success(root):
            guard let data = root["data"] as? [String: Any],
                  let token = data["token"] as? String,
                  !token.isEmpty else {
                return .failure(FailureBox(.protocolError))
            }
            return .success(token)
        }
    }

    private static func parseLibraryUser(_ body: String) -> Result<UpcLibraryUser, FailureBox> {
        switch parseRoot(body) {
        case let .failure(box):
            return .failure(box)
        case let .success(root):
            guard let data = root["data"] as? [String: Any],
                  let id = stringField(data["id"]), !id.isEmpty,
                  let username = stringField(data["username"]), !username.isEmpty else {
                return .failure(FailureBox(.protocolError))
            }
            let rawName = stringField(data["name"]) ?? ""
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? username : rawName
            return .success(UpcLibraryUser(id: id, username: username, name: name))
        }
    }

    private static func parseRoot(_ body: String) -> Result<[String: Any], FailureBox> {
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return .failure(FailureBox(.protocolError))
        }
        let code = intField(root["code"])
        if code == 12 {
            return .failure(FailureBox(.accessDenied))
        }
        if let boolStatus = root["status"] as? Bool, !(root["status"] is NSNumber && CFGetTypeID(root["status"] as CFTypeRef) != CFBooleanGetTypeID()) {
            if !boolStatus {
                return .failure(FailureBox(.upstreamError))
            }
        }
        if let strStatus = root["status"] as? String {
            let lowered = strStatus.lowercased()
            if lowered == "fail" || lowered == "error" {
                return .failure(FailureBox(.upstreamError))
            }
        }
        if let code, code != 0 && code != 200 {
            return .failure(FailureBox(.upstreamError))
        }
        if root["status"] == nil && root["data"] == nil && root["success"] == nil {
            return .failure(FailureBox(.protocolError))
        }
        return .success(root)
    }

    private static func stringField(_ value: Any?) -> String? {
        if let str = value as? String { return str }
        if let num = value as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            return num.stringValue
        }
        return nil
    }

    private static func intField(_ value: Any?) -> Int? {
        if let num = value as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            return num.intValue
        }
        if let str = value as? String {
            return Int(str.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    private static func urlQueryEncode(_ raw: String) -> String? {
        let allowed = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        return raw.addingPercentEncoding(withAllowedCharacters: allowed)
    }
}

private struct FailureBox: Error {
    let reason: UpcLibraryAuthFailure
    init(_ reason: UpcLibraryAuthFailure) { self.reason = reason }
}

private extension UpcLibraryAuthResult {
    static func failure(reason box: FailureBox) -> UpcLibraryAuthResult {
        .failure(reason: box.reason)
    }
}

private struct LibraryStepFailure: Error {
    let reason: UpcLibraryAuthFailure
    let message: String?

    var asAuthResult: UpcLibraryAuthResult {
        .failure(reason: reason, interactionURL: nil, message: message)
    }
}
