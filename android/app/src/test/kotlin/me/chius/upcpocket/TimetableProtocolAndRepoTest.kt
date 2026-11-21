package me.chius.upcpocket

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpResponse
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.TimetableAuthResult
import me.chius.upcpocket.core.auth.UpcTimetableAuthEndpoints
import me.chius.upcpocket.core.auth.UpcTimetableAuthenticator
import me.chius.upcpocket.core.data.CredentialCipher
import me.chius.upcpocket.core.data.CredentialCryptoException
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.feature.timetable.TimetableCacheStore
import me.chius.upcpocket.feature.timetable.TimetableConnectionStatus
import me.chius.upcpocket.feature.timetable.TimetableParseResult
import me.chius.upcpocket.feature.timetable.TimetableProtocol
import me.chius.upcpocket.feature.timetable.TimetableRepository
import me.chius.upcpocket.feature.timetable.TimetableSchedule
import me.chius.upcpocket.feature.timetable.TimetableSession
import me.chius.upcpocket.feature.timetable.TimetableSessionStore
import me.chius.upcpocket.feature.timetable.TimetableTermInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimetableProtocolAndRepoTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upc-timetable-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `official class schedule covers 1 to 12 and leaves 13 to 14 null`() {
        assertEquals("08:00–08:45", TimetableSchedule.resolveTimeRange(1, 1))
        assertEquals("08:00–09:35", TimetableSchedule.resolveTimeRange(1, 2))
        assertEquals("14:00–17:30", TimetableSchedule.resolveTimeRange(6, 9))
        assertEquals("19:00–21:25", TimetableSchedule.resolveTimeRange(10, 12))
        assertNull(TimetableSchedule.resolveTimeRange(13, 14))
        assertEquals("08:15–09:50", TimetableSchedule.resolveTimeRange(1, 2, "08:15-09:50"))
    }

    @Test
    fun `parseIndex skips malformed historical term, normalizes Sunday 0 to 7, and rejects boolean code`() {
        val result = TimetableProtocol.parseIndex(INDEX_WITH_BAD_HISTORY_JSON)
        assertTrue(result is TimetableParseResult.Ok)
        val index = (result as TimetableParseResult.Ok).value
        assertEquals(1, index.terms.size)
        assertEquals("2026-2027", index.terms.single().year)
        assertEquals(8, index.currentWeek)
        assertEquals(7, index.currentWeekday)

        assertEquals(
            TimetableParseResult.AuthRequired,
            TimetableProtocol.parseIndex("""{"e":10013,"m":"not login"}"""),
        )
        assertTrue(
            TimetableProtocol.parseIndex("""{"e":false,"d":{"termInfo":[]}}""")
                is TimetableParseResult.UpstreamError,
        )
    }

    @Test
    fun `parseWeek splits non-contiguous lessArr, checks week dates alignment and week range`() {
        val term = TimetableTermInfo(
            year = "2026-2027",
            term = "1",
            startDayIso = "2026-09-06",
            totalWeeks = 19,
            label = "2026-2027 秋季",
        )
        val okResult = TimetableProtocol.parseWeek(WEEK_8_JSON, term, requestedWeek = 8, fetchedAtEpochMillis = 999L)
        assertTrue(okResult is TimetableParseResult.Ok)
        val week = (okResult as TimetableParseResult.Ok).value
        assertEquals(7, week.days.size)
        assertEquals("2026-10-25", week.datesIso.first())

        val splitMeetings = week.meetings.filter { it.courseName == "概率论" }
        assertEquals(2, splitMeetings.size)
        assertEquals(listOf(listOf(1), listOf(3)), splitMeetings.map { it.periods })
        assertEquals(listOf(1, 3), splitMeetings.first().sourcePeriods)
        assertEquals("lessArr_split_noncontiguous", splitMeetings.first().sectionsSource)
        assertEquals("08:00–08:45", splitMeetings[0].effectiveTimeText)

        // Mismatched week number vs weekday start date must fail.
        assertTrue(TimetableProtocol.parseWeek(WEEK_8_JSON, term, requestedWeek = 9) is TimetableParseResult.UpstreamError)
    }

    @Test
    fun `repository restores offline cache, recovers once on 10013, and isolates account switch`() = runTest {
        val accountDir = File(tempDir, "account")
        val timetableDir = File(tempDir, "timetable")
        val cipher = FakeCipher()
        var now = 10_000L

        val accountRepo = SchoolAccountRepository(accountDir, cipher, clock = { now })
        accountRepo.save("2024010101", "fixture-pass".toCharArray())
        val state1 = accountRepo.state.value
        val accountId1 = state1.accountId!!

        val sessionStore = TimetableSessionStore(timetableDir, cipher)
        val cacheStore = TimetableCacheStore(timetableDir)

        // Pre-seed stale session to verify 1-shot CAS recovery.
        sessionStore.save(
            TimetableSession(
                accountId = accountId1,
                credentialGeneration = state1.generation,
                savedAtEpochMillis = now,
                cookies = mapOf("eai-sess" to "stale-cookie"),
            ),
        )

        var weekAuthRetries = 0
        val transport = CasHttpTransport { req ->
            when {
                req.url == UpcTimetableAuthEndpoints.INDEX_URL -> {
                    val cookie = req.headers["Cookie"].orEmpty()
                    if (cookie.contains("stale-cookie")) {
                        CasHttpResponse(200, null, emptyList(), """{"e":10013,"m":"expired"}""")
                    } else {
                        CasHttpResponse(200, null, emptyList(), INDEX_JSON)
                    }
                }
                req.url == UpcTimetableAuthEndpoints.LOGIN_URL && req.method == "GET" -> {
                    CasHttpResponse(200, null, emptyList(), CAS_FORM_HTML)
                }
                req.url.startsWith("https://cas.upc.edu.cn/cas/login") && req.method == "POST" -> {
                    weekAuthRetries++
                    CasHttpResponse(
                        statusCode = 302,
                        location = "https://app.upc.edu.cn/a_upc/api/cas/login" +
                            "?ticket=ST-fixture-123456&redirect=" +
                            "https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex&from=wap",
                        setCookieHeaders = emptyList(),
                        body = "",
                    )
                }
                req.url.startsWith("https://app.upc.edu.cn/a_upc/api/cas/login") -> {
                    CasHttpResponse(
                        statusCode = 302,
                        location = UpcTimetableAuthEndpoints.PAGE_URL,
                        setCookieHeaders = listOf("eai-sess=fresh-cookie; Path=/; Secure"),
                        body = "",
                    )
                }
                req.url == UpcTimetableAuthEndpoints.PAGE_URL -> {
                    CasHttpResponse(200, null, emptyList(), "ok")
                }
                req.url == UpcTimetableAuthEndpoints.WEEK_URL -> {
                    CasHttpResponse(200, null, emptyList(), WEEK_8_JSON)
                }
                else -> error("Unexpected ${req.method} ${req.url}")
            }
        }

        val repo = TimetableRepository(
            accountStateProvider = { accountRepo.state.value },
            matchesAccountGeneration = accountRepo::matchesAccountGeneration,
            credentialExecutor = { block -> accountRepo.withLoginCredential(block) },
            sessionStore = sessionStore,
            cacheStore = cacheStore,
            authenticator = UpcTimetableAuthenticator(transport),
            transport = transport,
            clearWebViewCookies = {},
            clock = { now },
            todayProvider = { LocalDate.of(2026, 10, 25) },
        )
        accountRepo.addInvalidationListener { reason -> repo.onAccountInvalidated(reason) }

        repo.refresh()
        assertEquals(1, weekAuthRetries)
        assertEquals(TimetableConnectionStatus.ONLINE, repo.uiState.value.connectionStatus)
        assertEquals(8, repo.uiState.value.selectedWeek)
        assertNotNull(repo.uiState.value.weekData)
        assertNotNull(repo.uiState.value.todaySummary)
        assertEquals(2, repo.uiState.value.todaySummary!!.courses.size)

        // Updating password clears session while keeping offline cache for same accountId.
        now = 20_000L
        accountRepo.save("2024010101", "fixture-pass-2".toCharArray())
        assertNull(sessionStore.load(accountId1, accountRepo.state.value.generation))
        assertEquals(TimetableConnectionStatus.OFFLINE_CACHE, repo.uiState.value.connectionStatus)
        assertNotNull(repo.uiState.value.weekData)

        // Switching to a second account wipes cache + session completely.
        now = 30_000L
        accountRepo.save("TS240100001", "fixture-pass-3".toCharArray())
        assertEquals(TimetableConnectionStatus.IDLE, repo.uiState.value.connectionStatus)
        assertNull(repo.uiState.value.weekData)
        assertNull(cacheStore.load(accountId1))
    }

    @Test
    fun `account change during in-flight refresh drops late result and adopts WebView cookies`() = runTest {
        val accountDir = File(tempDir, "account2")
        val timetableDir = File(tempDir, "timetable2")
        val cipher = FakeCipher()
        val accountRepo = SchoolAccountRepository(accountDir, cipher, clock = { 50_000L })
        accountRepo.save("2024010101", "fixture-pass".toCharArray())
        val state1 = accountRepo.state.value
        val accountId1 = state1.accountId!!

        val sessionStore = TimetableSessionStore(timetableDir, cipher)
        val cacheStore = TimetableCacheStore(timetableDir)

        val requestStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        var webViewCleared = 0

        val transport = CasHttpTransport { req ->
            when {
                req.url == UpcTimetableAuthEndpoints.LOGIN_URL && req.method == "GET" -> {
                    requestStarted.complete(Unit)
                    allowResponse.await()
                    CasHttpResponse(200, null, emptyList(), "<script>config.captcha = { id: 'late' };</script>")
                }
                req.url == UpcTimetableAuthEndpoints.INDEX_URL -> {
                    CasHttpResponse(200, null, emptyList(), INDEX_JSON)
                }
                req.url == UpcTimetableAuthEndpoints.WEEK_URL -> {
                    CasHttpResponse(200, null, emptyList(), WEEK_8_JSON)
                }
                else -> error("Unexpected ${req.method} ${req.url}")
            }
        }

        val repo = TimetableRepository(
            accountStateProvider = { accountRepo.state.value },
            matchesAccountGeneration = accountRepo::matchesAccountGeneration,
            credentialExecutor = { block -> accountRepo.withLoginCredential(block) },
            sessionStore = sessionStore,
            cacheStore = cacheStore,
            authenticator = UpcTimetableAuthenticator(transport),
            transport = transport,
            clearWebViewCookies = { webViewCleared++ },
            clock = { 50_000L },
            todayProvider = { LocalDate.of(2026, 10, 25) },
        )
        accountRepo.addInvalidationListener { reason -> repo.onAccountInvalidated(reason) }

        val job = async { repo.refresh() }
        requestStarted.await()
        // Switch account while first refresh is suspended inside network I/O.
        accountRepo.save("TS240100001", "fixture-pass-2".toCharArray())
        allowResponse.complete(Unit)
        job.await()

        assertNull(repo.uiState.value.interactionSession)
        assertNull(cacheStore.load(accountId1))

        // Verify adopting valid WebView cookies verifies index, saves session, and fetches week.
        val state2 = accountRepo.state.value
        val ctx = ControlledWebViewContract.SessionContext(
            accountId = state2.accountId,
            generation = state2.generation,
            entryUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
        )
        repo.adoptWebViewCookies(mapOf("eai-sess" to "webview-cookie-ok"), ctx)
        assertTrue(webViewCleared > 0)
        assertEquals(TimetableConnectionStatus.ONLINE, repo.uiState.value.connectionStatus)
        assertEquals("webview-cookie-ok", sessionStore.load(state2.accountId!!, state2.generation)?.cookies?.get("eai-sess"))
    }

    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            byteArrayOf(0x51) + plaintext.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (ciphertext.size < 2 || ciphertext[0] != 0x51.toByte()) {
                throw CredentialCryptoException("bad")
            }
            return ciphertext.drop(1).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
        }
    }

    private companion object {
        val CAS_FORM_HTML = """
            <form id="fm1" action="${UpcTimetableAuthEndpoints.LOGIN_URL}" method="post">
              <input type="text" name="username" value="" />
              <input type="password" name="password" value="" />
              <input type="hidden" name="type" value="username_password" />
              <input type="hidden" name="execution" value="e1s1" />
              <input type="hidden" name="_eventId" value="submit" />
              <input type="submit" name="submit" value="登录" />
            </form>
        """.trimIndent()

        const val INDEX_JSON = """
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

        const val INDEX_WITH_BAD_HISTORY_JSON = """
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

        const val WEEK_8_JSON = """
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
}
