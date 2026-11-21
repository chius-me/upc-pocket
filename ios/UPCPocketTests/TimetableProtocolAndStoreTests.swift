@testable import UPCPocket
import XCTest

final class TimetableProtocolAndStoreTests: XCTestCase {
    private actor FakeCredentialStore: CredentialBackingStore {
        private var storage: [String: Data] = [:]

        func save(accountKey: String, data: Data) async throws {
            storage[accountKey] = data
        }

        func load(accountKey: String) async throws -> Data? {
            storage[accountKey]
        }

        func delete(accountKey: String) async throws {
            storage.removeValue(forKey: accountKey)
        }
    }

    private actor AsyncGate {
        private var enteredContinuation: CheckedContinuation<Void, Never>?
        private var releaseContinuation: CheckedContinuation<Void, Never>?
        private var didEnter = false
        private var isReleased = false

        func markEntered() {
            didEnter = true
            enteredContinuation?.resume()
            enteredContinuation = nil
        }

        func waitUntilEntered() async {
            if didEnter { return }
            await withCheckedContinuation { enteredContinuation = $0 }
        }

        func waitForRelease() async {
            if isReleased { return }
            await withCheckedContinuation { releaseContinuation = $0 }
        }

        func release() {
            isReleased = true
            releaseContinuation?.resume()
            releaseContinuation = nil
        }
    }

    private actor RetryCounter {
        var authCount = 0
        func increment() { authCount += 1 }
    }

    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("upc-timetable-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    func testOfficialScheduleCoversPeriods1To12AndLeaves13To14Nil() {
        XCTAssertEqual(TimetableSchedule.resolveTimeRange(startPeriod: 1, endPeriod: 1), "08:00–08:45")
        XCTAssertEqual(TimetableSchedule.resolveTimeRange(startPeriod: 1, endPeriod: 2), "08:00–09:35")
        XCTAssertEqual(TimetableSchedule.resolveTimeRange(startPeriod: 6, endPeriod: 9), "14:00–17:30")
        XCTAssertEqual(TimetableSchedule.resolveTimeRange(startPeriod: 10, endPeriod: 12), "19:00–21:25")
        XCTAssertNil(TimetableSchedule.resolveTimeRange(startPeriod: 13, endPeriod: 14))
        XCTAssertEqual(
            TimetableSchedule.resolveTimeRange(startPeriod: 1, endPeriod: 2, rawCourseTime: "08:15-09:50"),
            "08:15–09:50"
        )
    }

    func testParseIndexSkipsMalformedHistoryNormalizesSundayAndRejectsBooleanCode() {
        let parsed = TimetableProtocol.parseIndex(Self.indexWithBadHistoryJSON)
        guard case let .ok(index) = parsed else {
            return XCTFail("Expected ok index")
        }
        XCTAssertEqual(index.terms.count, 1)
        XCTAssertEqual(index.terms[0].year, "2026-2027")
        XCTAssertEqual(index.currentWeek, 8)
        XCTAssertEqual(index.currentWeekday, 7)

        if case .authRequired = TimetableProtocol.parseIndex(#"{"e":10013,"m":"not login"}"#) {
            // expected
        } else {
            XCTFail("Expected authRequired for 10013")
        }

        // Critically ensure JSON false is never bridged to 0!
        if case .upstreamError = TimetableProtocol.parseIndex(#"{"e":false,"d":{"termInfo":[]}}"#) {
            // expected
        } else {
            XCTFail("Expected upstreamError when e is boolean false")
        }
    }

    func testParseWeekSplitsNonContiguousPeriodsAndChecksDateAlignment() {
        let term = TimetableTermInfo(
            year: "2026-2027",
            term: "1",
            startDayIso: "2026-09-06",
            totalWeeks: 19,
            serverId: "t1",
            label: "2026-2027 秋季"
        )
        let parsed = TimetableProtocol.parseWeek(Self.week8JSON, term: term, requestedWeek: 8, fetchedAtEpochMillis: 999)
        guard case let .ok(week) = parsed else {
            return XCTFail("Expected ok week")
        }
        XCTAssertEqual(week.days.count, 7)
        XCTAssertEqual(week.datesIso.first, "2026-10-25")

        let split = week.meetings.filter { $0.courseName == "概率论" }
        XCTAssertEqual(split.count, 2)
        XCTAssertEqual(split.map(\.periods), [[1], [3]])
        XCTAssertEqual(split.first?.sourcePeriods, [1, 3])
        XCTAssertEqual(split.first?.sectionsSource, "lessArr_split_noncontiguous")
        XCTAssertEqual(split.first?.effectiveTimeText, "08:00–08:45")

        // Mismatched week number must fail alignment check.
        if case .upstreamError = TimetableProtocol.parseWeek(Self.week8JSON, term: term, requestedWeek: 9) {
            // expected
        } else {
            XCTFail("Expected date alignment failure for week 9")
        }
    }

