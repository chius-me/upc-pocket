import Foundation

struct CasHTTPRequest: Sendable, CustomStringConvertible {
    let url: URL
    let method: String
    let headers: [String: String]
    let formItems: [(String, String)]?

    init(
        url: URL,
        method: String = "GET",
        headers: [String: String] = [:],
        formItems: [(String, String)]? = nil
    ) {
        self.url = url
        self.method = method
        self.headers = headers
        self.formItems = formItems
    }

    var description: String {
        "CasHTTPRequest(method: \(method), url: \(url.absoluteString), hasBody: \(formItems != nil))"
    }
}

typealias CasHTTPTransport = @Sendable (CasHTTPRequest) async throws -> RawHTTPResponse

struct UpcCasService: Sendable {
    let casHost: String
    let serviceURL: String
    let callbackHost: String
    let callbackPath: String
    let callbackParameterNames: Set<String>
    let maxRedirectHops: Int
    let isAllowedCallbackQuery: @Sendable ([String: String]) -> Bool

    init(
        casHost: String,
        serviceURL: String,
        callbackHost: String,
        callbackPath: String,
        callbackParameterNames: Set<String>,
        maxRedirectHops: Int = 8,
        isAllowedCallbackQuery: @escaping @Sendable ([String: String]) -> Bool = { _ in true }
    ) {
        self.casHost = casHost
        self.serviceURL = serviceURL
        self.callbackHost = callbackHost
        self.callbackPath = callbackPath
        self.callbackParameterNames = callbackParameterNames
        self.maxRedirectHops = maxRedirectHops
        self.isAllowedCallbackQuery = isAllowedCallbackQuery
    }

    func isCasLoginURL(_ url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == casHost.lowercased(),
              components.port == nil || components.port == 443,
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              components.percentEncodedPath == "/cas/login",
              let params = Self.parseSingleValueQueryParams(components.percentEncodedQuery) else {
            return false
        }
        let allowed: Set<String> = ["service", "from", "noAutoRedirect"]
        guard params.keys.allSatisfy({ allowed.contains($0) }),
              params["service"] == serviceURL else {
            return false
        }
        if let fromVal = params["from"], fromVal != "wap" { return false }
        if let noAuto = params["noAutoRedirect"], noAuto != "1" { return false }
        return true
    }

    func isCallbackURL(_ url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == callbackHost.lowercased(),
              components.port == nil || components.port == 443,
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              components.percentEncodedPath == callbackPath,
              let params = Self.parseSingleValueQueryParams(components.percentEncodedQuery),
              params.keys.allSatisfy({ callbackParameterNames.contains($0) }),
              let ticket = params["ticket"],
              ticket.hasPrefix("ST-"),
              (8...4096).contains(ticket.count) else {
            return false
        }
        return isAllowedCallbackQuery(params)
    }

    static func parseSingleValueQueryParams(_ rawQuery: String?) -> [String: String]? {
        guard let rawQuery, !rawQuery.isEmpty else { return [:] }
        var result: [String: String] = [:]
        for pair in rawQuery.split(separator: "&", omittingEmptySubsequences: false) {
            if pair.isEmpty { continue }
            let eq = pair.firstIndex(of: "=")
            let rawKey = String(eq.map { pair[..<$0] } ?? pair[...])
            let rawVal = eq.map { String(pair[pair.index(after: $0)...]) } ?? ""
            guard let key = rawKey.removingPercentEncoding,
                  let val = rawVal.removingPercentEncoding,
                  result[key] == nil else {
                return nil
            }
            result[key] = val
        }
        return result
    }
}

enum CasCallbackResult: Sendable {
    case callback(URL)
    case invalidCredentials
    case interactionRequired(interactionURL: URL, reason: String)
    case networkError(String)
    case upstreamError(String)
}

enum CasLoginResult: Sendable, CustomStringConvertible {
    case success(targetCookies: [String: String], finalURL: URL)
    case invalidCredentials
    case interactionRequired(interactionURL: URL, reason: String)
    case networkError(String)
    case upstreamError(String)

