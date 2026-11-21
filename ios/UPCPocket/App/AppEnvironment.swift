import Foundation
import Observation

@Observable
@MainActor
final class AppEnvironment {
    private let preferencesStore: PreferencesStore
    private let accountStore: SchoolAccountStore
    private(set) var cardBalanceStore: CardBalanceStore!
    private(set) var timetableStore: TimetableStore!
    private(set) var librarySeatStore: LibrarySeatStore!
    let todoStore: TodoStore
    let focusStore: FocusStore
    let powerStore: PowerStore
    let freeClassroomService: FreeClassroomService

    private(set) var preferences = AppPreferences()
    private(set) var accountState = SchoolAccountState()
    private(set) var cardBalanceState = CardBalanceUiState()
    private(set) var powerState = PowerUiState()
    private(set) var timetableState = TimetableUiState()
    private(set) var librarySeatState = LibrarySeatUiState()
    private(set) var todoState = TodoUiState()
    private(set) var focusState = FocusUiState()
    private(set) var isLoaded = false

    private var timerTask: Task<Void, Never>?

    init(
        preferencesStore: PreferencesStore = PreferencesStore(),
        accountStore: SchoolAccountStore = SchoolAccountStore(),
        todoStore: TodoStore = TodoStore(),
        focusStore: FocusStore = FocusStore(),
        powerStore: PowerStore = PowerStore(),
        freeClassroomService: FreeClassroomService = FreeClassroomService()
    ) {
        self.preferencesStore = preferencesStore
        self.accountStore = accountStore
        self.todoStore = todoStore
        self.focusStore = focusStore
        self.powerStore = powerStore
        self.freeClassroomService = freeClassroomService
        self.cardBalanceStore = CardBalanceStore(
            identityProvider: { [accountStore] in
                await accountStore.currentStudentIdentitySnapshot()
            }
        )
        self.timetableStore = TimetableStore(
            accountStateProvider: { [accountStore] in
                await accountStore.currentAccountStateSnapshot()
            },
            matchesAccountGeneration: { [accountStore] accountId, generation in
                await accountStore.matches(accountId: accountId, generation: generation)
            },
            loginCredentialProvider: { [accountStore] in
                await accountStore.currentLoginCredentialSnapshot()
            }
        )
        self.librarySeatStore = LibrarySeatStore(
            accountStateProvider: { [accountStore] in
                await accountStore.currentAccountStateSnapshot()
            },
            matchesAccountGeneration: { [accountStore] accountId, generation in
                await accountStore.matches(accountId: accountId, generation: generation)
            },
            loginCredentialProvider: { [accountStore] in
                await accountStore.currentLoginCredentialSnapshot()
            }
        )
    }

    func bootstrap() async {
        await LocalStorageExclusion.shared.applyDefaultExclusions()
        async let loadedPrefs = preferencesStore.load()
        async let restoredAccount = accountStore.restore()
        async let restoredPower = powerStore.restore()
        async let restoredTodos = todoStore.restore()
        async let restoredFocus = focusStore.restore()
        preferences = await loadedPrefs
        accountState = await restoredAccount
        powerState = await restoredPower
        todoState = await restoredTodos
        focusState = await restoredFocus
        cardBalanceState = await cardBalanceStore.restore()
        timetableState = await timetableStore.restore()
        librarySeatState = await librarySeatStore.syncAccountStatusAndCampus(preferences.campus)
        isLoaded = true
        syncFocusTicker()
    }

    func updateCampus(_ campus: CampusPreference) {
        preferences.campus = campus
        let snapshot = preferences
        Task {
            await preferencesStore.save(snapshot)
        }
    }

    func updateTheme(_ theme: ThemePreference) {
        preferences.theme = theme
        let snapshot = preferences
        Task {
            await preferencesStore.save(snapshot)
        }
    }

