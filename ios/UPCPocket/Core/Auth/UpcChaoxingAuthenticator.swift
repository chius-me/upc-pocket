import Foundation

enum UpcChaoxingAuthEndpoints: Sendable {
    static let casHost = "cas.upc.edu.cn"
    static let tyrzfwHost = "tyrzfw.chaoxing.com"
    static let passport2ApiHost = "passport2-api.chaoxing.com"
    static let officeHost = "office.chaoxing.com"
    static let iChaoxingHost = "i.chaoxing.com"
    static let chaoxingCookieDomain = "chaoxing.com"

    static let fidEnc = "e32f8fbde51f9dba"
    static let mappId = "4197453"
    static let seatEngineMappId = "20853788"
    static let seatEngineSeatId = "4258"

    static let seatEntryString =
        "https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=\(fidEnc)&mappId=\(mappId)"
    static let seatEngineEntryString =
        "https://office.chaoxing.com/front/third/apps/seatengine/index" +
            "?fidEnc=\(fidEnc)&mappId=\(seatEngineMappId)&seatId=\(seatEngineSeatId)"
    static let tyrzfwInitPath = "/auth3/upc/cas/init"
    static let tyrzfwCallbackPath = "/auth3/upc/cas/index"
    static let passport2CasreceivePath = "/api/v2/casreceive"
    static let officeSeatPath = "/front/third/apps/seat/index"
    static let officeLegacySeatPath = "/front/apps/seat/index"
    static let casService = "https://tyrzfw.chaoxing.com/auth3/upc/cas/index"
    static let maxRedirectHops = 8

    static let seatEntryURL = URL(string: seatEntryString)!
    static let seatEngineEntryURL = URL(string: seatEngineEntryString)!
}

enum UpcChaoxingAuthFailure: Equatable, Sendable {
    case invalidCredentials
    case interactionRequired
    case networkUnavailable
    case tlsError
    case upstreamError
    case protocolError
    case accessDenied
}

enum UpcChaoxingAuthResult: Sendable, CustomStringConvertible {
    case success(cookieHeader: String, indexHTML: String)
    case failure(reason: UpcChaoxingAuthFailure, interactionURL: URL? = nil, message: String? = nil)

    var description: String {
        switch self {
        case .success:
            "UpcChaoxingAuthResult.success(cookieHeader=<redacted>)"
        case let .failure(reason, _, _):
            "UpcChaoxingAuthResult.failure(\(reason))"
        }
    }
}

struct UpcChaoxingAuthenticator: Sendable {
    private let transport: CasHTTPTransport
    private let casService: UpcCasService

    init(transport: @escaping CasHTTPTransport = UpcCasLogin.defaultTransport) {
        self.transport = transport
        self.casService = UpcCasService(
            casHost: UpcChaoxingAuthEndpoints.casHost,
            serviceURL: UpcChaoxingAuthEndpoints.casService,
            callbackHost: UpcChaoxingAuthEndpoints.tyrzfwHost,
            callbackPath: UpcChaoxingAuthEndpoints.tyrzfwCallbackPath,
            callbackParameterNames: ["ticket"],
            maxRedirectHops: UpcChaoxingAuthEndpoints.maxRedirectHops
        )
    }

    func authenticate(credential: SchoolLoginCredential) async -> UpcChaoxingAuthResult {
        let jar = UpcCasCookieJar(allowedHosts: [
            UpcChaoxingAuthEndpoints.casHost,
            UpcChaoxingAuthEndpoints.tyrzfwHost,
            UpcChaoxingAuthEndpoints.passport2ApiHost,
            UpcChaoxingAuthEndpoints.iChaoxingHost,
            UpcChaoxingAuthEndpoints.officeHost,
            UpcChaoxingAuthEndpoints.chaoxingCookieDomain
        ])
        let result = await performLogin(credential: credential, jar: jar)
        await jar.clear()
        return result
    }