    var description: String {
        switch self {
        case let .success(cookies, _): "CasLoginResult.success(cookieCount: \(cookies.count))"
        case .invalidCredentials: "CasLoginResult.invalidCredentials"
        case let .interactionRequired(_, reason): "CasLoginResult.interactionRequired(\(reason))"
        case let .networkError(msg): "CasLoginResult.networkError(\(msg))"
        case let .upstreamError(msg): "CasLoginResult.upstreamError(\(msg))"
        }
    }
}

private struct CasLoginForm: Sendable {
    let action: String?
    let execution: String?
    let type: String
    let eventId: String
    let submit: String
    let captchaRequired: Bool
    let errorText: String?
    let hasPassword: Bool
    let hasSecondFactor: Bool
    let hasTypeField: Bool
    let hasEventIdField: Bool
}

struct UpcCasLogin: Sendable {
    private let service: UpcCasService
    private let transport: CasHTTPTransport

    init(
        service: UpcCasService,
        transport: @escaping CasHTTPTransport = UpcCasLogin.defaultTransport
    ) {
        self.service = service
        self.transport = transport
    }

    static let defaultTransport: CasHTTPTransport = { request in
        try await StrictHTTPClient.shared.executeRaw(
            url: request.url,
            method: request.method,
            headers: request.headers,
            formItems: request.formItems
        )
    }

