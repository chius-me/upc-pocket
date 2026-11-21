import Foundation

enum UpcTimetableAuthEndpoints: Sendable {
    static let casHost = "cas.upc.edu.cn"
    static let appHost = "app.upc.edu.cn"
    static let pageURLString = "https://app.upc.edu.cn/site/weekschedule/index"
    static let indexURLString = "https://app.upc.edu.cn/timetable/wap/default/get-index"
    static let weekURLString = "https://app.upc.edu.cn/timetable/wap/default/get-datatmp"

    // Inner redirect uses single-level percent encoding per UPC CAS gateway expectation.
    static let service =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex"
    static let serviceDecoded =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https://app.upc.edu.cn/site/weekschedule/index"
    static let loginURLString =
        "https://cas.upc.edu.cn/cas/login?service=\(service)&from=wap&noAutoRedirect=1"
    static let appCallbackPath = "/a_upc/api/cas/login"

    static let pageURL = URL(string: pageURLString)!
    static let indexURL = URL(string: indexURLString)!
    static let weekURL = URL(string: weekURLString)!
    static let loginURL = URL(string: loginURLString)!
}

enum TimetableAuthResult: Sendable, CustomStringConvertible {
    case success(cookies: [String: String], indexBody: String)
    case invalidCredentials
    case interactionRequired(interactionURL: URL, reason: String)
    case networkError(String)
    case upstreamError(String)

    var description: String {
        switch self {
        case let .success(cookies, _): "TimetableAuthResult.success(cookieCount: \(cookies.count))"
        case .invalidCredentials: "TimetableAuthResult.invalidCredentials"
        case let .interactionRequired(_, reason): "TimetableAuthResult.interactionRequired(\(reason))"
        case let .networkError(msg): "TimetableAuthResult.networkError(\(msg))"
        case let .upstreamError(msg): "TimetableAuthResult.upstreamError(\(msg))"
        }
    }
}

enum TimetableVerifyResult: Sendable {
    case valid(indexBody: String)
    case authRequired
    case networkError(String)
    case upstreamError(String)
}

private struct StrictCodeEnvelope: Decodable {
    let e: Int

    enum CodingKeys: String, CodingKey {
        case e
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let intVal = try? container.decode(Int.self, forKey: .e) {
            self.e = intVal
            return
        }
        if let strVal = try? container.decode(String.self, forKey: .e) {
            let trimmed = strVal.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.range(of: "^[+-]?\\d+$", options: .regularExpression) != nil,
               let parsed = Int(trimmed) {
                self.e = parsed
                return
            }
        }
        throw DecodingError.dataCorruptedError(
            forKey: .e,
            in: container,
            debugDescription: "Expected integral business code e"
        )
    }
}

struct UpcTimetableAuthenticator: Sendable {
    private let transport: CasHTTPTransport
    private let casService: UpcCasService

    init(transport: @escaping CasHTTPTransport = UpcCasLogin.defaultTransport) {
        self.transport = transport
        self.casService = UpcCasService(
            casHost: UpcTimetableAuthEndpoints.casHost,
            serviceURL: UpcTimetableAuthEndpoints.serviceDecoded,
            callbackHost: UpcTimetableAuthEndpoints.appHost,
            callbackPath: UpcTimetableAuthEndpoints.appCallbackPath,
            callbackParameterNames: ["ticket", "redirect", "from"],
            maxRedirectHops: 8,
            isAllowedCallbackQuery: { params in
                if let redirect = params["redirect"], redirect != UpcTimetableAuthEndpoints.pageURLString {
                    return false
                }
                if let from = params["from"], from != "wap" {
                    return false
                }
                return true
            }
        )
    }