    func testStoreRecoversOnceOn10013PreservesCacheOnPasswordUpdateAndWipesOnSwitch() async throws {
        let accountDir = tempDir.appendingPathComponent("account", isDirectory: true)
        let cacheDir = tempDir.appendingPathComponent("cache", isDirectory: true)
        let backing = FakeCredentialStore()
        let accountStore = SchoolAccountStore(directoryURL: accountDir, credentialStore: backing, clock: { 10_000 })
        let (_, state1) = await accountStore.save(rawLoginName: "2024010101", password: "fixture-password")
        let accountId1 = try XCTUnwrap(state1.accountId)

        let sessionStore = TimetableSessionStore(backingStore: backing)
        let cacheStore = TimetableCacheStore(directoryURL: cacheDir)

        // Pre-seed stale session to force single 10013 recovery.
        await sessionStore.save(
            TimetableSession(
                accountId: accountId1,
                credentialGeneration: state1.generation,
                savedAtEpochMillis: 10_000,
                cookies: ["eai-sess": "stale-cookie"]
            )
        )

        let counter = RetryCounter()
        let store = TimetableStore(
            accountStateProvider: { await accountStore.currentAccountStateSnapshot() },
            matchesAccountGeneration: { id, gen in await accountStore.matches(accountId: id, generation: gen) },
            loginCredentialProvider: { await accountStore.currentLoginCredentialSnapshot() },
            sessionStore: sessionStore,
            cacheStore: cacheStore,
            transport: { req in
                let urlStr = req.url.absoluteString
                if urlStr == UpcTimetableAuthEndpoints.indexURLString {
                    if req.headers["Cookie"]?.contains("stale-cookie") ?? false {
                        return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"e":10013,"m":"expired"}"#, contentType: "application/json")
                    }
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.indexJSON, contentType: "application/json")
                }
                if urlStr == UpcTimetableAuthEndpoints.loginURLString && req.method == "GET" {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.casFormHTML, contentType: "text/html")
                }
                if urlStr.hasPrefix("https://cas.upc.edu.cn/cas/login") && req.method == "POST" {
                    await counter.increment()
                    return RawHTTPResponse(
                        statusCode: 302,
                        location: "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-fixture-123456&redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex&from=wap",
                        setCookieHeaders: [],
                        body: "",
                        contentType: nil
                    )
                }
                if urlStr.hasPrefix("https://app.upc.edu.cn/a_upc/api/cas/login") {
                    return RawHTTPResponse(
                        statusCode: 302,
                        location: UpcTimetableAuthEndpoints.pageURLString,
                        setCookieHeaders: ["eai-sess=fresh-cookie; Path=/; Secure"],
                        body: "",
                        contentType: nil
                    )
                }
                if urlStr == UpcTimetableAuthEndpoints.pageURLString {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: "ok", contentType: "text/html")
                }
                if urlStr == UpcTimetableAuthEndpoints.weekURLString {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.week8JSON, contentType: "application/json")
                }
                XCTFail("Unexpected request: \(req.method) \(urlStr)")
                return RawHTTPResponse(statusCode: 500, location: nil, setCookieHeaders: [], body: "", contentType: nil)
            },
            clock: { 10_000 },
            todayISOProvider: { "2026-10-25" }
        )

        let refreshed = await store.refresh()
        let authAttempts = await counter.authCount
        XCTAssertEqual(authAttempts, 1)
        XCTAssertEqual(refreshed.connectionStatus, .online)
        XCTAssertEqual(refreshed.selectedWeek, 8)
        XCTAssertNotNil(refreshed.weekData)
        XCTAssertEqual(refreshed.todaySummary?.courses.count, 2)

        // Password change clears session, preserves offline cache for same accountId.
        let (_, state2) = await accountStore.save(rawLoginName: "2024010101", password: "fixture-password-2")
        let afterPasswordChange = await store.onAccountInvalidated(reason: .credentialsUpdated)
        let sessionAfterPw = await sessionStore.load(accountId: accountId1, credentialGeneration: state2.generation)
        XCTAssertNil(sessionAfterPw)
        XCTAssertEqual(afterPasswordChange.connectionStatus, .offlineCache)
        XCTAssertNotNil(afterPasswordChange.weekData)

        // Switching account clears cache and resets state.
        _ = await accountStore.save(rawLoginName: "TS240100001", password: "fixture-password-3")
        let afterSwitch = await store.onAccountInvalidated(reason: .accountChangedOrCleared)
        XCTAssertEqual(afterSwitch.connectionStatus, .idle)
        XCTAssertNil(afterSwitch.weekData)
        let oldCache = await cacheStore.load(accountId: accountId1)
        XCTAssertNil(oldCache)
    }

    func testAccountSwitchDuringInFlightRefreshDropsLateResultAndAdoptsWebViewCookies() async throws {
        let accountDir = tempDir.appendingPathComponent("account2", isDirectory: true)
        let cacheDir = tempDir.appendingPathComponent("cache2", isDirectory: true)
        let backing = FakeCredentialStore()
        let accountStore = SchoolAccountStore(directoryURL: accountDir, credentialStore: backing, clock: { 50_000 })
        let (_, state1) = await accountStore.save(rawLoginName: "2024010101", password: "fixture-password")
        let accountId1 = try XCTUnwrap(state1.accountId)

        let sessionStore = TimetableSessionStore(backingStore: backing)
        let cacheStore = TimetableCacheStore(directoryURL: cacheDir)
        let gate = AsyncGate()

        let store = TimetableStore(
            accountStateProvider: { await accountStore.currentAccountStateSnapshot() },
            matchesAccountGeneration: { id, gen in await accountStore.matches(accountId: id, generation: gen) },
            loginCredentialProvider: { await accountStore.currentLoginCredentialSnapshot() },
            sessionStore: sessionStore,
            cacheStore: cacheStore,
            transport: { req in
                let urlStr = req.url.absoluteString
                if urlStr == UpcTimetableAuthEndpoints.loginURLString && req.method == "GET" {
                    await gate.markEntered()
                    await gate.waitForRelease()
                    return RawHTTPResponse(
                        statusCode: 200,
                        location: nil,
                        setCookieHeaders: [],
                        body: "<script>config.captcha = { id: 'late' };</script>",
                        contentType: "text/html"
                    )
                }
                if urlStr == UpcTimetableAuthEndpoints.indexURLString {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.indexJSON, contentType: "application/json")
                }
                if urlStr == UpcTimetableAuthEndpoints.weekURLString {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.week8JSON, contentType: "application/json")
                }
                XCTFail("Unexpected request: \(req.method) \(urlStr)")
                return RawHTTPResponse(statusCode: 500, location: nil, setCookieHeaders: [], body: "", contentType: nil)
            },
            clock: { 50_000 },
            todayISOProvider: { "2026-10-25" }
        )

        let refreshTask = Task { await store.refresh() }
        await gate.waitUntilEntered()
        let (_, state2) = await accountStore.save(rawLoginName: "TS240100001", password: "fixture-password-2")
        let switchedState = await store.onAccountInvalidated(reason: .accountChangedOrCleared)
        XCTAssertEqual(switchedState.connectionStatus, .idle)

        await gate.release()
        let staleResult = await refreshTask.value
        XCTAssertNil(staleResult.interactionSession)
        let staleCache = await cacheStore.load(accountId: accountId1)
        XCTAssertNil(staleCache)

        // Adopt verified WebView cookies for account 2.
        let context = ControlledWebViewPolicy.SessionContext(
            accountId: state2.accountId,
            generation: state2.generation,
            entryURL: UpcTimetableAuthEndpoints.loginURL
        )
        let adopted = await store.adoptWebViewCookies(["eai-sess": "webview-cookie-ok"], sessionContext: context)
        XCTAssertEqual(adopted.connectionStatus, .online)
        let savedSession = await sessionStore.load(accountId: state2.accountId!, credentialGeneration: state2.generation)
        XCTAssertEqual(savedSession?.cookies["eai-sess"], "webview-cookie-ok")
    }

    private static let casFormHTML = """
    <form id="fm1" action="\(UpcTimetableAuthEndpoints.loginURLString)" method="post">
      <input type="text" name="username" value="" />
      <input type="password" name="password" value="" />
      <input type="hidden" name="type" value="username_password" />
      <input type="hidden" name="execution" value="e1s1" />
      <input type="hidden" name="_eventId" value="submit" />
      <input type="submit" name="submit" value="登录" />
    </form>
    """

    private static let indexJSON = """
    {
      "e": 0,
      "m": "ok",
      "d": {
        "termInfo": [
          {"year":"2026-2027","term":"1","startday":"2026-09-06","countweek":19,"id":"t1"}
        ],
        "params": {"year":"2026-2027","term":"1","week":8},
        "weekday": 0,
        "weekdays": ["2026-10-25","2026-10-26","2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31"]
      }
    }
    """

    private static let indexWithBadHistoryJSON = """
    {
      "e": 0,
      "m": "ok",
      "d": {
        "termInfo": [
          {"year":"2026-2027","term":"1","startday":"2026-9-6","countweek":19},
          {"year":"2024-2025","term":"3","startday":"bad-date","countweek":0}
        ],
        "params": {"year":"2026-2027","term":"1","week":8},
        "weekday": 0,
        "weekdays": ["2026-10-25","2026-10-26","2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31"]
      }
    }
    """

    private static let week8JSON = """
    {
      "e": 0,
      "m": "ok",
      "d": {
        "weekdays": ["2026-10-25","2026-10-26","2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31"],
        "classes": [
          {
            "c_1": [
              {
                "course_name": "概率论",
                "lessArr": [1, 3],
                "week": "1-16",
                "teacher": "张老师",
                "location": "南教楼 101",
                "course_id": "MATH201"
              }
            ],
            "extra_meta": "ignored"
          },
          { "c_6": [
              {
                "course_name": "操作系统",
                "lessArr": [6, 7],
                "week": "8,10周",
                "teacher": "李老师",
                "location": "工科楼 B202",
                "course_id": "CS301"
              }
            ]
          },
          {},
          {},
          {},
          {},
          {}
        ]
      }
    }
    """
}
