import Foundation

/// Wall-clock persisted Pomodoro controller storing snapshots and records in backup-excluded storage.
actor FocusStore {
    private static let fileName = "focus-v1.json"
    private static let maxRecords = 200
    private static let maxFileBytes = 2 * 1024 * 1024

    private let directoryURL: URL?
    private let clock: @Sendable () -> Int64
    private let timeZoneProvider: @Sendable () -> TimeZone
    private let idGenerator: @Sendable () -> String
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private var persisted = FocusPersistenceState()
    private var currentState = FocusUiState()

    init(
        directoryURL: URL? = nil,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        timeZoneProvider: @escaping @Sendable () -> TimeZone = { .current },
        idGenerator: @escaping @Sendable () -> String = { UUID().uuidString }
    ) {
        self.directoryURL = directoryURL
        self.clock = clock
        self.timeZoneProvider = timeZoneProvider
        self.idGenerator = idGenerator
    }

    func restore() async -> FocusUiState {
        persisted = await readStateFromDisk()
        let now = max(1, clock())
        await reconcileFinishedIfNeeded(now: now)
        publishUi(now: now)
        return currentState
    }

    func tick() async -> FocusUiState {
        let now = max(1, clock())
        await reconcileFinishedIfNeeded(now: now)
        publishUi(now: now)
        return currentState
    }

    func selectMode(_ mode: FocusMode) async -> FocusUiState {
        let now = max(1, clock())
        persisted.snapshot = FocusTimerSnapshot(
            mode: mode,
            running: false,
            startedAt: 0,
            plannedSeconds: mode.durationSeconds,
            pausedTotalMs: 0,
            pausedAt: 0,
            focusRound: persisted.snapshot.focusRound
        )
        await writeStateAtomically(persisted)
        publishUi(now: now)
        return currentState
    }

    func toggleStartPause() async -> FocusUiState {
        let now = max(1, clock())
        var snap = persisted.snapshot
        if snap.isIdle {
            snap = FocusTimerSnapshot(
                mode: snap.mode,
                running: true,
                startedAt: now,
                plannedSeconds: snap.mode.durationSeconds,
                pausedTotalMs: 0,
                pausedAt: 0,
                focusRound: snap.focusRound
            )
        } else if snap.running {
            snap.running = false
            snap.pausedAt = now
        } else if snap.isPaused {
            let addedPause = max(0, now - snap.pausedAt)
            snap.running = true
            snap.pausedTotalMs += addedPause
            snap.pausedAt = 0
        }
        persisted.snapshot = snap
        await writeStateAtomically(persisted)
        publishUi(now: now)
        return currentState
    }

    func reset() async -> FocusUiState {
        let now = max(1, clock())
        let snap = persisted.snapshot
        persisted.snapshot = FocusTimerSnapshot(
            mode: snap.mode,
            running: false,
            startedAt: 0,
            plannedSeconds: snap.mode.durationSeconds,
            pausedTotalMs: 0,
            pausedAt: 0,
            focusRound: snap.focusRound
        )
        await writeStateAtomically(persisted)
        publishUi(now: now)
        return currentState
    }

    func finishEarly() async -> FocusUiState {
        let now = max(1, clock())
        let snap = persisted.snapshot
        if !snap.isIdle {
            let elapsed = min(PomodoroClock.elapsedSeconds(snap, nowMillis: now), snap.plannedSeconds)
            let completed = PomodoroClock.isFinished(snap, nowMillis: now)
            if snap.mode.isFocus && elapsed > 0 {
                let rec = FocusRecord(
                    id: idGenerator(),
                    mode: snap.mode,
                    startedAt: snap.startedAt,
                    endedAt: now,
                    actualSeconds: elapsed,
                    completed: completed
                )
                persisted.records = Array(([rec] + persisted.records).prefix(Self.maxRecords))
                if completed {
                    persisted.snapshot.focusRound += 1
                }
            }
        }
        persisted.snapshot = FocusTimerSnapshot(
            mode: snap.mode,
            running: false,
            startedAt: 0,
            plannedSeconds: snap.mode.durationSeconds,
            pausedTotalMs: 0,
            pausedAt: 0,
            focusRound: persisted.snapshot.focusRound
        )
        await writeStateAtomically(persisted)
        publishUi(now: now)
        return currentState
    }

    func clearHistory() async -> FocusUiState {
        let now = max(1, clock())
        persisted.records = []
        await writeStateAtomically(persisted)
        publishUi(now: now)
        return currentState
    }

    private func reconcileFinishedIfNeeded(now: Int64) async {
        let snap = persisted.snapshot
        guard snap.running, PomodoroClock.isFinished(snap, nowMillis: now) else { return }
        if snap.mode.isFocus && snap.plannedSeconds > 0 {
            let rec = FocusRecord(
                id: idGenerator(),
                mode: snap.mode,
                startedAt: snap.startedAt,
                endedAt: now,
                actualSeconds: snap.plannedSeconds,
                completed: true
            )
            persisted.records = Array(([rec] + persisted.records).prefix(Self.maxRecords))
            persisted.snapshot.focusRound += 1
        }
        persisted.snapshot = FocusTimerSnapshot(
            mode: snap.mode,
            running: false,
            startedAt: 0,
            plannedSeconds: snap.mode.durationSeconds,
            pausedTotalMs: 0,
            pausedAt: 0,
            focusRound: persisted.snapshot.focusRound
        )
        await writeStateAtomically(persisted)
    }

    private func publishUi(now: Int64) {
        let snap = persisted.snapshot
        let remaining = PomodoroClock.remainingSeconds(snap, nowMillis: now)
        let elapsed = PomodoroClock.elapsedSeconds(snap, nowMillis: now)
        let summary = FocusStats.summarize(
            records: persisted.records,
            nowMillis: now,
            timeZone: timeZoneProvider()
        )
        currentState = FocusUiState(
            mode: snap.mode,
            remainingSeconds: remaining,
            elapsedSeconds: elapsed,
            isRunning: snap.running,
            isPaused: snap.isPaused,
            isIdle: snap.isIdle,
            progress: PomodoroClock.progress(snap, nowMillis: now),
            formattedTime: PomodoroClock.format(remaining),
            focusRound: snap.focusRound,
            summary: summary,
            recentRecords: Array(persisted.records.prefix(15))
        )
    }

    private func readStateFromDisk() async -> FocusPersistenceState {
        guard let fileURL = await resolveFileURL(),
              FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              data.count <= Self.maxFileBytes,
              let decoded = try? decoder.decode(FocusPersistenceState.self, from: data) else {
            return FocusPersistenceState()
        }
        return decoded
    }

    private func writeStateAtomically(_ state: FocusPersistenceState) async {
        guard let fileURL = await resolveFileURL(),
              let data = try? encoder.encode(state),
              data.count <= Self.maxFileBytes else {
            return
        }
        try? data.write(to: fileURL, options: .atomic)
        try? await LocalStorageExclusion.shared.excludeFromBackup(url: fileURL)
    }

    private func resolveFileURL() async -> URL? {
        if let directoryURL {
            try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: directoryURL)
            return directoryURL.appendingPathComponent(Self.fileName, isDirectory: false)
        }
        guard let base = try? await LocalStorageExclusion.shared.noBackupBaseDirectory() else {
            return nil
        }
        let dir = base.appendingPathComponent("focus", isDirectory: true)
        try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: dir)
        return dir.appendingPathComponent(Self.fileName, isDirectory: false)
    }
}