    func authenticate(credential: SchoolLoginCredential) async -> TimetableAuthResult {
        let casLogin = UpcCasLogin(service: casService, transport: transport)
        let result = await casLogin.login(
            credential: credential,
            casURL: UpcTimetableAuthEndpoints.loginURL,
            isAllowedAppHop: { url in
                Self.isAllowedAppHop(url, casService: casService)
            }
        )

        switch result {
        case .invalidCredentials:
            return .invalidCredentials
        case let .interactionRequired(url, reason):
            return .interactionRequired(interactionURL: url, reason: reason)
        case let .networkError(message):
            return .networkError(message)
        case let .upstreamError(message):
            return .upstreamError(message)
        case let .success(cookies, _):
            switch await verifySessionCookies(cookies) {
            case let .valid(indexBody):
                return .success(cookies: cookies, indexBody: indexBody)
            case .authRequired:
                return .interactionRequired(
                    interactionURL: UpcTimetableAuthEndpoints.loginURL,
                    reason: "业务会话未能生效，请通过交互页面完成登录"
                )
            case let .networkError(message):
                return .networkError(message)
            case let .upstreamError(message):
                return .upstreamError(message)
            }
        }
    }

    func verifySessionCookies(_ cookies: [String: String]) async -> TimetableVerifyResult {
        guard let cookieHeader = Self.formatCookieHeader(cookies) else {
            return .authRequired
        }
        let response: RawHTTPResponse
        do {
            response = try await transport(
                CasHTTPRequest(
                    url: UpcTimetableAuthEndpoints.indexURL,
                    method: "GET",
                    headers: [
                        "Accept": "application/json, text/plain, */*",
                        "X-Requested-With": "XMLHttpRequest",
                        "Referer": UpcTimetableAuthEndpoints.pageURLString,
                        "Cookie": cookieHeader
                    ]
                )
            )
        } catch let error as StrictHTTPError {
            switch error {
            case let .transport(message):
                return .networkError(message)
            case .redirectBlocked, .httpStatus, .responseTooLarge, .invalidEncoding:
                return .upstreamError(error.localizedDescription)
            }
        } catch {
            return .networkError("无法连接课表服务，请检查网络后重试")
        }

        if (300...399).contains(response.statusCode) || response.statusCode == 401 {
            return .authRequired
        }
        guard response.statusCode == 200 else {
            return .upstreamError("课表接口暂时不可用（HTTP \(response.statusCode)）")
        }

        let trimmed = response.body.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF}\u{200B}").union(.whitespacesAndNewlines))
        guard !trimmed.isEmpty else {
            return .upstreamError("课表索引返回空响应")
        }
        if trimmed.hasPrefix("<") {
            return .authRequired
        }

        guard let data = trimmed.data(using: .utf8),
              let envelope = try? JSONDecoder().decode(StrictCodeEnvelope.self, from: data) else {
            return .upstreamError("课表索引响应结构无法解析")
        }

        switch envelope.e {
        case 10013:
            return .authRequired
        case 0:
            return .valid(indexBody: response.body)
        default:
            return .upstreamError("课表服务返回业务错误码（\(envelope.e)）")
        }
    }

    static func formatCookieHeader(_ cookies: [String: String]) -> String? {
        guard !cookies.isEmpty else { return nil }
        let nameAllowed = CharacterSet(
            charactersIn: "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        )
        let pairs = cookies.compactMap { name, value -> String? in
            guard !name.isEmpty,
                  name.count <= 256,
                  name.unicodeScalars.allSatisfy({ nameAllowed.contains($0) }),
                  value.count <= 4096,
                  value.unicodeScalars.allSatisfy({ $0.value >= 0x21 && $0.value <= 0x7E }) else {
                return nil
            }
            return "\(name)=\(value)"
        }
        guard !pairs.isEmpty else { return nil }
        return pairs.joined(separator: "; ")
    }

    private static func isAllowedAppHop(_ url: URL, casService: UpcCasService) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == UpcTimetableAuthEndpoints.appHost,
              components.port == nil || components.port == 443,
              components.user == nil,
              components.password == nil,
              components.fragment == nil else {
            return false
        }
        switch components.percentEncodedPath {
        case UpcTimetableAuthEndpoints.appCallbackPath:
            return casService.isCallbackURL(url)
        case "/site/weekschedule/index", "/timetable/wap/default/get-index":
            return true
        default:
            return false
        }
    }
}
