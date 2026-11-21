@testable import UPCPocket
import XCTest

final class FocusClockAndStoreTests: XCTestCase {
    private actor MutableClock {
        var now: Int64
        init(_ initial: Int64) { self.now = initial }
        func advance(_ delta: Int64) { now += delta }
    }

    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("upc-focus-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    func testPomodoroClockCalculatesElapsedAndRemainingAcrossPauses() {
        let snap = FocusTimerSnapshot(
            mode: .focus25,
            running: true,
            startedAt: 10_000,
            plannedSeconds: 1_500,
            pausedTotalMs: 5_000,
            pausedAt: 0
        )
        XCTAssertEqual(PomodoroClock.elapsedSeconds(snap, nowMillis: 75_000), 60)
        XCTAssertEqual(PomodoroClock.remainingSeconds(snap, nowMillis: 75_000), 1_440)
        XCTAssertFalse(PomodoroClock.isFinished(snap, nowMillis: 75_000))

        var pausedSnap = snap
        pausedSnap.running = false
        pausedSnap.pausedAt = 75_000
        XCTAssertEqual(PomodoroClock.elapsedSeconds(pausedSnap, nowMillis: 200_000), 60)
        XCTAssertEqual(PomodoroClock.format(PomodoroClock.remainingSeconds(pausedSnap, nowMillis: 200_000)), "24:00")
    }

    func testFocusStatsUses4AmCutoffIgnoresBreaksAndSeparatesAbandonedSecondsFromSessions() {
        let shanghai = TimeZone(identifier: "Asia/Shanghai")!
        func millis(year: Int, month: Int, day: Int, hour: Int, minute: Int) -> Int64 {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = shanghai
            let date = calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
            return Int64(date.timeIntervalSince1970 * 1000)
        }

        let now = millis(year: 2026, month: 11, day: 21, hour: 14, minute: 0)
        let lateNightSameDay = millis(year: 2026, month: 11, day: 22, hour: 2, minute: 30)
        let beforeCutoffPrevDay = millis(year: 2026, month: 11, day: 21, hour: 3, minute: 50)

        XCTAssertEqual(FocusStats.dayKeyString(for: lateNightSameDay, timeZone: shanghai), "2026-11-21")
        XCTAssertEqual(FocusStats.dayKeyString(for: beforeCutoffPrevDay, timeZone: shanghai), "2026-11-20")

        let records = [
            FocusRecord(id: "1", mode: .focus25, startedAt: now, endedAt: now + 1_500_000, actualSeconds: 1_500, completed: true),
            FocusRecord(id: "2", mode: .deep45, startedAt: now + 2_000_000, endedAt: now + 2_300_000, actualSeconds: 300, completed: false),
            FocusRecord(id: "3", mode: .shortBreak5, startedAt: now + 2_500_000, endedAt: now + 2_800_000, actualSeconds: 300, completed: true),
            FocusRecord(id: "4", mode: .focus25, startedAt: beforeCutoffPrevDay, endedAt: beforeCutoffPrevDay + 1_500_000, actualSeconds: 1_500, completed: true)
        ]

        let summary = FocusStats.summarize(records: records, nowMillis: now, timeZone: shanghai)
        XCTAssertEqual(summary.today.seconds, 1_800)
        XCTAssertEqual(summary.today.sessions, 1)
        XCTAssertEqual(summary.week.seconds, 3_300)
        XCTAssertEqual(summary.week.sessions, 2)
    }

    func testStorePersistsRunningTimerRecoversFinishedSessionAndSupportsFinishEarly() async {
        let box = SendableClockBox(100_000)
        let utc = TimeZone(secondsFromGMT: 0)!

        let first = FocusStore(
            directoryURL: tempDir,
            clock: { box.now },
            timeZoneProvider: { utc },
            idGenerator: { "rec-1" }
        )
        _ = await first.restore()
        let started = await first.toggleStartPause()
        XCTAssertTrue(started.isRunning)

        // Advance clock by 30 minutes across simulated process death.
        box.now += 1_800_000
        let reopened = FocusStore(
            directoryURL: tempDir,
            clock: { box.now },
            timeZoneProvider: { utc },
            idGenerator: { "rec-auto" }
        )
        let restored = await reopened.restore()
        XCTAssertFalse(restored.isRunning)
        XCTAssertEqual(restored.summary.today.seconds, 1_500)
        XCTAssertEqual(restored.summary.today.sessions, 1)

        // Start another timer, advance 120s, and finishEarly.
        _ = await reopened.toggleStartPause()
        box.now += 120_000
        let afterEarly = await reopened.finishEarly()
        XCTAssertEqual(afterEarly.summary.today.seconds, 1_620)
        XCTAssertEqual(afterEarly.summary.today.sessions, 1)
        XCTAssertEqual(afterEarly.recentRecords.count, 2)
    }
}

private final class SendableClockBox: @unchecked Sendable {
    var now: Int64
    init(_ initial: Int64) { self.now = initial }
}
