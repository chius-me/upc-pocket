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

    private(set) var preferences = AppPreferences()
    private(set) var accountState = SchoolAccountState()
    private(set) var isLoaded = false

    private(set) var focusMode: FocusMode = .focus25
    private(set) var remainingSeconds: Int = FocusMode.focus25.durationSeconds
    private(set) var isFocusRunning = false
    private(set) var completedFocusSessions = 0

    private var timerTask: Task<Void, Never>?

    init(
        preferencesStore: PreferencesStore = PreferencesStore(),
        accountStore: SchoolAccountStore = SchoolAccountStore()
    ) {
        self.preferencesStore = preferencesStore
        self.accountStore = accountStore
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
        preferences = await loadedPrefs
        accountState = await restoredAccount
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
        let (result, updatedState) = await accountStore.save(rawLoginName: loginName, password: password)
        accountState = updatedState
        return result
    }

    func clearAccount() async -> Bool {
        let (ok, updatedState) = await accountStore.clear()
        accountState = updatedState
        return ok
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
