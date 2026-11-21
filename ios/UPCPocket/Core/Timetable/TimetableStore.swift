import Foundation

actor TimetableStore {
    private enum EnsureSessionOutcome: Sendable {
        case abortedForAccountChange
        case ready(cookies: [String: String], index: TimetableIndex, freshlyAuthenticated: Bool)
        case failed(
            status: TimetableConnectionStatus,
            message: String,
            interactionURL: URL? = nil,
            interactionReason: String? = nil
        )
    }

    static let defaultTransport: CasHTTPTransport = { request in
        try await StrictHTTPClient.shared.executeRaw(
            url: request.url,
            method: request.method,
            headers: request.headers,
            formItems: request.formItems
        )
    }

    static let defaultTodayISO: @Sendable () -> String = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? .current
        let comps = calendar.dateComponents([.year, .month, .day], from: Date())
        return String(
            format: "%04d-%02d-%02d",
            comps.year ?? 2026,
            comps.month ?? 1,
            comps.day ?? 1
        )
    }

    private let accountStateProvider: @Sendable () async -> SchoolAccountState
    private let matchesAccountGeneration: @Sendable (String, Int64) async -> Bool
    private let loginCredentialProvider: @Sendable () async -> SchoolLoginCredential?
    private let sessionStore: TimetableSessionStore
    private let cacheStore: TimetableCacheStore
    private let authenticator: UpcTimetableAuthenticator
    private let transport: CasHTTPTransport
    private let clock: @Sendable () -> Int64
    private let todayISOProvider: @Sendable () -> String

    private var businessEpoch: Int64 = 0
    private var explicitSelection = false
    private var currentUiState = TimetableUiState()

    init(
        accountStateProvider: @escaping @Sendable () async -> SchoolAccountState,
        matchesAccountGeneration: @escaping @Sendable (String, Int64) async -> Bool,
        loginCredentialProvider: @escaping @Sendable () async -> SchoolLoginCredential?,
        sessionStore: TimetableSessionStore = TimetableSessionStore(),
        cacheStore: TimetableCacheStore = TimetableCacheStore(),
        authenticator: UpcTimetableAuthenticator? = nil,
        transport: @escaping CasHTTPTransport = TimetableStore.defaultTransport,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        todayISOProvider: @escaping @Sendable () -> String = TimetableStore.defaultTodayISO
    ) {
        self.accountStateProvider = accountStateProvider
        self.matchesAccountGeneration = matchesAccountGeneration
        self.loginCredentialProvider = loginCredentialProvider
        self.sessionStore = sessionStore
        self.cacheStore = cacheStore
        self.transport = transport
        self.authenticator = authenticator ?? UpcTimetableAuthenticator(transport: transport)
        self.clock = clock
        self.todayISOProvider = todayISOProvider
    }

    func restore() async -> TimetableUiState {
        explicitSelection = false
        let state = await accountStateProvider()
        let epoch = businessEpoch

        switch state.status {
        case .unset:
            currentUiState = TimetableUiState(
                connectionStatus: .unconfigured,
                statusDetail: "请先前往「学校账号」保存统一认证凭据"
            )
            return currentUiState
        case .recoveryRequired:
            currentUiState = TimetableUiState(
                connectionStatus: .recoveryRequired,
                statusDetail: "学校账号加密状态需要重新保存，暂不可同步课表"
            )
            return currentUiState
        case .savedUnverified:
            guard let accountId = state.accountId else { return currentUiState }
            let cache = await cacheStore.load(accountId: accountId)
            guard await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) else {
                return currentUiState
            }
            let terms = cache?.index?.terms ?? []
            let preferredTerm = cache?.index?.currentTermOrNull()
                ?? terms.first
                ?? cache?.weeks.first?.term
            let rawWeek = cache?.index?.currentWeek ?? cache?.weeks.first?.week
            let preferredWeek = rawWeek.map { w in
                if let preferredTerm {
                    return min(max(1, w), preferredTerm.totalWeeks)
                }
                return w
            }
            let cachedWeek = findCachedWeek(cache: cache, term: preferredTerm, week: preferredWeek)
            let todaySummary = computeTodaySummary(cache: cache, isOffline: true)

            currentUiState = TimetableUiState(
                connectionStatus: (cachedWeek != nil || cache?.index != nil) ? .offlineCache : .idle,
                terms: terms,
                selectedTerm: preferredTerm,
                selectedWeek: preferredWeek,
                currentSchoolWeek: cache?.index?.currentWeek,
                weekData: cachedWeek,
                todaySummary: todaySummary,
                isRefreshing: false,
                isOfflineFallback: cachedWeek != nil,
                fetchedAtEpochMillis: cachedWeek?.fetchedAtEpochMillis ?? cache?.cachedAtEpochMillis,
                statusDetail: cachedWeek != nil
                    ? "已加载本机离线课表缓存，点击刷新可从学校同步最新数据"
                    : "已保存学校账号（未验证），点击「同步课表」获取当前教学周"
            )
            return currentUiState
        }
    }

    func refresh() async -> TimetableUiState {
        let state = await accountStateProvider()
        let epoch = businessEpoch
        guard state.status == .savedUnverified, let accountId = state.accountId else {
            return publishAccountBlockedState(state)
        }
        let generation = state.generation
        let existingCache = await cacheStore.load(accountId: accountId)

        let outcome = await ensureIndexAndSession(
            accountId: accountId,
            generation: generation,
            epoch: epoch
        )

        switch outcome {
        case .abortedForAccountChange:
            currentUiState.isRefreshing = false
            return currentUiState
        case let .failed(status, message, interactionURL, interactionReason):
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                currentUiState.isRefreshing = false
                return currentUiState
            }
            return applyFailure(
                cache: existingCache,
                status: status,
                message: message,
                interactionURL: interactionURL,
                interactionReason: interactionReason,
                accountId: accountId,
                generation: generation
            )
        case let .ready(cookies, index, freshlyAuthenticated):
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                currentUiState.isRefreshing = false
                return currentUiState
            }
            let preferredTerm = explicitSelection ? currentUiState.selectedTerm : nil
            let targetTerm = resolveTargetTerm(index: index, preferredTerm: preferredTerm)
            let preferredWeek = explicitSelection ? currentUiState.selectedWeek : nil
            let targetWeek = resolveTargetWeek(index: index, term: targetTerm, preferredWeek: preferredWeek)
            let now = max(1, clock())
            await cacheStore.save(accountId: accountId, index: index, week: nil, nowEpochMillis: now)

            return await fetchAndCommitWeek(
                accountId: accountId,
                generation: generation,
                epoch: epoch,
                initialCookies: cookies,
                index: index,
                term: targetTerm,
                week: targetWeek,
                allowReauth: !freshlyAuthenticated
            )
        }
    }

    func selectWeek(term: TimetableTermInfo, week: Int) async -> TimetableUiState {
        guard (1...term.totalWeeks).contains(week) else { return currentUiState }
        explicitSelection = true
        let state = await accountStateProvider()
        let epoch = businessEpoch
        guard state.status == .savedUnverified, let accountId = state.accountId else {
            return publishAccountBlockedState(state)
        }
        let generation = state.generation
        let cache = await cacheStore.load(accountId: accountId)

        var cookies: [String: String]?
        var freshlyAuthenticated = false
        var indexFromAuth: TimetableIndex?

        if let existing = await sessionStore.load(accountId: accountId, credentialGeneration: generation) {
            cookies = existing.cookies
        } else {
            let ensure = await ensureIndexAndSession(accountId: accountId, generation: generation, epoch: epoch)
            switch ensure {
            case .abortedForAccountChange:
                currentUiState.isRefreshing = false
                return currentUiState
            case let .failed(status, message, interactionURL, interactionReason):
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    currentUiState.isRefreshing = false
                    return currentUiState
                }
                return applyFailure(
                    cache: cache,
                    status: status,
                    message: message,
                    interactionURL: interactionURL,
                    interactionReason: interactionReason,
                    accountId: accountId,
                    generation: generation,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            case let .ready(readyCookies, idx, fresh):
                cookies = readyCookies
                freshlyAuthenticated = fresh
                indexFromAuth = idx
            }
        }

        guard await stillValid(accountId: accountId, generation: generation, epoch: epoch),
              let cookies else {
            currentUiState.isRefreshing = false
            return currentUiState
        }
        let indexForState = indexFromAuth
            ?? cache?.index
            ?? TimetableIndex(
                terms: currentUiState.terms.isEmpty ? [term] : currentUiState.terms,
                currentYear: nil,
                currentTerm: nil,
                currentWeek: nil,
                currentWeekdaysIso: nil,
                currentWeekday: nil
            )
        return await fetchAndCommitWeek(
            accountId: accountId,
            generation: generation,
            epoch: epoch,
            initialCookies: cookies,
            index: indexForState,
            term: term,
            week: week,
            allowReauth: !freshlyAuthenticated
        )
    }

    func adoptWebViewCookies(
        _ cookies: [String: String],
        sessionContext: ControlledWebViewPolicy.SessionContext
    ) async -> TimetableUiState {
        let state = await accountStateProvider()
        let epoch = businessEpoch
        guard sessionContext.isStillValid(for: state),
              state.status == .savedUnverified,
              let accountId = state.accountId else {
            currentUiState.interactionSession = nil
            currentUiState.interactionReason = nil
            currentUiState.isRefreshing = false
            return currentUiState
        }
        let generation = state.generation
        let cache = await cacheStore.load(accountId: accountId)
        currentUiState.interactionSession = nil
        currentUiState.interactionReason = nil

        switch await authenticator.verifySessionCookies(cookies) {
        case let .valid(indexBody):
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            switch TimetableProtocol.parseIndex(indexBody) {
            case let .ok(index):
                let now = max(1, clock())
                await sessionStore.save(
                    TimetableSession(
                        accountId: accountId,
                        credentialGeneration: generation,
                        savedAtEpochMillis: now,
                        cookies: cookies
                    )
                )
                await cacheStore.save(accountId: accountId, index: index, week: nil, nowEpochMillis: now)
                let preferredTerm = explicitSelection ? currentUiState.selectedTerm : nil
                let targetTerm = resolveTargetTerm(index: index, preferredTerm: preferredTerm)
                let preferredWeek = explicitSelection ? currentUiState.selectedWeek : nil
                let targetWeek = resolveTargetWeek(index: index, term: targetTerm, preferredWeek: preferredWeek)
                return await fetchAndCommitWeek(
                    accountId: accountId,
                    generation: generation,
                    epoch: epoch,
                    initialCookies: cookies,
                    index: index,
                    term: targetTerm,
                    week: targetWeek,
                    allowReauth: false
                )
            case .authRequired:
                return applyFailure(
                    cache: cache,
                    status: .interactionRequired,
                    message: "网页会话尚未建立，请重新验证",
                    interactionURL: UpcTimetableAuthEndpoints.loginURL,
                    interactionReason: "网页登录未生效",
                    accountId: accountId,
                    generation: generation
                )
            case let .upstreamError(msg):
                return applyFailure(
                    cache: cache,
                    status: .error,
                    message: msg,
                    accountId: accountId,
                    generation: generation
                )
            }
        case .authRequired:
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            return applyFailure(
                cache: cache,
                status: .interactionRequired,
                message: "交互登录后尚未取得有效的课表会话",
                interactionURL: UpcTimetableAuthEndpoints.loginURL,
                interactionReason: "需要完成学校身份验证",
                accountId: accountId,
                generation: generation
            )
        case let .networkError(msg), let .upstreamError(msg):
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            return applyFailure(
                cache: cache,
                status: .error,
                message: msg,
                accountId: accountId,
                generation: generation
            )
        }
    }

    func dismissInteractionPrompt() -> TimetableUiState {
        currentUiState.interactionSession = nil
        currentUiState.interactionReason = nil
        return currentUiState
    }

    func onAccountInvalidated(reason: AccountInvalidationReason) async -> TimetableUiState {
        businessEpoch += 1
        explicitSelection = false
        await sessionStore.clear()

        switch reason {
        case .accountChangedOrCleared:
            await cacheStore.clear()
            let state = await accountStateProvider()
            return publishAccountBlockedState(state)
        case .credentialsUpdated:
            let state = await accountStateProvider()
            let cache: TimetableOfflineCache? = if let accountId = state.accountId {
                await cacheStore.load(accountId: accountId)
            } else {
                nil
            }
            let terms = cache?.index?.terms ?? []
            let selectedTerm = currentUiState.selectedTerm ?? cache?.index?.currentTermOrNull() ?? terms.first
            let selectedWeek = currentUiState.selectedWeek ?? cache?.index?.currentWeek
            let cachedWeek = findCachedWeek(cache: cache, term: selectedTerm, week: selectedWeek)
            currentUiState = TimetableUiState(
                connectionStatus: (cachedWeek != nil || cache?.index != nil) ? .offlineCache : .idle,
                terms: terms,
                selectedTerm: selectedTerm,
                selectedWeek: selectedWeek,
                currentSchoolWeek: cache?.index?.currentWeek,
                weekData: cachedWeek,
                todaySummary: computeTodaySummary(cache: cache, isOffline: true),
                isRefreshing: false,
                isOfflineFallback: cachedWeek != nil,
                fetchedAtEpochMillis: cachedWeek?.fetchedAtEpochMillis ?? cache?.cachedAtEpochMillis,
                statusDetail: "密码已更新，原学校会话已清除；可点击刷新重新建立会话"
            )
            return currentUiState
        }
    }

    func clearLocalCache() async -> TimetableUiState {
        businessEpoch += 1
        await cacheStore.clear()
        let state = await accountStateProvider()
        guard state.status == .savedUnverified else {
            return publishAccountBlockedState(state)
        }
        currentUiState = TimetableUiState(
            connectionStatus: .idle,
            statusDetail: "已清除本机课表离线缓存"
        )
        return currentUiState
    }

    private func ensureIndexAndSession(
        accountId: String,
        generation: Int64,
        epoch: Int64
    ) async -> EnsureSessionOutcome {
        if let existing = await sessionStore.load(accountId: accountId, credentialGeneration: generation) {
            switch await authenticator.verifySessionCookies(existing.cookies) {
            case let .valid(indexBody):
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return .abortedForAccountChange
                }
                switch TimetableProtocol.parseIndex(indexBody) {
                case let .ok(index):
                    return .ready(cookies: existing.cookies, index: index, freshlyAuthenticated: false)
                case .authRequired:
                    await sessionStore.clear()
                    return await authenticateOnceForIndex(accountId: accountId, generation: generation, epoch: epoch)
                case let .upstreamError(msg):
                    return .failed(status: .error, message: msg)
                }
            case .authRequired:
                await sessionStore.clear()
            case let .networkError(msg), let .upstreamError(msg):
                return .failed(status: .error, message: msg)
            }
        }
        return await authenticateOnceForIndex(accountId: accountId, generation: generation, epoch: epoch)
    }

    private func authenticateOnceForIndex(
        accountId: String,
        generation: Int64,
        epoch: Int64
    ) async -> EnsureSessionOutcome {
        guard await stillValid(accountId: accountId, generation: generation, epoch: epoch),
              let credential = await loginCredentialProvider(),
              credential.accountId == accountId,
              credential.generation == generation else {
            return .abortedForAccountChange
        }

        let authResult = await authenticator.authenticate(credential: credential)
        guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
            return .abortedForAccountChange
        }

        switch authResult {
        case .invalidCredentials:
            await sessionStore.clear()
            return .failed(
                status: .invalidCredentials,
                message: "学号或统一认证密码错误，请前往「学校账号」核对并重新保存"
            )
        case let .interactionRequired(url, reason):
            return .failed(
                status: .interactionRequired,
                message: "学校统一认证要求交互验证：\(reason)",
                interactionURL: url,
                interactionReason: reason
            )
        case let .networkError(msg), let .upstreamError(msg):
            return .failed(status: .error, message: msg)
        case let .success(cookies, indexBody):
            switch TimetableProtocol.parseIndex(indexBody) {
            case let .ok(index):
                let now = max(1, clock())
                await sessionStore.save(
                    TimetableSession(
                        accountId: accountId,
                        credentialGeneration: generation,
                        savedAtEpochMillis: now,
                        cookies: cookies
                    )
                )
                return .ready(cookies: cookies, index: index, freshlyAuthenticated: true)
            case .authRequired:
                return .failed(
                    status: .interactionRequired,
                    message: "登录回调未生效，请尝试通过安全页面完成登录",
                    interactionURL: UpcTimetableAuthEndpoints.loginURL,
                    interactionReason: "需要交互完成认证"
                )
            case let .upstreamError(msg):
                return .failed(status: .error, message: msg)
            }
        }
    }

    private func fetchAndCommitWeek(
        accountId: String,
        generation: Int64,
        epoch: Int64,
        initialCookies: [String: String],
        index: TimetableIndex,
        term: TimetableTermInfo,
        week: Int,
        allowReauth: Bool
    ) async -> TimetableUiState {
        var activeCookies = initialCookies
        var reauthAttempted = !allowReauth

        while true {
            guard let cookieHeader = UpcTimetableAuthenticator.formatCookieHeader(activeCookies) else {
                await sessionStore.clear()
                if !reauthAttempted {
                    reauthAttempted = true
                    let reauth = await authenticateOnceForIndex(
                        accountId: accountId,
                        generation: generation,
                        epoch: epoch
                    )
                    switch reauth {
                    case .abortedForAccountChange:
                        return currentUiState
                    case let .failed(status, msg, url, reason):
                        let cache = await cacheStore.load(accountId: accountId)
                        return applyFailure(
                            cache: cache,
                            status: status,
                            message: msg,
                            interactionURL: url,
                            interactionReason: reason,
                            accountId: accountId,
                            generation: generation,
                            indexOverride: index,
                            selectedTermOverride: term,
                            selectedWeekOverride: week
                        )
                    case let .ready(newCookies, _, _):
                        activeCookies = newCookies
                        continue
                    }
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .error,
                    message: "课表会话失效，请重试",
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            }

            let response: RawHTTPResponse
            do {
                response = try await transport(
                    CasHTTPRequest(
                        url: UpcTimetableAuthEndpoints.weekURL,
                        method: "POST",
                        headers: [
                            "Accept": "application/json, text/plain, */*",
                            "X-Requested-With": "XMLHttpRequest",
                            "Referer": UpcTimetableAuthEndpoints.pageURLString,
                            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                            "Cookie": cookieHeader
                        ],
                        formItems: [
                            ("year", term.year),
                            ("term", term.term),
                            ("week", String(week)),
                            ("type", "2")
                        ]
                    )
                )
            } catch let error as StrictHTTPError {
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return currentUiState
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .error,
                    message: error.localizedDescription,
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            } catch {
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return currentUiState
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .error,
                    message: "网络请求失败，请检查校园网络",
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            }

            if (300...399).contains(response.statusCode) || response.statusCode == 401 {
                await sessionStore.clear()
                if !reauthAttempted {
                    reauthAttempted = true
                    let reauth = await authenticateOnceForIndex(
                        accountId: accountId,
                        generation: generation,
                        epoch: epoch
                    )
                    switch reauth {
                    case .abortedForAccountChange:
                        return currentUiState
                    case let .failed(status, msg, url, reason):
                        let cache = await cacheStore.load(accountId: accountId)
                        return applyFailure(
                            cache: cache,
                            status: status,
                            message: msg,
                            interactionURL: url,
                            interactionReason: reason,
                            accountId: accountId,
                            generation: generation,
                            indexOverride: index,
                            selectedTermOverride: term,
                            selectedWeekOverride: week
                        )
                    case let .ready(newCookies, _, _):
                        activeCookies = newCookies
                        continue
                    }
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .interactionRequired,
                    message: "学校会话已过期，请通过安全页面重新认证",
                    interactionURL: UpcTimetableAuthEndpoints.loginURL,
                    interactionReason: "会话已过期",
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            }

            guard response.statusCode == 200 else {
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return currentUiState
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .error,
                    message: "周课表接口返回 HTTP \(response.statusCode)",
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            }

            let now = max(1, clock())
            switch TimetableProtocol.parseWeek(
                response.body,
                term: term,
                requestedWeek: week,
                fetchedAtEpochMillis: now
            ) {
            case let .ok(parsedWeek):
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return currentUiState
                }
                await cacheStore.save(
                    accountId: accountId,
                    index: index,
                    week: parsedWeek,
                    nowEpochMillis: now
                )
                let refreshedCache = await cacheStore.load(accountId: accountId)
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return currentUiState
                }
                let todaySummary = computeTodaySummary(cache: refreshedCache, isOffline: false)
                currentUiState = TimetableUiState(
                    connectionStatus: .online,
                    terms: index.terms,
                    selectedTerm: term,
                    selectedWeek: week,
                    currentSchoolWeek: index.currentWeek,
                    weekData: parsedWeek,
                    todaySummary: todaySummary,
                    isRefreshing: false,
                    isOfflineFallback: false,
                    fetchedAtEpochMillis: now,
                    statusDetail: "已同步 \(term.label) · 第 \(week) 周"
                )
                return currentUiState
            case .authRequired:
                await sessionStore.clear()
                if !reauthAttempted {
                    reauthAttempted = true
                    let reauth = await authenticateOnceForIndex(
                        accountId: accountId,
                        generation: generation,
                        epoch: epoch
                    )
                    switch reauth {
                    case .abortedForAccountChange:
                        return currentUiState
                    case let .failed(status, msg, url, reason):
                        let cache = await cacheStore.load(accountId: accountId)
                        return applyFailure(
                            cache: cache,
                            status: status,
                            message: msg,
                            interactionURL: url,
                            interactionReason: reason,
                            accountId: accountId,
                            generation: generation,
                            indexOverride: index,
                            selectedTermOverride: term,
                            selectedWeekOverride: week
                        )
                    case let .ready(newCookies, _, _):
                        activeCookies = newCookies
                        continue
                    }
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .interactionRequired,
                    message: "需要重新通过学校认证页面获取有效会话",
                    interactionURL: UpcTimetableAuthEndpoints.loginURL,
                    interactionReason: "会话已失效（10013）",
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            case let .upstreamError(msg):
                guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                    return currentUiState
                }
                let cache = await cacheStore.load(accountId: accountId)
                return applyFailure(
                    cache: cache,
                    status: .error,
                    message: msg,
                    accountId: accountId,
                    generation: generation,
                    indexOverride: index,
                    selectedTermOverride: term,
                    selectedWeekOverride: week
                )
            }
        }
    }

    private func applyFailure(
        cache: TimetableOfflineCache?,
        status: TimetableConnectionStatus,
        message: String,
        interactionURL: URL? = nil,
        interactionReason: String? = nil,
        accountId: String,
        generation: Int64,
        indexOverride: TimetableIndex? = nil,
        selectedTermOverride: TimetableTermInfo? = nil,
        selectedWeekOverride: Int? = nil
    ) -> TimetableUiState {
        let effectiveIndex = indexOverride ?? cache?.index
        let terms = effectiveIndex?.terms ?? currentUiState.terms
        let selectedTerm = selectedTermOverride
            ?? currentUiState.selectedTerm
            ?? effectiveIndex?.currentTermOrNull()
            ?? terms.first
        let selectedWeek = selectedWeekOverride
            ?? currentUiState.selectedWeek
            ?? effectiveIndex?.currentWeek
            ?? (selectedTerm != nil ? 1 : nil)
        let cachedWeek = findCachedWeek(cache: cache, term: selectedTerm, week: selectedWeek)
        let hasCachedWeek = cachedWeek != nil

        let interactionSession: ControlledWebViewPolicy.SessionContext? =
            if status == .interactionRequired, let interactionURL {
                ControlledWebViewPolicy.SessionContext(
                    accountId: accountId,
                    generation: generation,
                    entryURL: interactionURL
                )
            } else {
                nil
            }

        let resolvedStatus: TimetableConnectionStatus = switch status {
        case .interactionRequired: .interactionRequired
        case .invalidCredentials: .invalidCredentials
        default: hasCachedWeek ? .offlineCache : .error
        }

        currentUiState = TimetableUiState(
            connectionStatus: resolvedStatus,
            terms: terms,
            selectedTerm: selectedTerm,
            selectedWeek: selectedWeek,
            currentSchoolWeek: effectiveIndex?.currentWeek,
            weekData: cachedWeek,
            todaySummary: computeTodaySummary(cache: cache, isOffline: true),
            isRefreshing: false,
            isOfflineFallback: hasCachedWeek,
            fetchedAtEpochMillis: cachedWeek?.fetchedAtEpochMillis ?? cache?.cachedAtEpochMillis,
            statusDetail: hasCachedWeek ? "当前显示本机缓存" : nil,
            errorMessage: message,
            interactionSession: interactionSession,
            interactionReason: interactionReason
        )
        return currentUiState
    }

    private func publishAccountBlockedState(_ state: SchoolAccountState) -> TimetableUiState {
        switch state.status {
        case .unset:
            currentUiState = TimetableUiState(
                connectionStatus: .unconfigured,
                statusDetail: "请先前往「学校账号」配置统一认证凭据"
            )
        case .recoveryRequired:
            currentUiState = TimetableUiState(
                connectionStatus: .recoveryRequired,
                statusDetail: "学校账号加密状态异常，请重新保存后再同步课表"
            )
        case .savedUnverified:
            currentUiState = TimetableUiState(
                connectionStatus: .idle,
                statusDetail: "点击「同步课表」获取当前教学周"
            )
        }
        return currentUiState
    }

    private func stillValid(accountId: String, generation: Int64, epoch: Int64) async -> Bool {
        await matchesAccountGeneration(accountId, generation) && businessEpoch == epoch
    }

    private func resolveTargetTerm(
        index: TimetableIndex,
        preferredTerm: TimetableTermInfo?
    ) -> TimetableTermInfo {
        if let preferredTerm, let matched = index.findTerm(year: preferredTerm.year, term: preferredTerm.term) {
            return matched
        }
        return index.currentTermOrNull() ?? index.terms[0]
    }

    private func resolveTargetWeek(
        index: TimetableIndex,
        term: TimetableTermInfo,
        preferredWeek: Int?
    ) -> Int {
        let isCurrentTerm = index.currentYear == term.year && index.currentTerm == term.term
        let candidate: Int = if let preferredWeek, (!isCurrentTerm || preferredWeek != 1 || index.currentWeek == nil) {
            preferredWeek
        } else if isCurrentTerm, let currentWeek = index.currentWeek {
            currentWeek
        } else if let preferredWeek {
            preferredWeek
        } else {
            1
        }
        return min(max(1, candidate), term.totalWeeks)
    }

    private func findCachedWeek(
        cache: TimetableOfflineCache?,
        term: TimetableTermInfo?,
        week: Int?
    ) -> TimetableWeek? {
        guard let cache, let term, let week else { return nil }
        return cache.weeks.first {
            $0.term.year == term.year && $0.term.term == term.term && $0.week == week
        }
    }

    private func computeTodaySummary(
        cache: TimetableOfflineCache?,
        isOffline: Bool
    ) -> TodayCourseSummary? {
        guard let cache else { return nil }
        let todayIso = todayISOProvider()
        guard let matchedWeek = cache.weeks
            .sorted(by: { $0.fetchedAtEpochMillis > $1.fetchedAtEpochMillis })
            .first(where: { $0.datesIso.contains(todayIso) }) else {
            return nil
        }
        let day = matchedWeek.days.first { $0.dateIso == todayIso }
        return TodayCourseSummary(
            dateIso: todayIso,
            weekNumber: matchedWeek.week,
            courses: day?.meetings ?? [],
            isOfflineCache: isOffline,
            fetchedAtEpochMillis: matchedWeek.fetchedAtEpochMillis
        )
    }
}