    private func performLogin(
        credential: SchoolLoginCredential,
        jar: UpcCasCookieJar
    ) async -> UpcChaoxingAuthResult {
        let entry: RawHTTPResponse
        do {
            entry = try await executeWithJar(
                jar: jar,
                request: CasHTTPRequest(url: UpcChaoxingAuthEndpoints.seatEntryURL, method: "GET")
            )
        } catch let failure as ChaoxingStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        guard (300...399).contains(entry.statusCode) else {
            return failureForStatus(entry.statusCode)
        }
        guard let location = entry.location,
              let initURL = URL(string: location, relativeTo: UpcChaoxingAuthEndpoints.seatEntryURL)?.absoluteURL,
              Self.isAllowedTyrzfwInit(initURL) else {
            return .failure(reason: .protocolError)
        }

        let initPage: RawHTTPResponse
        do {
            initPage = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: initURL, method: "GET"))
        } catch let failure as ChaoxingStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }
        guard initPage.statusCode == 200 else {
            return failureForStatus(initPage.statusCode)
        }
        guard let rawCas = Self.extractJsLocation(initPage.body),
              let casURL = URL(string: rawCas, relativeTo: initURL)?.absoluteURL,
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

        var callbackPage: RawHTTPResponse
        do {
            callbackPage = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: callbackURL, method: "GET"))
            if (300...399).contains(callbackPage.statusCode) {
                guard let nextLoc = callbackPage.location,
                      let nextURL = URL(string: nextLoc, relativeTo: callbackURL)?.absoluteURL,
                      Self.isAllowedTyrzfwCallback(nextURL, requireTicket: false) else {
                    return .failure(reason: .protocolError)
                }
                callbackPage = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: nextURL, method: "GET"))
            }
        } catch let failure as ChaoxingStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        guard callbackPage.statusCode == 200 else {
            return failureForStatus(callbackPage.statusCode)
        }
        guard let passportRaw = Self.extractJsLocation(callbackPage.body),
              let passportURL = URL(string: passportRaw, relativeTo: callbackURL)?.absoluteURL,
              Self.isAllowedPassport2Casreceive(passportURL) else {
            return .failure(reason: .protocolError)
        }

        var receive: RawHTTPResponse
        do {
            receive = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: passportURL, method: "GET"))
            if (300...399).contains(receive.statusCode) {
                guard let nextLoc = receive.location,
                      let nextURL = URL(string: nextLoc, relativeTo: passportURL)?.absoluteURL,
                      Self.isAllowedIChaoxing(nextURL) else {
                    return .failure(reason: .protocolError)
                }
                receive = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: nextURL, method: "GET"))
            }
        } catch let failure as ChaoxingStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        guard receive.statusCode == 200 else {
            return failureForStatus(receive.statusCode)
        }
        guard let referRaw = Self.extractHiddenInput(receive.body, id: "refer"),
              let officeURL = URL(string: Self.decodeHtmlEntities(referRaw)),
              Self.isAllowedOfficeSeatEntry(officeURL) else {
            return .failure(reason: .protocolError)
        }

        var currentOffice = officeURL
        var office: RawHTTPResponse
        do {
            office = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: currentOffice, method: "GET"))
            var hops = 0
            while (300...399).contains(office.statusCode), hops < UpcChaoxingAuthEndpoints.maxRedirectHops {
                hops += 1
                guard let nextLoc = office.location,
                      let nextURL = URL(string: nextLoc, relativeTo: currentOffice)?.absoluteURL,
                      Self.isAllowedOfficeSeatEntry(nextURL) else {
                    return .failure(reason: .protocolError)
                }
                currentOffice = nextURL
                office = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: nextURL, method: "GET"))
            }
        } catch let failure as ChaoxingStepFailure {
            return failure.asAuthResult
        } catch {
            return .failure(reason: .networkUnavailable)
        }

        guard office.statusCode == 200 else {
            return failureForStatus(office.statusCode)
        }
        guard office.body.contains("userLoginInfo") else {
            return .failure(reason: .protocolError)
        }

        guard let cookieHeader = await jar.cookieHeader(for: UpcChaoxingAuthEndpoints.seatEntryURL),
              Self.isPlausibleCookieHeader(cookieHeader) else {
            return .failure(reason: .protocolError)
        }
        return .success(cookieHeader: cookieHeader, indexHTML: office.body)
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
                throw ChaoxingStepFailure(reason: .networkUnavailable, message: msg)
            case .redirectBlocked, .httpStatus, .responseTooLarge, .invalidEncoding:
                throw ChaoxingStepFailure(reason: .upstreamError, message: error.localizedDescription)
            }
        }
    }

    private func failureForStatus(_ status: Int) -> UpcChaoxingAuthResult {
        if [401, 403, 429].contains(status) {
            return .failure(reason: .accessDenied)
        }
        if status >= 500 {
            return .failure(reason: .upstreamError)
        }
        return .failure(reason: .protocolError)
    }

    static func isAllowedHTTPSHost(_ url: URL, host: String) -> Bool {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return false }
        return comps.scheme?.lowercased() == "https" &&
            comps.host?.lowercased() == host &&
            (comps.port == nil || comps.port == 443) &&
            comps.user == nil &&
            comps.password == nil &&
            comps.fragment == nil
    }

    static func isAllowedTyrzfwInit(_ url: URL) -> Bool {
        guard isAllowedHTTPSHost(url, host: UpcChaoxingAuthEndpoints.tyrzfwHost),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true),
              comps.percentEncodedPath == UpcChaoxingAuthEndpoints.tyrzfwInitPath,
              let query = UpcCasService.parseSingleValueQueryParams(comps.percentEncodedQuery) else {
            return false
        }
        let allowed: Set<String> = ["refer", "mappId"]
        guard query.keys.allSatisfy({ allowed.contains($0) }),
              let refer = query["refer"],
              isAllowedSeatRefer(refer) else {
            return false
        }
        if let mappId = query["mappId"] {
            return mappId == UpcChaoxingAuthEndpoints.mappId
        }
        return true
    }

    static func isAllowedTyrzfwCallback(_ url: URL, requireTicket: Bool) -> Bool {
        guard isAllowedHTTPSHost(url, host: UpcChaoxingAuthEndpoints.tyrzfwHost),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true),
              comps.percentEncodedPath == UpcChaoxingAuthEndpoints.tyrzfwCallbackPath,
              let query = UpcCasService.parseSingleValueQueryParams(comps.percentEncodedQuery) else {
            return false
        }
        if requireTicket {
            guard query.count == 1,
                  let ticket = query["ticket"],
                  ticket.hasPrefix("ST-"),
                  (8...4096).contains(ticket.count) else {
                return false
            }
            return true
        }
        return query.isEmpty
    }

    static func isAllowedPassport2Casreceive(_ url: URL) -> Bool {
        guard isAllowedHTTPSHost(url, host: UpcChaoxingAuthEndpoints.passport2ApiHost),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true),
              comps.percentEncodedPath == UpcChaoxingAuthEndpoints.passport2CasreceivePath,
              let query = UpcCasService.parseSingleValueQueryParams(comps.percentEncodedQuery),
              !query.isEmpty else {
            return false
        }
        let allowed: Set<String> = [
            "thirdinfo", "refer", "setmain", "time", "signature", "appid", "casfrom", "casinfo", "casenc"
        ]
        guard query.keys.allSatisfy({ allowed.contains($0) }),
              let thirdinfo = query["thirdinfo"], !thirdinfo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let refer = query["refer"], isAllowedSeatRefer(refer),
              query["setmain"] == "true" else {
            return false
        }
        if let casfrom = query["casfrom"], casfrom != "login" {
            return false
        }
        return true
    }

    static func isAllowedIChaoxing(_ url: URL) -> Bool {
        guard isAllowedHTTPSHost(url, host: UpcChaoxingAuthEndpoints.iChaoxingHost),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
            return false
        }
        let path = comps.percentEncodedPath
        return path.isEmpty || path == "/" || path == "/base" || path == "/base/"
    }

    static func isAllowedOfficeSeatEntry(_ url: URL) -> Bool {
        guard isAllowedHTTPSHost(url, host: UpcChaoxingAuthEndpoints.officeHost),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
            return false
        }
        let path = comps.percentEncodedPath
        guard path == UpcChaoxingAuthEndpoints.officeSeatPath ||
              path == UpcChaoxingAuthEndpoints.officeLegacySeatPath,
              let query = UpcCasService.parseSingleValueQueryParams(comps.percentEncodedQuery) else {
            return false
        }
        return Set(query.keys) == ["fidEnc", "mappId"] &&
            query["fidEnc"] == UpcChaoxingAuthEndpoints.fidEnc &&
            query["mappId"] == UpcChaoxingAuthEndpoints.mappId
    }

    static func isAllowedSeatRefer(_ raw: String) -> Bool {
        guard let url = URL(string: raw),
              isAllowedHTTPSHost(url, host: UpcChaoxingAuthEndpoints.officeHost),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
            return false
        }
        let path = comps.percentEncodedPath
        guard path == UpcChaoxingAuthEndpoints.officeSeatPath ||
              path == UpcChaoxingAuthEndpoints.officeLegacySeatPath,
              let query = UpcCasService.parseSingleValueQueryParams(comps.percentEncodedQuery),
              query["fidEnc"] == UpcChaoxingAuthEndpoints.fidEnc else {
            return false
        }
        let allowed: Set<String> = ["fidEnc", "mappId", "seatId"]
        return query.keys.allSatisfy { allowed.contains($0) }
    }

    private static func extractJsLocation(_ html: String) -> String? {
        guard let regex = try? NSRegularExpression(
            pattern: #"window\.location(?:\.href)?\s*=\s*["']([^"']+)["']"#,
            options: [.caseInsensitive]
        ) else {
            return nil
        }
        let nsRange = NSRange(html.startIndex..<html.endIndex, in: html)
        guard let match = regex.firstMatch(in: html, options: [], range: nsRange),
              let range = Range(match.range(at: 1), in: html) else {
            return nil
        }
        return decodeJsString(String(html[range]))
    }

    private static func extractHiddenInput(_ html: String, id: String) -> String? {
        guard let inputRegex = try? NSRegularExpression(pattern: #"<input\b[^>]*>"#, options: [.caseInsensitive]),
              let idRegex = try? NSRegularExpression(pattern: #"\bid\s*=\s*["']([^"']+)["']"#, options: [.caseInsensitive]),
              let valRegex = try? NSRegularExpression(pattern: #"\bvalue\s*=\s*["']([^"']*)["']"#, options: [.caseInsensitive]) else {
            return nil
        }
        let htmlRange = NSRange(html.startIndex..<html.endIndex, in: html)
        for inputMatch in inputRegex.matches(in: html, options: [], range: htmlRange) {
            guard let tagRange = Range(inputMatch.range, in: html) else { continue }
            let tag = String(html[tagRange])
            let tagNS = NSRange(tag.startIndex..<tag.endIndex, in: tag)
            guard let idMatch = idRegex.firstMatch(in: tag, options: [], range: tagNS),
                  let idR = Range(idMatch.range(at: 1), in: tag),
                  String(tag[idR]) == id,
                  let valMatch = valRegex.firstMatch(in: tag, options: [], range: tagNS),
                  let valR = Range(valMatch.range(at: 1), in: tag) else {
                continue
            }
            return String(tag[valR])
        }
        return nil
    }

    private static func decodeJsString(_ value: String) -> String {
        var out = value.replacingOccurrences(of: "\\/", with: "/")
        if let uRegex = try? NSRegularExpression(pattern: #"\\u([0-9a-fA-F]{4})"#) {
            let matches = uRegex.matches(in: out, options: [], range: NSRange(out.startIndex..<out.endIndex, in: out)).reversed()
            for m in matches {
                if let hexR = Range(m.range(at: 1), in: out),
                   let fullR = Range(m.range, in: out),
                   let scalarVal = UInt32(out[hexR], radix: 16),
                   let scalar = UnicodeScalar(scalarVal) {
                    out.replaceSubrange(fullR, with: String(scalar))
                }
            }
        }
        if let xRegex = try? NSRegularExpression(pattern: #"\\x([0-9a-fA-F]{2})"#) {
            let matches = xRegex.matches(in: out, options: [], range: NSRange(out.startIndex..<out.endIndex, in: out)).reversed()
            for m in matches {
                if let hexR = Range(m.range(at: 1), in: out),
                   let fullR = Range(m.range, in: out),
                   let scalarVal = UInt32(out[hexR], radix: 16),
                   let scalar = UnicodeScalar(scalarVal) {
                    out.replaceSubrange(fullR, with: String(scalar))
                }
            }
        }
        return out
            .replacingOccurrences(of: "\\\"", with: "\"")
            .replacingOccurrences(of: "\\\\", with: "\\")
    }

    private static func decodeHtmlEntities(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
    }

    private static func isPlausibleCookieHeader(_ value: String) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            value.count <= 8 * 1024 &&
            value.contains("=") &&
            !value.contains("\r") &&
            !value.contains("\n")
    }
}

private struct ChaoxingStepFailure: Error {
    let reason: UpcChaoxingAuthFailure
    let message: String?

    var asAuthResult: UpcChaoxingAuthResult {
        .failure(reason: reason, interactionURL: nil, message: message)
    }
}
