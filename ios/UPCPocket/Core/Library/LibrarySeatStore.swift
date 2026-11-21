import Foundation

actor LibrarySeatStore {
    private struct LibseatInMemorySession: Sendable {
        let token: String
        let user: LibraryUser
        let accountId: String
        let generation: Int64
        let epoch: Int64
    }

    private struct ChaoxingInMemorySession: Sendable {
        let cookieHeader: String
        let sno: String
        let accountId: String
        let generation: Int64
        let epoch: Int64
    }

    static let defaultTransport: CasHTTPTransport = { request in
        try await StrictHTTPClient.shared.executeRaw(
            url: request.url,
            method: request.method,
            headers: request.headers,
            formItems: request.formItems
        )
    }

    static let defaultDateOptions: @Sendable () -> [String] = {
        var calendar = Calendar(identifier: .gregorian)
        let shanghai = TimeZone(identifier: "Asia/Shanghai") ?? .current
        calendar.timeZone = shanghai
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = shanghai
        formatter.dateFormat = "yyyy-MM-dd"
        let now = Date()
        return (0...2).compactMap { offset in
            calendar.date(byAdding: .day, value: offset, to: now).map { formatter.string(from: $0) }
        }
    }

    private let accountStateProvider: @Sendable () async -> SchoolAccountState
    private let matchesAccountGeneration: @Sendable (String, Int64) async -> Bool
    private let loginCredentialProvider: @Sendable () async -> SchoolLoginCredential?
    private let transport: CasHTTPTransport
    private let libseatAuthenticator: UpcLibraryAuthenticator
    private let chaoxingAuthenticator: UpcChaoxingAuthenticator
    private let dateOptionsProvider: @Sendable () -> [String]
    private let uuidProvider: @Sendable () -> String
    private let clock: @Sendable () -> Int64

    private var businessEpoch: Int64 = 0
    private var libseatSession: LibseatInMemorySession?
    private var chaoxingSession: ChaoxingInMemorySession?
    private var userSelectedSystem = false
    private var currentUiState = LibrarySeatUiState()

    init(
        accountStateProvider: @escaping @Sendable () async -> SchoolAccountState,
        matchesAccountGeneration: @escaping @Sendable (String, Int64) async -> Bool,
        loginCredentialProvider: @escaping @Sendable () async -> SchoolLoginCredential?,
        transport: @escaping CasHTTPTransport = LibrarySeatStore.defaultTransport,
        dateOptionsProvider: @escaping @Sendable () -> [String] = LibrarySeatStore.defaultDateOptions,
        uuidProvider: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.accountStateProvider = accountStateProvider
        self.matchesAccountGeneration = matchesAccountGeneration
        self.loginCredentialProvider = loginCredentialProvider
        self.transport = transport
        self.libseatAuthenticator = UpcLibraryAuthenticator(transport: transport)
        self.chaoxingAuthenticator = UpcChaoxingAuthenticator(transport: transport)
        self.dateOptionsProvider = dateOptionsProvider
        self.uuidProvider = uuidProvider
        self.clock = clock
    }

    func syncAccountStatusAndCampus(_ preferredCampus: CampusPreference) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        if !userSelectedSystem && currentUiState.connectionStatus != .ready {
            currentUiState.activeTab = LibrarySystemTab.defaultForCampus(preferredCampus)
        }
        let dates = dateOptionsProvider()
        currentUiState.chaoxingAvailableDates = dates
        if !dates.contains(currentUiState.chaoxingSelectedDate), let first = dates.first {
            currentUiState.chaoxingSelectedDate = first
        }
        switch state.status {
        case .unset:
            currentUiState.connectionStatus = .unconfigured
        case .recoveryRequired:
            currentUiState.connectionStatus = .recoveryRequired
        case .savedUnverified:
            if currentUiState.connectionStatus == .unconfigured || currentUiState.connectionStatus == .recoveryRequired {
                currentUiState.connectionStatus = .idle
            }
        }
        return currentUiState
    }

    func selectSystem(_ tab: LibrarySystemTab) async -> LibrarySeatUiState {
        userSelectedSystem = true
        let changed = currentUiState.activeTab != tab
        currentUiState.activeTab = tab
        currentUiState.errorMessage = nil
        if currentUiState.interactionRequest?.system != tab {
            currentUiState.interactionRequest = nil
        }
        if changed && tab != .libseat {
            currentUiState.chaoxingSelectedRoom = nil
            currentUiState.chaoxingSelectedRoomDetail = nil
            currentUiState.chaoxingSelectedSeatNum = nil
            currentUiState.chaoxingSelectedSeatUsedTimes = []
            currentUiState.chaoxingUsedSeatNums = []
        }

        let needsInitialLoad: Bool = switch tab {
        case .libseat: currentUiState.libseatRooms.isEmpty
        case .chaoxingSeat, .chaoxingSeatEngine: true
        }
        let state = await accountStateProvider()
        if needsInitialLoad && state.status == .savedUnverified {
            return await refreshActiveSystem()
        }
        return currentUiState
    }

    func refreshActiveSystem() async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        let epoch = businessEpoch
        switch state.status {
        case .unset:
            currentUiState.connectionStatus = .unconfigured
            currentUiState.isLoading = false
            currentUiState.errorMessage = nil
            currentUiState.statusMessage = "请先在「学校账号」配置统一认证凭据"
            currentUiState.interactionRequest = nil
            return currentUiState
        case .recoveryRequired:
            currentUiState.connectionStatus = .recoveryRequired
            currentUiState.isLoading = false
            currentUiState.errorMessage = "学校账号加密状态异常，请重新保存后再试"
            currentUiState.interactionRequest = nil
            return currentUiState
        case .savedUnverified:
            break
        }

        guard let accountId = state.accountId else { return currentUiState }
        let generation = state.generation

        switch currentUiState.activeTab {
        case .libseat:
            await refreshLibseat(accountId: accountId, generation: generation, epoch: epoch)
        case .chaoxingSeat:
            await refreshChaoxing(kind: .seat, accountId: accountId, generation: generation, epoch: epoch)
        case .chaoxingSeatEngine:
            await refreshChaoxing(kind: .seatEngine, accountId: accountId, generation: generation, epoch: epoch)
        }
        return currentUiState
    }

    func selectLibseatDate(_ date: String) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let building = currentUiState.libseatSelectedBuilding else {
            return currentUiState
        }
        let generation = state.generation
        let epoch = businessEpoch

        currentUiState.libseatSelectedDate = date
        currentUiState.libseatSelectedRoom = nil
        currentUiState.libseatSelectedRoomLayout = nil
        currentUiState.errorMessage = nil
        currentUiState.isLoading = true

        do {
            guard let floors = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatFloors(token: token, buildingId: building.id)
            }) else { return currentUiState }

            let floorMap = Dictionary(uniqueKeysWithValues: floors.map { ($0.floorNum, $0.floorName) })
            guard let rooms = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatRooms(token: token, building: building, date: date, floorMap: floorMap)
            }) else { return currentUiState }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoading = false
            currentUiState.connectionStatus = .ready
            currentUiState.libseatFloors = floors
            currentUiState.libseatRooms = rooms
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoading = false
            currentUiState.connectionStatus = .error
            currentUiState.errorMessage = error.localizedDescription
        }
        return currentUiState
    }

    func selectLibseatBuilding(_ building: LibraryBuilding) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let date = currentUiState.libseatSelectedDate ?? currentUiState.libseatDates.first else {
            return currentUiState
        }
        let generation = state.generation
        let epoch = businessEpoch

        currentUiState.libseatSelectedBuilding = building
        currentUiState.libseatSelectedFloor = nil
        currentUiState.libseatSelectedRoom = nil
        currentUiState.libseatSelectedRoomLayout = nil
        currentUiState.errorMessage = nil
        currentUiState.isLoading = true

        do {
            guard let floors = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatFloors(token: token, buildingId: building.id)
            }) else { return currentUiState }

            let floorMap = Dictionary(uniqueKeysWithValues: floors.map { ($0.floorNum, $0.floorName) })
            guard let rooms = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatRooms(token: token, building: building, date: date, floorMap: floorMap)
            }) else { return currentUiState }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoading = false
            currentUiState.connectionStatus = .ready
            currentUiState.libseatFloors = floors
            currentUiState.libseatRooms = rooms
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoading = false
            currentUiState.connectionStatus = .error
            currentUiState.errorMessage = error.localizedDescription
        }
        return currentUiState
    }

    func selectLibseatFloor(_ floor: LibraryFloor?) -> LibrarySeatUiState {
        currentUiState.libseatSelectedFloor = floor
        return currentUiState
    }

    func loadLibseatRoomLayout(_ room: LibraryRoom) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let date = currentUiState.libseatSelectedDate else {
            return currentUiState
        }
        let generation = state.generation
        let epoch = businessEpoch

        currentUiState.libseatSelectedRoom = room
        currentUiState.libseatSelectedRoomLayout = nil
        currentUiState.isLoadingRoomDetail = true
        currentUiState.errorMessage = nil

        do {
            guard let layout = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatLayout(token: token, roomId: room.roomId, date: date)
            }) else { return currentUiState }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.libseatSelectedRoomLayout = layout
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.errorMessage = error.localizedDescription
        }
        return currentUiState
    }

    func dismissLibseatRoomDetail() -> LibrarySeatUiState {
        currentUiState.libseatSelectedRoom = nil
        currentUiState.libseatSelectedRoomLayout = nil
        currentUiState.isLoadingRoomDetail = false
        return currentUiState
    }

    func selectChaoxingDate(_ date: String) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let kind = currentUiState.activeTab.chaoxingKind else {
            return currentUiState
        }
        currentUiState.chaoxingSelectedDate = date
        currentUiState.chaoxingSelectedRoom = nil
        currentUiState.chaoxingSelectedRoomDetail = nil
        currentUiState.chaoxingSelectedSeatNum = nil
        currentUiState.chaoxingSelectedSeatUsedTimes = []
        currentUiState.chaoxingUsedSeatNums = []
        currentUiState.errorMessage = nil
        await refreshChaoxing(kind: kind, accountId: accountId, generation: state.generation, epoch: businessEpoch)
        return currentUiState
    }

    func loadChaoxingRoomDetail(_ room: ChaoxingSeatRoom) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let kind = currentUiState.activeTab.chaoxingKind else {
            return currentUiState
        }
        let generation = state.generation
        let epoch = businessEpoch
        let date = currentUiState.chaoxingSelectedDate.isEmpty
            ? (dateOptionsProvider().first ?? "2026-11-21")
            : currentUiState.chaoxingSelectedDate

        currentUiState.chaoxingSelectedRoom = room
        currentUiState.chaoxingSelectedRoomDetail = nil
        currentUiState.chaoxingSelectedSeatNum = nil
        currentUiState.chaoxingSelectedSeatUsedTimes = []
        currentUiState.chaoxingUsedSeatNums = []
        currentUiState.isLoadingRoomDetail = true
        currentUiState.errorMessage = nil

        do {
            guard let detail = try await withChaoxingCookie(accountId: accountId, generation: generation, epoch: epoch, operation: { cookie in
                try await fetchChaoxingRoomDetail(kind: kind, cookieHeader: cookie, roomId: room.id, date: date)
            }) else { return currentUiState }

            let defaultEnd = min(detail.startMinute + 240, detail.endMinute)
            guard let usedNums = try await withChaoxingCookie(accountId: accountId, generation: generation, epoch: epoch, operation: { cookie in
                try await fetchChaoxingUsedSeatNums(
                    kind: kind,
                    cookieHeader: cookie,
                    roomId: room.id,
                    startMinute: detail.startMinute,
                    endMinute: defaultEnd,
                    date: date
                )
            }) else { return currentUiState }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.chaoxingSelectedRoomDetail = detail
            currentUiState.chaoxingSelectedStartMinute = detail.startMinute
            currentUiState.chaoxingSelectedEndMinute = defaultEnd
            currentUiState.chaoxingUsedSeatNums = usedNums
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.errorMessage = error.localizedDescription
        }
        return currentUiState
    }

    func updateChaoxingWindow(startMinute: Int, endMinute: Int) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let kind = currentUiState.activeTab.chaoxingKind,
              let room = currentUiState.chaoxingSelectedRoom else {
            return currentUiState
        }
        guard endMinute > startMinute else {
            currentUiState.errorMessage = "结束时间必须晚于开始时间"
            return currentUiState
        }
        let generation = state.generation
        let epoch = businessEpoch
        let date = currentUiState.chaoxingSelectedDate

        currentUiState.chaoxingSelectedStartMinute = startMinute
        currentUiState.chaoxingSelectedEndMinute = endMinute
        currentUiState.isLoadingRoomDetail = true
        currentUiState.errorMessage = nil

        do {
            guard let usedNums = try await withChaoxingCookie(accountId: accountId, generation: generation, epoch: epoch, operation: { cookie in
                try await fetchChaoxingUsedSeatNums(
                    kind: kind,
                    cookieHeader: cookie,
                    roomId: room.id,
                    startMinute: startMinute,
                    endMinute: endMinute,
                    date: date
                )
            }) else { return currentUiState }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.chaoxingUsedSeatNums = usedNums
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.errorMessage = error.localizedDescription
        }
        return currentUiState
    }

    func loadChaoxingSeatUsedTimes(seatNum: String) async -> LibrarySeatUiState {
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              let kind = currentUiState.activeTab.chaoxingKind,
              let room = currentUiState.chaoxingSelectedRoom else {
            return currentUiState
        }
        let generation = state.generation
        let epoch = businessEpoch
        let date = currentUiState.chaoxingSelectedDate

        currentUiState.chaoxingSelectedSeatNum = seatNum
        currentUiState.chaoxingSelectedSeatUsedTimes = []
        currentUiState.isLoadingRoomDetail = true

        do {
            guard let intervals = try await withChaoxingCookie(accountId: accountId, generation: generation, epoch: epoch, operation: { cookie in
                try await fetchChaoxingUsedTimes(kind: kind, cookieHeader: cookie, roomId: room.id, seatNum: seatNum, date: date)
            }) else { return currentUiState }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.chaoxingSelectedSeatUsedTimes = intervals
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
                return currentUiState
            }
            currentUiState.isLoadingRoomDetail = false
            currentUiState.errorMessage = error.localizedDescription
        }
        return currentUiState
    }

    func dismissChaoxingRoomDetail() -> LibrarySeatUiState {
        currentUiState.chaoxingSelectedRoom = nil
        currentUiState.chaoxingSelectedRoomDetail = nil
        currentUiState.chaoxingSelectedSeatNum = nil
        currentUiState.chaoxingSelectedSeatUsedTimes = []
        currentUiState.chaoxingUsedSeatNums = []
        currentUiState.isLoadingRoomDetail = false
        return currentUiState
    }

    func adoptLibseatWebViewToken(
        _ url: URL,
        sessionContext: ControlledWebViewPolicy.SessionContext
    ) async -> LibrarySeatUiState {
        let epoch = businessEpoch
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              sessionContext.isStillValid(for: state),
              await matchesAccountGeneration(accountId, state.generation) else {
            return currentUiState
        }

        guard let appToken = LibrarySeatProtocol.extractTokenFromURL(url.absoluteString),
              !appToken.isEmpty else {
            currentUiState.connectionStatus = .error
            currentUiState.errorMessage = "未从图书馆页面检测到有效登录凭据，请重试"
            currentUiState.interactionRequest = nil
            return currentUiState
        }

        guard let credential = await loginCredentialProvider() else {
            return currentUiState
        }

        currentUiState.isLoading = true
        currentUiState.errorMessage = nil
        currentUiState.interactionRequest = nil

        do {
            let seatToken = try await exchangeLibseatAppToken(appToken: appToken)
            let user = try await fetchLibseatUser(seatToken: seatToken)
            guard user.username.trimmingCharacters(in: .whitespacesAndNewlines)
                == credential.loginName.trimmingCharacters(in: .whitespacesAndNewlines) else {
                if await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) {
                    currentUiState.isLoading = false
                    currentUiState.connectionStatus = .error
                    currentUiState.errorMessage = "网页登录学号与当前学校账号不一致，已拒绝加载"
                }
                return currentUiState
            }
            guard await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) else {
                return currentUiState
            }
            libseatSession = LibseatInMemorySession(
                token: seatToken,
                user: user,
                accountId: accountId,
                generation: state.generation,
                epoch: epoch
            )
            await refreshLibseat(accountId: accountId, generation: state.generation, epoch: epoch)
        } catch {
            if await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) {
                currentUiState.isLoading = false
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = error.localizedDescription
            }
        }
        return currentUiState
    }

    func adoptChaoxingWebViewCookies(
        _ cookies: [String: String],
        sessionContext: ControlledWebViewPolicy.SessionContext
    ) async -> LibrarySeatUiState {
        let epoch = businessEpoch
        let state = await accountStateProvider()
        guard state.status == .savedUnverified,
              let accountId = state.accountId,
              sessionContext.isStillValid(for: state),
              await matchesAccountGeneration(accountId, state.generation) else {
            return currentUiState
        }

        guard let cookieHeader = UpcTimetableAuthenticator.formatCookieHeader(cookies) else {
            currentUiState.connectionStatus = .error
            currentUiState.errorMessage = "未读取到有效的超星会话 Cookie，请重试"
            currentUiState.interactionRequest = nil
            return currentUiState
        }

        guard let credential = await loginCredentialProvider() else {
            return currentUiState
        }

        currentUiState.isLoading = true
        currentUiState.errorMessage = nil
        currentUiState.interactionRequest = nil

        do {
            let html = try await readChaoxing(cookieHeader: cookieHeader, url: UpcChaoxingAuthEndpoints.seatEntryURL)
            let user = try ChaoxingProtocol.parseIdentity(html)
            guard user.sno.trimmingCharacters(in: .whitespacesAndNewlines)
                == credential.loginName.trimmingCharacters(in: .whitespacesAndNewlines) else {
                if await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) {
                    currentUiState.isLoading = false
                    currentUiState.connectionStatus = .error
                    currentUiState.errorMessage = "网页登录学号与当前学校账号不一致，已拒绝加载"
                }
                return currentUiState
            }
            guard await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) else {
                return currentUiState
            }
            chaoxingSession = ChaoxingInMemorySession(
                cookieHeader: cookieHeader,
                sno: user.sno,
                accountId: accountId,
                generation: state.generation,
                epoch: epoch
            )
            let kind = currentUiState.activeTab.chaoxingKind ?? .seat
            await refreshChaoxing(kind: kind, accountId: accountId, generation: state.generation, epoch: epoch)
        } catch {
            if await stillValid(accountId: accountId, generation: state.generation, epoch: epoch) {
                currentUiState.isLoading = false
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = error.localizedDescription
            }
        }
        return currentUiState
    }

    func dismissInteractionPrompt() -> LibrarySeatUiState {
        currentUiState.interactionRequest = nil
        if currentUiState.connectionStatus == .interactionRequired {
            currentUiState.connectionStatus = .idle
        }
        return currentUiState
    }

    func clearInMemorySessions() async -> LibrarySeatUiState {
        businessEpoch += 1
        libseatSession = nil
        chaoxingSession = nil
        let state = await accountStateProvider()
        let activeTab = currentUiState.activeTab
        let dates = dateOptionsProvider()
        let status: LibraryConnectionStatus = switch state.status {
        case .unset: .unconfigured
        case .recoveryRequired: .recoveryRequired
        case .savedUnverified: .idle
        }
        currentUiState = LibrarySeatUiState(
            activeTab: activeTab,
            connectionStatus: status,
            statusMessage: "已清空图书馆内存会话",
            chaoxingAvailableDates: dates,
            chaoxingSelectedDate: dates.first ?? ""
        )
        return currentUiState
    }

    func onAccountInvalidated(reason: AccountInvalidationReason) async -> LibrarySeatUiState {
        businessEpoch += 1
        libseatSession = nil
        chaoxingSession = nil
        let state = await accountStateProvider()
        let activeTab = currentUiState.activeTab
        let dates = dateOptionsProvider()
        let msg = switch reason {
        case .accountChangedOrCleared: "学校账号已切换或清空，图书馆内存会话已清除"
        case .credentialsUpdated: "账号凭据已更新，请重新拉取图书馆数据"
        }
        let status: LibraryConnectionStatus = switch state.status {
        case .unset: .unconfigured
        case .recoveryRequired: .recoveryRequired
        case .savedUnverified: .idle
        }
        currentUiState = LibrarySeatUiState(
            activeTab: activeTab,
            connectionStatus: status,
            statusMessage: msg,
            chaoxingAvailableDates: dates,
            chaoxingSelectedDate: dates.first ?? ""
        )
        return currentUiState
    }

    private func refreshLibseat(accountId: String, generation: Int64, epoch: Int64) async {
        currentUiState.isLoading = true
        currentUiState.errorMessage = nil
        currentUiState.statusMessage = nil

        do {
            guard let filters = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatFilters(token: token)
            }) else { return }
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else { return }

            let date = currentUiState.libseatSelectedDate.flatMap { filters.dates.contains($0) ? $0 : nil } ?? filters.dates[0]
            let building = currentUiState.libseatSelectedBuilding.flatMap { prev in
                filters.buildings.first(where: { $0.id == prev.id })
            } ?? filters.buildings[0]

            guard let floors = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatFloors(token: token, buildingId: building.id)
            }) else { return }
            let floorMap = Dictionary(uniqueKeysWithValues: floors.map { ($0.floorNum, $0.floorName) })

            guard let rooms = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatRooms(token: token, building: building, date: date, floorMap: floorMap)
            }) else { return }

            guard let currentRes = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatCurrentReservations(token: token)
            }) else { return }

            guard let historyRes = try await withLibseatToken(accountId: accountId, generation: generation, epoch: epoch, operation: { token in
                try await fetchLibseatHistoryReservations(token: token)
            }) else { return }

            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else { return }
            let matchedFloor = currentUiState.libseatSelectedFloor.flatMap { prev in
                floors.first(where: { $0.floorNum == prev.floorNum })
            }

            currentUiState.connectionStatus = .ready
            currentUiState.isLoading = false
            currentUiState.errorMessage = nil
            currentUiState.statusMessage = "已同步 \(building.name)（只读）"
            currentUiState.libseatDates = filters.dates
            currentUiState.libseatSelectedDate = date
            currentUiState.libseatBuildings = filters.buildings
            currentUiState.libseatSelectedBuilding = building
            currentUiState.libseatFloors = floors
            currentUiState.libseatSelectedFloor = matchedFloor
            currentUiState.libseatRooms = rooms
            currentUiState.libseatSelectedRoom = nil
            currentUiState.libseatSelectedRoomLayout = nil
            currentUiState.libseatCurrentReservations = currentRes
            currentUiState.libseatHistoryReservations = historyRes
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else { return }
            currentUiState.connectionStatus = .error
            currentUiState.isLoading = false
            currentUiState.errorMessage = error.localizedDescription
        }
    }

    private func refreshChaoxing(
        kind: ChaoxingSeatKind,
        accountId: String,
        generation: Int64,
        epoch: Int64
    ) async {
        let dates = dateOptionsProvider()
        let date = dates.contains(currentUiState.chaoxingSelectedDate)
            ? currentUiState.chaoxingSelectedDate
            : (dates.first ?? "2026-11-21")
        currentUiState.chaoxingAvailableDates = dates
        currentUiState.chaoxingSelectedDate = date
        currentUiState.isLoading = true
        currentUiState.errorMessage = nil
        currentUiState.statusMessage = nil

        do {
            guard let page = try await withChaoxingCookie(accountId: accountId, generation: generation, epoch: epoch, operation: { cookie in
                try await fetchChaoxingRooms(kind: kind, cookieHeader: cookie, date: date)
            }) else { return }
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else { return }

            currentUiState.connectionStatus = .ready
            currentUiState.isLoading = false
            currentUiState.errorMessage = nil
            currentUiState.statusMessage = "已同步 \(kind.campusLabel) 房间列表（只读）"
            currentUiState.chaoxingRooms = page.rooms
            currentUiState.chaoxingSelectedRoom = nil
            currentUiState.chaoxingSelectedRoomDetail = nil
            currentUiState.chaoxingSelectedSeatNum = nil
            currentUiState.chaoxingSelectedSeatUsedTimes = []
            currentUiState.chaoxingUsedSeatNums = []
        } catch {
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else { return }
            currentUiState.connectionStatus = .error
            currentUiState.isLoading = false
            currentUiState.errorMessage = error.localizedDescription
        }
    }

    private func withLibseatToken<T>(
        accountId: String,
        generation: Int64,
        epoch: Int64,
        operation: (String) async throws -> T
    ) async throws -> T? {
        let session: LibseatInMemorySession
        if let existing = libseatSession,
           existing.accountId == accountId,
           existing.generation == generation,
           existing.epoch == epoch {
            session = existing
        } else {
            guard let authed = await authenticateLibseat(accountId: accountId, generation: generation, epoch: epoch) else {
                return nil
            }
            session = authed
        }

        do {
            return try await operation(session.token)
        } catch LibrarySeatError.sessionExpired {
            libseatSession = nil
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch),
                  let refreshed = await authenticateLibseat(accountId: accountId, generation: generation, epoch: epoch) else {
                return nil
            }
            return try await operation(refreshed.token)
        }
    }

    private func authenticateLibseat(accountId: String, generation: Int64, epoch: Int64) async -> LibseatInMemorySession? {
        currentUiState.connectionStatus = .authenticating
        guard let credential = await loginCredentialProvider(),
              credential.accountId == accountId,
              credential.generation == generation else {
            return nil
        }
        let result = await libseatAuthenticator.authenticate(credential: credential)
        guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
            return nil
        }
        switch result {
        case let .success(seatToken, user):
            let created = LibseatInMemorySession(
                token: seatToken,
                user: LibraryUser(id: user.id, username: user.username, name: user.name),
                accountId: accountId,
                generation: generation,
                epoch: epoch
            )
            libseatSession = created
            return created
        case let .failure(reason, interactionURL, message):
            currentUiState.isLoading = false
            switch reason {
            case .invalidCredentials:
                currentUiState.connectionStatus = .invalidCredentials
                currentUiState.errorMessage = "学校统一认证用户名或密码错误，请核对后重试"
            case .interactionRequired:
                let url = interactionURL ?? UpcLibraryAuthEndpoints.entryURL
                currentUiState.connectionStatus = .interactionRequired
                currentUiState.errorMessage = message ?? "需要完成学校安全验证后获取图书馆会话"
                currentUiState.interactionRequest = LibraryInteractionRequest(
                    system: .libseat,
                    sessionContext: ControlledWebViewPolicy.SessionContext(
                        accountId: accountId,
                        generation: generation,
                        entryURL: url
                    ),
                    scopeHosts: ControlledWebViewPolicy.libraryScopeHosts,
                    targetHost: UpcLibraryAuthEndpoints.libraryHost,
                    targetCookieDomains: [UpcLibraryAuthEndpoints.libraryHost],
                    completionPathPrefixes: ["/libseat", "/remote/static/casAuth/"],
                    usesURLTokenCapture: true,
                    reason: message ?? "需要完成学校安全验证"
                )
            case .identityMismatch:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = "图书馆返回的学号与本机配置不一致，已终止会话"
            case .networkUnavailable:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = message ?? "无法连接图书馆座位系统，请检查网络"
            case .tlsError:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = "安全证书校验失败，已终止访问"
            case .upstreamError, .protocolError, .accessDenied:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = message ?? "图书馆认证失败，请稍后重试"
            }
            return nil
        }
    }

    private func withChaoxingCookie<T>(
        accountId: String,
        generation: Int64,
        epoch: Int64,
        operation: (String) async throws -> T
    ) async throws -> T? {
        let session: ChaoxingInMemorySession
        if let existing = chaoxingSession,
           existing.accountId == accountId,
           existing.generation == generation,
           existing.epoch == epoch {
            session = existing
        } else {
            guard let authed = await authenticateChaoxing(accountId: accountId, generation: generation, epoch: epoch) else {
                return nil
            }
            session = authed
        }

        do {
            return try await operation(session.cookieHeader)
        } catch ChaoxingProtocolError.sessionExpired {
            chaoxingSession = nil
            guard await stillValid(accountId: accountId, generation: generation, epoch: epoch),
                  let refreshed = await authenticateChaoxing(accountId: accountId, generation: generation, epoch: epoch) else {
                return nil
            }
            return try await operation(refreshed.cookieHeader)
        }
    }

    private func authenticateChaoxing(accountId: String, generation: Int64, epoch: Int64) async -> ChaoxingInMemorySession? {
        currentUiState.connectionStatus = .authenticating
        guard let credential = await loginCredentialProvider(),
              credential.accountId == accountId,
              credential.generation == generation else {
            return nil
        }
        let result = await chaoxingAuthenticator.authenticate(credential: credential)
        guard await stillValid(accountId: accountId, generation: generation, epoch: epoch) else {
            return nil
        }
        switch result {
        case let .success(cookieHeader, indexHTML):
            guard let parsedUser = try? ChaoxingProtocol.parseIdentity(indexHTML) else {
                currentUiState.isLoading = false
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = "未识别到超星用户学号信息"
                return nil
            }
            guard parsedUser.sno.trimmingCharacters(in: .whitespacesAndNewlines)
                == credential.loginName.trimmingCharacters(in: .whitespacesAndNewlines) else {
                currentUiState.isLoading = false
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = "超星返回的学号与本机配置不一致，已拒绝加载"
                return nil
            }
            let created = ChaoxingInMemorySession(
                cookieHeader: cookieHeader,
                sno: parsedUser.sno,
                accountId: accountId,
                generation: generation,
                epoch: epoch
            )
            chaoxingSession = created
            return created
        case let .failure(reason, _, message):
            currentUiState.isLoading = false
            switch reason {
            case .invalidCredentials:
                currentUiState.connectionStatus = .invalidCredentials
                currentUiState.errorMessage = "学校统一认证用户名或密码错误，请核对后重试"
            case .interactionRequired:
                let currentTab = currentUiState.activeTab
                currentUiState.connectionStatus = .interactionRequired
                currentUiState.errorMessage = message ?? "需要完成学校网页安全验证"
                currentUiState.interactionRequest = LibraryInteractionRequest(
                    system: currentTab,
                    sessionContext: ControlledWebViewPolicy.SessionContext(
                        accountId: accountId,
                        generation: generation,
                        entryURL: currentTab.officialURL
                    ),
                    scopeHosts: ControlledWebViewPolicy.chaoxingScopeHosts,
                    targetHost: UpcChaoxingAuthEndpoints.officeHost,
                    targetCookieDomains: [
                        UpcChaoxingAuthEndpoints.officeHost,
                        UpcChaoxingAuthEndpoints.chaoxingCookieDomain
                    ],
                    completionPathPrefixes: [
                        "/front/third/apps/seat/",
                        "/front/third/apps/seatengine/",
                        "/front/apps/seat/"
                    ],
                    usesURLTokenCapture: false,
                    reason: message ?? "需要完成学校安全验证"
                )
            case .networkUnavailable:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = message ?? "无法连接超星座位系统，请检查网络"
            case .tlsError:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = "安全证书校验失败，已终止访问"
            case .upstreamError, .protocolError, .accessDenied:
                currentUiState.connectionStatus = .error
                currentUiState.errorMessage = message ?? "超星认证失败，请稍后重试"
            }
            return nil
        }
    }

    private func exchangeLibseatAppToken(appToken: String) async throws -> String {
        let body = try await requestLibseat(
            method: "POST",
            path: "/rest/ssoAuth",
            query: [("token", appToken)],
            form: [("token", appToken)],
            token: appToken
        )
        return try LibrarySeatProtocol.parseToken(body)
    }

    private func fetchLibseatUser(seatToken: String) async throws -> LibraryUser {
        let body = try await requestLibseat(
            method: "GET",
            path: "/rest/v2/user",
            query: [("token", seatToken)],
            token: seatToken
        )
        return try LibrarySeatProtocol.parseUser(body)
    }

    private func fetchLibseatFilters(token: String) async throws -> LibraryFilters {
        try LibrarySeatProtocol.parseFilters(
            try await requestLibseat(method: "GET", path: "/rest/v2/free/filters", query: [("token", token)], token: token)
        )
    }

    private func fetchLibseatFloors(token: String, buildingId: String) async throws -> [LibraryFloor] {
        try LibrarySeatProtocol.parseFloors(
            try await requestLibseat(
                method: "POST",
                path: "/rest/v2/room/floors/\(pathEncode(buildingId))",
                query: [("token", token)],
                token: token
            )
        )
    }

    private func fetchLibseatRooms(
        token: String,
        building: LibraryBuilding,
        date: String,
        floorMap: [Int: String]
    ) async throws -> [LibraryRoom] {
        try LibrarySeatProtocol.parseRooms(
            try await requestLibseat(
                method: "GET",
                path: "/rest/v2/room/stats2/\(pathEncode(building.id))/\(pathEncode(date))",
                query: [("token", token)],
                token: token
            ),
            floorNamesByNum: floorMap
        )
    }

    private func fetchLibseatLayout(token: String, roomId: String, date: String) async throws -> LibrarySeatLayout {
        try LibrarySeatProtocol.parseLayout(
            try await requestLibseat(
                method: "GET",
                path: "/rest/v2/room/layoutByDate/\(pathEncode(roomId))/\(pathEncode(date))",
                query: [("token", token)],
                token: token
            )
        )
    }

    private func fetchLibseatCurrentReservations(token: String) async throws -> [LibraryReservation] {
        try LibrarySeatProtocol.parseCurrentReservations(
            try await requestLibseat(method: "GET", path: "/rest/v2/user/reservations", query: [("token", token)], token: token)
        )
    }

    private func fetchLibseatHistoryReservations(token: String) async throws -> [LibraryReservation] {
        try LibrarySeatProtocol.parseHistoryReservations(
            try await requestLibseat(
                method: "GET",
                path: "/rest/v2/history/1/50",
                query: [("token", token), ("page", "1"), ("pageSize", "50")],
                token: token
            )
        )
    }

    private func requestLibseat(
        method: String,
        path: String,
        query: [(String, String)],
        form: [(String, String)]? = nil,
        token: String
    ) async throws -> String {
        let queryString = query.isEmpty ? "" : "?" + LibrarySeatProtocol.formEncode(query)
        let fullString = "\(LibrarySeatProtocol.baseURLString)\(path)\(queryString)"
        guard let url = URL(string: fullString), isAllowedLibseatURL(url) else {
            throw LibrarySeatError.message("未允许的图书馆请求地址")
        }
        var headers = try UpcLibrarySigning.signedHeaders(
            token: token,
            method: method,
            uuid: uuidProvider(),
            timestamp: clock()
        )
        if form != nil {
            headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"
        }
        let response: RawHTTPResponse
        do {
            response = try await transport(
                CasHTTPRequest(url: url, method: method, headers: headers, formItems: form)
            )
        } catch let error as StrictHTTPError {
            throw LibrarySeatError.message(error.localizedDescription)
        }
        switch response.statusCode {
        case 200...299:
            return response.body
        case 401, 403, 300...399:
            throw LibrarySeatError.sessionExpired("图书馆登录已失效，请重新验证")
        default:
            throw LibrarySeatError.message("图书馆服务暂时不可用（HTTP \(response.statusCode)）")
        }
    }

    private func fetchChaoxingRooms(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        date: String
    ) async throws -> ChaoxingSeatRoomPage {
        var params: [(String, String)] = [
            ("time", ""),
            ("cpage", "1"),
            ("pageSize", "100"),
            ("firstLevelName", ""),
            ("secondLevelName", ""),
            ("thirdLevelName", ""),
            ("day", date),
            ("deptIdEnc", UpcChaoxingAuthEndpoints.fidEnc)
        ]
        if kind == .seatEngine {
            params.append(("seatId", UpcChaoxingAuthEndpoints.seatEngineSeatId))
        }
        let query = LibrarySeatProtocol.formEncode(params)
        guard let url = URL(string: "https://office.chaoxing.com/data/apps/\(kind.api)/room/list?\(query)") else {
            throw ChaoxingProtocolError.protocolError("非法 Chaoxing URL")
        }
        let body = try await readChaoxing(cookieHeader: cookieHeader, url: url)
        return try ChaoxingProtocol.parseSeatRooms(body, kind: kind, date: date)
    }

    private func fetchChaoxingRoomDetail(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        date: String
    ) async throws -> ChaoxingSeatRoomDetail {
        guard let infoURL = URL(string: "https://office.chaoxing.com/data/apps/\(kind.api)/room/info") else {
            throw ChaoxingProtocolError.protocolError("非法 Chaoxing URL")
        }
        let infoBody = try await readChaoxing(
            cookieHeader: cookieHeader,
            url: infoURL,
            formItems: [
                ("id", String(roomId)),
                ("fidEnc", UpcChaoxingAuthEndpoints.fidEnc)
            ]
        )
        let detail = try ChaoxingProtocol.parseSeatRoomDetail(infoBody, date: date, expectedRoomId: roomId)

        guard let drawURL = URL(string: "https://office.chaoxing.com/data/apps/\(kind.api)/getdrawseat?id=\(roomId)") else {
            throw ChaoxingProtocolError.protocolError("非法 Chaoxing URL")
        }
        let drawBody = try await readChaoxing(cookieHeader: cookieHeader, url: drawURL)
        let places = try ChaoxingProtocol.parseSeatPlaces(drawBody)
        return ChaoxingSeatRoomDetail(
            roomId: detail.roomId,
            name: detail.name,
            capacity: detail.capacity,
            timeUnit: detail.timeUnit,
            startMinute: detail.startMinute,
            endMinute: detail.endMinute,
            places: places,
            disabledSeatNums: detail.disabledSeatNums,
            seatRulesKnown: detail.seatRulesKnown,
            minDurationMinutes: detail.minDurationMinutes,
            maxDurationMinutes: detail.maxDurationMinutes,
            closed: detail.closed
        )
    }

    private func fetchChaoxingUsedSeatNums(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        startMinute: Int,
        endMinute: Int,
        date: String
    ) async throws -> Set<String> {
        var params: [(String, String)] = [
            ("roomId", String(roomId)),
            ("startTime", LibraryTimeFormatter.clockMinute(startMinute)),
            ("endTime", LibraryTimeFormatter.clockMinute(endMinute)),
            ("day", date)
        ]
        if kind == .seatEngine {
            params.append(("seatId", UpcChaoxingAuthEndpoints.seatEngineSeatId))
        } else {
            params.append(("fidEnc", UpcChaoxingAuthEndpoints.fidEnc))
        }
        let query = LibrarySeatProtocol.formEncode(params)
        guard let url = URL(string: "https://office.chaoxing.com/data/apps/\(kind.api)/getusedseatnums?\(query)") else {
            throw ChaoxingProtocolError.protocolError("非法 Chaoxing URL")
        }
        let body = try await readChaoxing(cookieHeader: cookieHeader, url: url)
        return try ChaoxingProtocol.parseUsedSeatNums(body)
    }

    private func fetchChaoxingUsedTimes(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        seatNum: String,
        date: String
    ) async throws -> [ChaoxingTimeInterval] {
        var params: [(String, String)] = [
            ("roomId", String(roomId)),
            ("seatNum", seatNum),
            ("day", date)
        ]
        if kind == .seatEngine {
            params.append(("seatId", UpcChaoxingAuthEndpoints.seatEngineSeatId))
        } else {
            params.append(("fidEnc", UpcChaoxingAuthEndpoints.fidEnc))
        }
        let query = LibrarySeatProtocol.formEncode(params)
        guard let url = URL(string: "https://office.chaoxing.com/data/apps/\(kind.api)/getusedtimes?\(query)") else {
            throw ChaoxingProtocolError.protocolError("非法 Chaoxing URL")
        }
        let body = try await readChaoxing(cookieHeader: cookieHeader, url: url)
        return try ChaoxingProtocol.parseUsedTimes(body)
    }

    private func readChaoxing(
        cookieHeader: String,
        url: URL,
        formItems: [(String, String)]? = nil
    ) async throws -> String {
        guard isAllowedChaoxingReadURL(url) else {
            throw ChaoxingProtocolError.protocolError("未允许的超星只读路径")
        }
        var headers: [String: String] = [
            "Accept": "application/json, text/html;q=0.9",
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148"
        ]
        if !cookieHeader.isEmpty {
            headers["Cookie"] = cookieHeader
        }
        let response: RawHTTPResponse
        do {
            response = try await transport(
                CasHTTPRequest(
                    url: url,
                    method: formItems == nil ? "GET" : "POST",
                    headers: headers,
                    formItems: formItems
                )
            )
        } catch let error as StrictHTTPError {
            throw ChaoxingProtocolError.upstream(error.localizedDescription)
        }
        if [401, 403, 302, 303].contains(response.statusCode) {
            throw ChaoxingProtocolError.sessionExpired
        }
        if response.statusCode == 429 {
            throw ChaoxingProtocolError.rateLimited
        }
        guard (200...299).contains(response.statusCode) else {
            throw ChaoxingProtocolError.upstream("学校超星服务返回 HTTP \(response.statusCode)")
        }
        return response.body
    }

    private func stillValid(accountId: String, generation: Int64, epoch: Int64) async -> Bool {
        guard businessEpoch == epoch else { return false }
        return await matchesAccountGeneration(accountId, generation)
    }

    private func pathEncode(_ raw: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        return raw.addingPercentEncoding(withAllowedCharacters: allowed) ?? raw
    }

    private func isAllowedLibseatURL(_ url: URL) -> Bool {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: true),
              comps.scheme?.lowercased() == "https",
              comps.host?.lowercased() == UpcLibraryAuthEndpoints.libraryHost,
              comps.port == nil || comps.port == 443,
              comps.user == nil,
              comps.password == nil else {
            return false
        }
        return true
    }

    private func isAllowedChaoxingReadURL(_ url: URL) -> Bool {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: true),
              comps.scheme?.lowercased() == "https",
              comps.host?.lowercased() == UpcChaoxingAuthEndpoints.officeHost,
              comps.port == nil || comps.port == 443,
              comps.user == nil,
              comps.password == nil else {
            return false
        }
        let allowedPaths: Set<String> = [
            UpcChaoxingAuthEndpoints.officeSeatPath,
            "/front/third/apps/seatengine/index",
            "/data/apps/seat/room/list",
            "/data/apps/seat/room/info",
            "/data/apps/seat/getdrawseat",
            "/data/apps/seat/getusedseatnums",
            "/data/apps/seat/getusedtimes",
            "/data/apps/seatengine/room/list",
            "/data/apps/seatengine/room/info",
            "/data/apps/seatengine/getdrawseat",
            "/data/apps/seatengine/getusedseatnums",
            "/data/apps/seatengine/getusedtimes"
        ]
        return allowedPaths.contains(comps.percentEncodedPath)
    }
}