    func saveAccount(loginName: String, password: String) async -> AccountWriteResult {
        let previousAccountId = accountState.accountId
        let (result, updatedState) = await accountStore.save(rawLoginName: loginName, password: password)
        accountState = updatedState
        if case .success = result {
            if previousAccountId != updatedState.accountId {
                cardBalanceState = await cardBalanceStore.clearForAccountChange()
                timetableState = await timetableStore.onAccountInvalidated(reason: .accountChangedOrCleared)
                librarySeatState = await librarySeatStore.onAccountInvalidated(reason: .accountChangedOrCleared)
            } else if previousAccountId != nil {
                cardBalanceState = await cardBalanceStore.restore()
                timetableState = await timetableStore.onAccountInvalidated(reason: .credentialsUpdated)
                librarySeatState = await librarySeatStore.onAccountInvalidated(reason: .credentialsUpdated)
            } else {
                cardBalanceState = await cardBalanceStore.restore()
                timetableState = await timetableStore.restore()
                librarySeatState = await librarySeatStore.syncAccountStatusAndCampus(preferences.campus)
            }
        }
        return result
    }

    func clearAccount() async -> Bool {
        let (ok, updatedState) = await accountStore.clear()
        accountState = updatedState
        if ok {
            cardBalanceState = await cardBalanceStore.clearForAccountChange()
            timetableState = await timetableStore.onAccountInvalidated(reason: .accountChangedOrCleared)
            librarySeatState = await librarySeatStore.onAccountInvalidated(reason: .accountChangedOrCleared)
        }
        return ok
    }

    func refreshTimetable() async {
        timetableState.isRefreshing = true
        timetableState.errorMessage = nil
        timetableState = await timetableStore.refresh()
    }

    func selectTimetableWeek(term: TimetableTermInfo, week: Int) async {
        timetableState.selectedTerm = term
        timetableState.selectedWeek = week
        timetableState.isRefreshing = true
        timetableState.errorMessage = nil
        timetableState = await timetableStore.selectWeek(term: term, week: week)
    }

    func adoptTimetableWebViewCookies(
        _ cookies: [String: String],
        context: ControlledWebViewPolicy.SessionContext
    ) async {
        timetableState.isRefreshing = true
        timetableState.errorMessage = nil
        timetableState = await timetableStore.adoptWebViewCookies(cookies, sessionContext: context)
    }

    func dismissTimetableInteractionPrompt() async {
        timetableState = await timetableStore.dismissInteractionPrompt()
    }

    func clearTimetableCache() async {
        timetableState = await timetableStore.clearLocalCache()
    }

    func syncLibraryCampus() async {
        librarySeatState = await librarySeatStore.syncAccountStatusAndCampus(preferences.campus)
    }

    func selectLibrarySystem(_ tab: LibrarySystemTab) async {
        librarySeatState = await librarySeatStore.selectSystem(tab)
    }

    func refreshLibrarySeat() async {
        librarySeatState.isLoading = true
        librarySeatState.errorMessage = nil
        librarySeatState = await librarySeatStore.refreshActiveSystem()
    }

    func selectLibseatDate(_ date: String) async {
        librarySeatState = await librarySeatStore.selectLibseatDate(date)
    }

    func selectLibseatBuilding(_ building: LibraryBuilding) async {
        librarySeatState = await librarySeatStore.selectLibseatBuilding(building)
    }

    func selectLibseatFloor(_ floor: LibraryFloor?) async {
        librarySeatState = await librarySeatStore.selectLibseatFloor(floor)
    }

    func loadLibseatRoomLayout(_ room: LibraryRoom) async {
        librarySeatState = await librarySeatStore.loadLibseatRoomLayout(room)
    }

    func dismissLibseatRoomDetail() async {
        librarySeatState = await librarySeatStore.dismissLibseatRoomDetail()
    }

    func selectChaoxingDate(_ date: String) async {
        librarySeatState = await librarySeatStore.selectChaoxingDate(date)
    }

    func loadChaoxingRoomDetail(_ room: ChaoxingSeatRoom) async {
        librarySeatState = await librarySeatStore.loadChaoxingRoomDetail(room)
    }