    func login(
        credential: SchoolLoginCredential,
        casURL: URL,
        isAllowedAppHop: @Sendable (URL) -> Bool = { url in
            guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return false }
            return comps.scheme?.lowercased() == "https" &&
                (comps.port == nil || comps.port == 443) &&
                comps.user == nil &&
                comps.password == nil &&
                comps.fragment == nil
        }
    ) async -> CasLoginResult {
        let jar = UpcCasCookieJar(allowedHosts: [service.casHost, service.callbackHost])
        let callbackResult = await authenticateToCallback(credential: credential, casURL: casURL, jar: jar)
        switch callbackResult {
        case .invalidCredentials:
            await jar.clear()
            return .invalidCredentials
        case let .interactionRequired(interactionURL, reason):
            await jar.clear()
            return .interactionRequired(interactionURL: interactionURL, reason: reason)
        case let .networkError(msg):
            await jar.clear()
            return .networkError(msg)
        case let .upstreamError(msg):
            await jar.clear()
            return .upstreamError(msg)
        case let .callback(targetURL):
            do {
                return try await followCallbackHops(
                    jar: jar,
                    startURL: targetURL,
                    isAllowedAppHop: isAllowedAppHop
                )
            } catch let error as StrictHTTPError {
                await jar.clear()
                switch error {
                case let .transport(msg):
                    return .networkError(msg)
                case .redirectBlocked, .httpStatus, .responseTooLarge, .invalidEncoding:
                    return .upstreamError(error.localizedDescription)
                }
            } catch {
                await jar.clear()
                return .networkError("无法连接学校认证服务，请检查网络后重试")
            }
        }
    }

    func authenticateToCallback(
        credential: SchoolLoginCredential,
        casURL: URL,
        jar: UpcCasCookieJar
    ) async -> CasCallbackResult {
        guard service.isCasLoginURL(casURL) else {
            return .upstreamError("CAS 登录入口参数校验未通过")
        }

        do {
            let getResponse = try await executeWithJar(
                jar: jar,
                request: CasHTTPRequest(url: casURL, method: "GET")
            )
            if (300...399).contains(getResponse.statusCode) {
                return .upstreamError("学校认证页返回意外重定向（HTTP \(getResponse.statusCode)）")
            }
            if getResponse.statusCode == 429 {
                return .upstreamError("学校认证请求过于频繁，请稍后重试")
            }
            guard [200, 401, 403].contains(getResponse.statusCode) else {
                return .upstreamError("学校认证服务暂时不可用（HTTP \(getResponse.statusCode)）")
            }

            let form = Self.parseCasLoginForm(getResponse.body)
            if let form, form.captchaRequired || form.hasSecondFactor {
                return .interactionRequired(
                    interactionURL: casURL,
                    reason: form.hasSecondFactor ? "需要二次验证" : "需要图形或行为验证码"
                )
            }
            if let form, Self.isExplicitCredentialRejection(form.errorText) {
                return .invalidCredentials
            }
            guard let form,
                  let actionURL = validateAndResolveFormAction(form: form, pageURL: casURL) else {
                if getResponse.statusCode == 401 || getResponse.statusCode == 403 {
                    return .upstreamError("学校认证拒绝访问（HTTP \(getResponse.statusCode)）")
                }
                return .upstreamError("未检测到可验证的 CAS 登录表单")
            }

            let formItems: [(String, String)] = [
                ("username", credential.loginName),
                ("password", credential.password),
                ("type", form.type),
                ("execution", form.execution ?? ""),
                ("_eventId", form.eventId),
                ("submit", form.submit)
            ]

            let postResponse = try await executeWithJar(
                jar: jar,
                request: CasHTTPRequest(
                    url: actionURL,
                    method: "POST",
                    headers: [
                        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin": "https://\(service.casHost)",
                        "Referer": casURL.absoluteString
                    ],
                    formItems: formItems
                )
            )

            if (300...399).contains(postResponse.statusCode) {
                guard let location = postResponse.location,
                      let targetURL = URL(string: location, relativeTo: actionURL)?.absoluteURL else {
                    return .upstreamError("CAS 认证重定向缺少或无效目标地址")
                }

                if service.isCasLoginURL(targetURL) {
                    return try await classifyReturnedCasPage(
                        jar: jar,
                        targetURL: targetURL,
                        interactionURL: casURL
                    )
                } else if service.isCallbackURL(targetURL) {
                    return .callback(targetURL)
                } else {
                    return .upstreamError("CAS 回调目标未通过域与参数白名单校验")
                }
            }

            if postResponse.statusCode == 429 {
                return .upstreamError("学校认证请求过于频繁，请稍后重试")
            }
            guard [200, 401, 403].contains(postResponse.statusCode) else {
                return .upstreamError("学校认证提交返回 HTTP \(postResponse.statusCode)")
            }

            if let returned = Self.parseCasLoginForm(postResponse.body) {
                if returned.captchaRequired || returned.hasSecondFactor {
                    return .interactionRequired(
                        interactionURL: casURL,
                        reason: returned.hasSecondFactor ? "需要二次验证" : "需要验证码验证"
                    )
                }
                if Self.isExplicitCredentialRejection(returned.errorText) {
                    return .invalidCredentials
                }
            }
            return .interactionRequired(
                interactionURL: casURL,
                reason: "需要完成交互式学校登录验证"
            )
        } catch let error as StrictHTTPError {
            switch error {
            case let .transport(msg):
                return .networkError(msg)
            case .redirectBlocked, .httpStatus, .responseTooLarge, .invalidEncoding:
                return .upstreamError(error.localizedDescription)
            }
        } catch {
            return .networkError("无法连接学校认证服务，请检查网络后重试")
        }
    }

    private func classifyReturnedCasPage(
        jar: UpcCasCookieJar,
        targetURL: URL,
        interactionURL: URL
    ) async throws -> CasCallbackResult {
        let response = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: targetURL, method: "GET"))
        if response.statusCode == 429 {
            return .upstreamError("学校认证请求过于频繁，请稍后重试")
        }
        guard [200, 401, 403].contains(response.statusCode) else {
            return .upstreamError("CAS 返回页面异常（HTTP \(response.statusCode)）")
        }
        if let form = Self.parseCasLoginForm(response.body) {
            if form.captchaRequired || form.hasSecondFactor {
                return .interactionRequired(
                    interactionURL: interactionURL,
                    reason: form.hasSecondFactor ? "需要二次验证" : "需要验证码验证"
                )
            }
            if Self.isExplicitCredentialRejection(form.errorText) {
                return .invalidCredentials
            }
        }
        return .interactionRequired(
            interactionURL: interactionURL,
            reason: "需要通过页面完成学校验证"
        )
    }

    private func followCallbackHops(
        jar: UpcCasCookieJar,
        startURL: URL,
        isAllowedAppHop: @Sendable (URL) -> Bool
    ) async throws -> CasLoginResult {
        var current = startURL
        for _ in 0..<service.maxRedirectHops {
            guard isAllowedAppHop(current) else {
                return .upstreamError("业务回调跳转超出了允许的官方路径")
            }
            let response = try await executeWithJar(jar: jar, request: CasHTTPRequest(url: current, method: "GET"))
            if (300...399).contains(response.statusCode) {
                guard let location = response.location,
                      let nextURL = URL(string: location, relativeTo: current)?.absoluteURL else {
                    return .upstreamError("业务回调重定向缺少或非法 Location")
                }
                current = nextURL
                continue
            }
            if response.statusCode == 401 || response.statusCode == 403 {
                return .upstreamError("业务回调鉴权被拒绝（HTTP \(response.statusCode)）")
            }
            guard response.statusCode == 200 else {
                return .upstreamError("业务回调返回 HTTP \(response.statusCode)")
            }
            let appCookies = await jar.cookiesForHost(service.callbackHost)
            await jar.clear()
            guard !appCookies.isEmpty else {
                return .upstreamError("登录完成但未收到业务会话 Cookie")
            }
            return .success(targetCookies: appCookies, finalURL: current)
        }
        await jar.clear()
        return .upstreamError("业务回调跳转次数超过上限")
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
        let updatedRequest = CasHTTPRequest(
            url: request.url,
            method: request.method,
            headers: mergedHeaders,
            formItems: request.formItems
        )
        let response = try await transport(updatedRequest)
        await jar.saveFromHeaders(requestURL: request.url, setCookieHeaders: response.setCookieHeaders)
        return response
    }

    private func validateAndResolveFormAction(form: CasLoginForm, pageURL: URL) -> URL? {
        guard form.hasPassword, !form.captchaRequired, !form.hasSecondFactor else { return nil }
        guard form.hasTypeField, form.type == "username_password" else { return nil }
        guard form.hasEventIdField, form.eventId == "submit" else { return nil }
        guard let execution = form.execution, Self.isValidCasExecution(execution) else { return nil }
        let rawAction = form.action ?? pageURL.absoluteString
        guard let actionURL = URL(string: rawAction, relativeTo: pageURL)?.absoluteURL else { return nil }
        return service.isCasLoginURL(actionURL) ? actionURL : nil
    }

    private static let explicitCredentialErrors: Set<String> = [
        "用户名或密码错误",
        "账号或密码错误",
        "用户名或密码不正确",
        "账号或密码不正确",
        "密码错误",
        "密码不正确",
        "Invalidusernameorpassword",
        "Invalidcredentials"
    ]

    private static func isValidCasExecution(_ execution: String) -> Bool {
        let trimmed = execution.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, execution.count <= 64 * 1024 else { return false }
        return execution.unicodeScalars.allSatisfy { scalar in
            scalar.value > 0x20 && scalar.value != 0x7F
        }
    }

    private static func isExplicitCredentialRejection(_ errorText: String?) -> Bool {
        guard let errorText else { return false }
        let normalized = errorText.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        return explicitCredentialErrors.contains(normalized)
    }

    private static func parseCasLoginForm(_ html: String) -> CasLoginForm? {
        let markup = CasCaptchaChallengeDetector.visibleMarkup(html)
        let formMatches = matches(
            "<form\\b[^>]*>.*?</form>",
            options: [.caseInsensitive, .dotMatchesLineSeparators],
            in: markup
        )
        let forms = formMatches.compactMap { match -> String? in
            Range(match.range, in: markup).map { String(markup[$0]) }
        }
        let chosen = forms.first(where: { isVisibleCasForm($0) && hasPasswordInput($0) })
            ?? forms.first(where: isVisibleCasForm)

        var fields: [String: String] = [:]
        var hasPassword = false
        var action: String?

        if let form = chosen {
            if let openMatch = matches("<form\\b([^>]*)>", options: [.caseInsensitive], in: form).first,
               let openRange = Range(openMatch.range(at: 1), in: form) {
                action = casAttribute(in: String(form[openRange]), name: "action")
            }
            for inputMatch in matches("<input\\b([^>]*)>", options: [.caseInsensitive], in: form) {
                guard let attrsRange = Range(inputMatch.range(at: 1), in: form) else { continue }
                let attrs = String(form[attrsRange])
                guard let name = casAttribute(in: attrs, name: "name") else { continue }
                if casAttribute(in: attrs, name: "type")?.caseInsensitiveCompare("password") == .orderedSame {
                    hasPassword = true
                }
                if fields[name] == nil {
                    fields[name] = casAttribute(in: attrs, name: "value") ?? ""
                }
            }
        }

        let errorText: String? = {
            guard let errMatch = matches(
                "<div\\b[^>]*id\\s*=\\s*[\"']errorDiv[\"'][^>]*>(.*?)</div>",
                options: [.caseInsensitive, .dotMatchesLineSeparators],
                in: markup
            ).first,
                  let range = Range(errMatch.range(at: 1), in: markup) else {
                return nil
            }
            let stripped = String(markup[range])
                .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            let decoded = decodeCasEntities(stripped)
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return decoded.isEmpty ? nil : decoded
        }()

        let captcha = CasCaptchaChallengeDetector.inspect(html).active
        let secondFactor: Bool = if let chosen, hasPassword {
            hasActiveSecondFactor(in: chosen, formType: fields["type"])
        } else {
            forms.contains { hasActiveSecondFactor(in: $0, formType: activeFormType(in: $0)) }
        }

        return CasLoginForm(
            action: action,
            execution: fields["execution"],
            type: fields["type"] ?? "username_password",
            eventId: fields["_eventId"] ?? "submit",
            submit: fields["submit"] ?? "登录",
            captchaRequired: captcha,
            errorText: errorText,
            hasPassword: hasPassword,
            hasSecondFactor: secondFactor,
            hasTypeField: fields["type"] != nil,
            hasEventIdField: fields["_eventId"] != nil
        )
    }

    private static func hasPasswordInput(_ form: String) -> Bool {
        !matches(
            "<input\\b[^>]*type\\s*=\\s*[\"']password[\"']",
            options: [.caseInsensitive],
            in: form
        ).isEmpty
    }

    private static func activeFormType(in form: String) -> String? {
        for match in matches("<input\\b([^>]*)>", options: [.caseInsensitive], in: form) {
            guard let range = Range(match.range(at: 1), in: form) else { continue }
            let attrs = String(form[range])
            if casAttribute(in: attrs, name: "name")?.caseInsensitiveCompare("type") == .orderedSame {
                return casAttribute(in: attrs, name: "value")
            }
        }
        return nil
    }

    private static func isVisibleCasForm(_ form: String) -> Bool {
        guard let openMatch = matches("<form\\b([^>]*)>", options: [.caseInsensitive], in: form).first,
              let range = Range(openMatch.range(at: 1), in: form) else {
            return true
        }
        let open = String(form[range])
        if !matches("(?:^|\\s)hidden(?:\\s|=|$)", options: [.caseInsensitive], in: open).isEmpty {
            return false
        }
        if casAttribute(in: open, name: "aria-hidden")?.caseInsensitiveCompare("true") == .orderedSame {
            return false
        }
        let style = (casAttribute(in: open, name: "style") ?? "")
            .filter { !$0.isWhitespace }
            .lowercased()
        return !style.contains("display:none") && !style.contains("visibility:hidden")
    }

    private static func hasActiveSecondFactor(in form: String, formType: String?) -> Bool {
        guard isVisibleCasForm(form) else { return false }
        if formType?.caseInsensitiveCompare("username_smstoken") == .orderedSame {
            return true
        }
        for match in matches("<input\\b([^>]*)>", options: [.caseInsensitive], in: form) {
            guard let range = Range(match.range(at: 1), in: form) else { continue }
            let attrs = String(form[range])
            let name = casAttribute(in: attrs, name: "name")
            let type = casAttribute(in: attrs, name: "type")
            if name?.caseInsensitiveCompare("username_smstoken") == .orderedSame,
               type?.caseInsensitiveCompare("hidden") != .orderedSame {
                return true
            }
        }
        return false
    }

    private static func casAttribute(in attrs: String, name: String) -> String? {
        let pattern = "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
        for match in matches(pattern, in: attrs) {
            guard let nameRange = Range(match.range(at: 1), in: attrs) else { continue }
            guard String(attrs[nameRange]).caseInsensitiveCompare(name) == .orderedSame else { continue }
            let val: String = [2, 3, 4].compactMap { group -> String? in
                let nsRange = match.range(at: group)
                guard nsRange.location != NSNotFound, let r = Range(nsRange, in: attrs) else { return nil }
                let s = String(attrs[r])
                return s.isEmpty ? nil : s
            }.first ?? ""
            return decodeCasEntities(val)
        }
        return nil
    }

    private static func decodeCasEntities(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
    }

    private static func matches(
        _ pattern: String,
        options: NSRegularExpression.Options = [],
        in text: String
    ) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, options: [], range: range)
    }
}
