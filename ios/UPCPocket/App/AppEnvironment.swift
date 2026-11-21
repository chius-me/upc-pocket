import Foundation
import Observation

enum FocusMode: String, CaseIterable, Identifiable, Sendable {
    case focus25
    case shortBreak5
    case deep45

    var id: String { rawValue }

    var label: String {
        switch self {
        case .focus25: "专注 25 分钟"
        case .shortBreak5: "短休 5 分钟"
        case .deep45: "深度 45 分钟"
        }
    }

    var durationSeconds: Int {
        switch self {
        case .focus25: 25 * 60
        case .shortBreak5: 5 * 60
        case .deep45: 45 * 60
        }
    }
}

@Observable
@MainActor
final class AppEnvironment {
    private let preferencesStore: PreferencesStore
    private let accountStore: SchoolAccountStore
    private(set) var cardBalanceStore: CardBalanceStore!
    private(set) var timetableStore: TimetableStore!
    let powerStore: PowerStore
    let freeClassroomService: FreeClassroomService

    private(set) var preferences = AppPreferences()
    private(set) var accountState = SchoolAccountState()
    private(set) var cardBalanceState = CardBalanceUiState()
    private(set) var powerState = PowerUiState()
    private(set) var timetableState = TimetableUiState()
    private(set) var isLoaded = false

    private(set) var focusMode: FocusMode = .focus25
    private(set) var remainingSeconds: Int = FocusMode.focus25.durationSeconds
    private(set) var isFocusRunning = false
    private(set) var completedFocusSessions = 0

    private var timerTask: Task<Void, Never>?

    init(
        preferencesStore: PreferencesStore = PreferencesStore(),
        accountStore: SchoolAccountStore = SchoolAccountStore(),
        powerStore: PowerStore = PowerStore(),
        freeClassroomService: FreeClassroomService = FreeClassroomService()
    ) {
        self.preferencesStore = preferencesStore
        self.accountStore = accountStore
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
    }

    var focusProgress: Double {
        guard focusMode.durationSeconds > 0 else { return 0 }
        return Double(remainingSeconds) / Double(focusMode.durationSeconds)
    }

    var formattedFocusTime: String {
        let minutes = remainingSeconds / 60
        let seconds = remainingSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    func bootstrap() async {
        await LocalStorageExclusion.shared.applyDefaultExclusions()
        async let loadedPrefs = preferencesStore.load()
        async let restoredAccount = accountStore.restore()
        async let restoredPower = powerStore.restore()
        preferences = await loadedPrefs
        accountState = await restoredAccount
        powerState = await restoredPower
        cardBalanceState = await cardBalanceStore.restore()
        timetableState = await timetableStore.restore()
        isLoaded = true
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
            } else if previousAccountId != nil {
                cardBalanceState = await cardBalanceStore.restore()
                timetableState = await timetableStore.onAccountInvalidated(reason: .credentialsUpdated)
            } else {
                cardBalanceState = await cardBalanceStore.restore()
                timetableState = await timetableStore.restore()
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

    func selectFocusMode(_ mode: FocusMode) {
        timerTask?.cancel()
        timerTask = nil
        focusMode = mode
        remainingSeconds = mode.durationSeconds
        isFocusRunning = false
    }

    func toggleFocusTimer() {
        if isFocusRunning {
            pauseFocusTimer()
        } else {
            startFocusTimer()
        }
    }

    func resetFocusTimer() {
        timerTask?.cancel()
        timerTask = nil
        remainingSeconds = focusMode.durationSeconds
        isFocusRunning = false
    }

    private func startFocusTimer() {
        guard timerTask == nil else { return }
        if remainingSeconds <= 0 {
            remainingSeconds = focusMode.durationSeconds
        }
        isFocusRunning = true
        timerTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, self.isFocusRunning else { break }
                if self.remainingSeconds <= 1 {
                    self.remainingSeconds = 0
                    self.isFocusRunning = false
                    self.completedFocusSessions += 1
                    self.timerTask = nil
                    break
                } else {
                    self.remainingSeconds -= 1
                }
            }
        }
    }

    private func pauseFocusTimer() {
        timerTask?.cancel()
        timerTask = nil
        isFocusRunning = false
    }
}