    func updateChaoxingWindow(startMinute: Int, endMinute: Int) async {
        librarySeatState = await librarySeatStore.updateChaoxingWindow(startMinute: startMinute, endMinute: endMinute)
    }

    func loadChaoxingSeatUsedTimes(seatNum: String) async {
        librarySeatState = await librarySeatStore.loadChaoxingSeatUsedTimes(seatNum: seatNum)
    }

    func dismissChaoxingRoomDetail() async {
        librarySeatState = await librarySeatStore.dismissChaoxingRoomDetail()
    }

    func adoptLibseatWebViewToken(_ url: URL, context: ControlledWebViewPolicy.SessionContext) async {
        librarySeatState = await librarySeatStore.adoptLibseatWebViewToken(url, sessionContext: context)
    }

    func adoptChaoxingWebViewCookies(_ cookies: [String: String], context: ControlledWebViewPolicy.SessionContext) async {
        librarySeatState = await librarySeatStore.adoptChaoxingWebViewCookies(cookies, sessionContext: context)
    }

    func dismissLibraryInteraction() async {
        librarySeatState = await librarySeatStore.dismissInteractionPrompt()
    }

    func clearLibrarySessions() async {
        librarySeatState = await librarySeatStore.clearInMemorySessions()
    }

    func refreshCardBalance() async {
        cardBalanceState.isRefreshing = true
        cardBalanceState.errorMessage = nil
        let (_, updated) = await cardBalanceStore.refresh()
        cardBalanceState = updated
    }

    func clearCardBalanceCache() async {
        cardBalanceState = await cardBalanceStore.clearCache()
    }

    func loadPowerBuildings() async {
        powerState.isLoadingBuildings = true
        powerState.errorMessage = nil
        let (_, updated) = await powerStore.loadBuildings()
        powerState = updated
    }

    func queryPower(building: String, room: String) async {
        powerState.isQuerying = true
        powerState.errorMessage = nil
        powerState.candidates = []
        let (_, updated) = await powerStore.query(buildingInput: building, roomInput: room)
        powerState = updated
    }

    func clearSavedPower() async {
        powerState = await powerStore.clearSaved()
    }

    func upsertTodo(id: String?, title: String, dueAt: Int64?, reminderAt: Int64?) async {
        let (_, updated) = await todoStore.upsertTask(
            id: id,
            rawTitle: title,
            dueAt: dueAt,
            reminderAt: reminderAt
        )
        todoState = updated
    }

    func toggleTodoCompleted(_ id: String) async {
        todoState = await todoStore.toggleCompleted(taskId: id)
    }

    func deleteTodo(_ id: String) async {
        todoState = await todoStore.deleteTask(taskId: id)
    }

    func clearCompletedTodos() async {
        todoState = await todoStore.clearCompleted()
    }

    func requestTodoNotificationPermissionIfNeeded() async -> Bool {
        await todoStore.requestReminderPermissionIfNeeded()
    }

    func selectFocusMode(_ mode: FocusMode) async {
        timerTask?.cancel()
        timerTask = nil
        focusState = await focusStore.selectMode(mode)
        syncFocusTicker()
    }

    func toggleFocusTimer() async {
        focusState = await focusStore.toggleStartPause()
        syncFocusTicker()
    }

    func resetFocusTimer() async {
        timerTask?.cancel()
        timerTask = nil
        focusState = await focusStore.reset()
    }

    func finishFocusEarly() async {
        timerTask?.cancel()
        timerTask = nil
        focusState = await focusStore.finishEarly()
    }

    func clearFocusHistory() async {
        focusState = await focusStore.clearHistory()
    }

    private func syncFocusTicker() {
        if focusState.isRunning {
            guard timerTask == nil else { return }
            timerTask = Task { [weak self] in
                while let self, !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    guard !Task.isCancelled else { break }
                    self.focusState = await self.focusStore.tick()
                    if !self.focusState.isRunning {
                        self.timerTask = nil
                        break
                    }
                }
            }
        } else {
            timerTask?.cancel()
            timerTask = nil
        }
    }
}
