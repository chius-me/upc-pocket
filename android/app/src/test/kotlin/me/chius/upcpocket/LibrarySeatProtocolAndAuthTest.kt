package me.chius.upcpocket

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpResponse
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.UpcCasCookieJar
import me.chius.upcpocket.core.auth.UpcChaoxingAuthEndpoints
import me.chius.upcpocket.core.auth.UpcChaoxingAuthFailure
import me.chius.upcpocket.core.auth.UpcChaoxingAuthResult
import me.chius.upcpocket.core.auth.UpcChaoxingAuthenticator
import me.chius.upcpocket.core.auth.UpcLibraryAuthEndpoints
import me.chius.upcpocket.core.auth.UpcLibraryAuthFailure
import me.chius.upcpocket.core.auth.UpcLibraryAuthResult
import me.chius.upcpocket.core.auth.UpcLibraryAuthenticator
import me.chius.upcpocket.core.auth.UpcLibrarySigning
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.feature.library.ChaoxingException
import me.chius.upcpocket.feature.library.ChaoxingProtocol
import me.chius.upcpocket.feature.library.ChaoxingSeatKind
import me.chius.upcpocket.feature.library.LibraryBuilding
import me.chius.upcpocket.feature.library.LibraryConnectionStatus
import me.chius.upcpocket.feature.library.LibraryCredentialAccess
import me.chius.upcpocket.feature.library.LibrarySeatException
import me.chius.upcpocket.feature.library.LibrarySeatProtocol
import me.chius.upcpocket.feature.library.LibrarySeatRepository
import me.chius.upcpocket.feature.library.LibrarySessionExpiredException
import me.chius.upcpocket.feature.library.LibrarySystemTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySeatProtocolAndAuthTest {
    private val sampleAccountId = "acct_test_library_01"
    private val sampleStudentId = "22010101"

    @Test
    fun `libseat signing matches cross-platform AES key length and HMAC test vectors`() {
        val key = UpcLibrarySigning.configKey()
        assertEquals(9, key.size)

        val uuid = "00000000-0000-4000-8000-000000000000"
        val ts = 1700000000000L
        assertEquals(
            "d96340212c47c2f7c2c843bbf99e0a53f90d41fa8e0505c4ffc16c407f968897",
            UpcLibrarySigning.sign("GET", uuid, ts, key),
        )
        assertEquals(
            "c62c4b092c8d71425cc29e479bba3fb4120a89d3fbeb35ebf97f7aa5bfb1a8e1",
            UpcLibrarySigning.sign("POST", uuid, ts, key),
        )
        val headers = UpcLibrarySigning.signedHeaders("tok-1", "GET", uuid, ts, key)
        assertEquals("tok-1", headers["Authorization"])
        assertEquals("PC", headers["loginType"])
    }

    @Test
    fun `LibrarySeatProtocol parses read-only payloads and preserves error semantics`() {
        val filters = LibrarySeatProtocol.parseFilters(
            """{"status":"success","code":"0","data":{"dates":["2026-11-21"],"buildings":[["1","唐岛湾主馆"],{"id":"2","name":"南堂"}]}}""",
        )
        assertEquals(listOf("2026-11-21"), filters.dates)
        assertEquals(2, filters.buildings.size)

        val floors = LibrarySeatProtocol.parseFloors(
            """{"status":"success","data":[{"floorNum":2,"floorName":"二楼"}]}""",
        )
        assertEquals("二楼", floors.single().floorName)

        val rooms = LibrarySeatProtocol.parseRooms(
            """{"status":"success","data":[{"roomId":"101","room":"二楼自习区","floor":2,"totalSeats":120},{"roomId":"102","room":"二楼报刊区","floor":2,"totalSeats":80,"free":25,"maxHour":4}]}""",
            building = LibraryBuilding("1", "唐岛湾主馆"),
            floorNamesByNum = mapOf(2 to "二楼"),
        )
        assertEquals(2, rooms.size)
        assertNull(rooms[0].freeSeats)
        assertEquals("共 120 座", rooms[0].remainingLabel)
        assertEquals("余 25 / 共 80 座", rooms[1].remainingLabel)
        assertEquals("学校单次最长 4 小时", rooms[1].maxDurationLabel)

        val layout = LibrarySeatProtocol.parseLayout(
            """{"status":"success","data":{"rows":4,"cols":5,"layout":{"2003":{"id":"s1","name":"A01","type":"seat","enabled":"true","status":"FREE","local":"0"}}}}""",
        )
        assertEquals(2, layout.seats.single().row)
        assertEquals(3, layout.seats.single().col)
        assertTrue(layout.seats.single().selectable)

        assertTrue(
            LibrarySeatProtocol.parseCurrentReservations("""{"status":"success","data":null}""").isEmpty(),
        )
        val history = LibrarySeatProtocol.parseHistoryReservations(
            """{"status":"success","data":{"reservations":[{"id":"r1","date":"2026-11-20","begin":"08:00","end":"12:00","location":"二楼-A01","status":"COMPLETE"}]}}""",
        )
        assertEquals("已履约", history.single().statusLabel)

        var badCurrentThrown = false
        try {
            LibrarySeatProtocol.parseCurrentReservations("""{"status":"success","data":{"unexpected":1}}""")
        } catch (_: LibrarySeatException) {
            badCurrentThrown = true
        }
        assertTrue(badCurrentThrown)

        var expiredThrown = false
        try {
            LibrarySeatProtocol.parseFilters("""{"code":12,"status":"fail"}""")
        } catch (_: LibrarySessionExpiredException) {
            expiredThrown = true
        }
        assertTrue(expiredThrown)

        var clockMismatchThrown = false
        try {
            LibrarySeatProtocol.parseFilters("""{"code":"20","message":"请检查手机时间"}""")
        } catch (e: LibrarySeatException) {
            clockMismatchThrown = e.message.orEmpty().contains("校准设备时间")
        }
        assertTrue(clockMismatchThrown)
    }

    @Test
    fun `ChaoxingProtocol parses balanced identity, places, used seats, and intervals`() {
        val user = ChaoxingProtocol.parseIdentity(
            """<script>var userLoginInfo={"nested":{"a":1},"userInfo":{"sno":"$sampleStudentId"}}||{};</script>""",
        )
        assertEquals(sampleStudentId, user.sno)

        val rooms = ChaoxingProtocol.parseSeatRooms(
            """{"success":true,"data":{"totalRow":1,"totalPage":1,"seatRoomList":[{"id":12,"firstLevelName":"东区","secondLevelName":"二层","thirdLevelName":"A区","capacity":100,"curUsedCount":40,"seatSpecialTime":{"satStartTime":"08:00","satEndTime":"22:00"}}]}}""",
            kind = ChaoxingSeatKind.SEAT,
            date = "2026-11-21",
        )
        assertEquals(1, rooms.rooms.size)
        assertEquals(60, rooms.rooms.single().remainingEstimate)

        val detail = ChaoxingProtocol.parseSeatRoomDetail(
            """{"success":true,"data":{"seatRoom":{"id":12,"firstLevelName":"东区","secondLevelName":"二层","capacity":100,"isOpen":0,"seatSpecialTime":{"satStartTime":"08:00","satEndTime":"22:00"}},"seatConfig":{"timeUnit":30,"minReserveDuration":1.0,"reserveDuration":4.0},"seatAttributes":[{"seatNum":"01","isReserve":0},{"seatNum":"02","isReserve":1}]}}""",
            date = "2026-11-21",
            expectedRoomId = 12,
        )
        assertEquals(setOf("01"), detail.disabledSeatNums)
        assertEquals(240, detail.maxDurationMinutes)

        val places = ChaoxingProtocol.parseSeatPlaces(
            """{"success":true,"data":[{"seatNum":"01","xleft":166.5,"ytop":75.25,"width":40.0,"height":40.0,"rotate":630.0}]}""",
        )
        assertEquals(270f, places.single().rotate, 0.01f)

        val used = ChaoxingProtocol.parseUsedSeatNums(
            """{"success":true,"data":{"seatReserves":[{"seatNum":"02"}]}}""",
        )
        assertEquals(setOf("02"), used)

        val times = ChaoxingProtocol.parseUsedTimes(
            """{"success":true,"data":[[1795226400000,1795240800000]]}""",
        )
        assertEquals(1, times.size)
        assertTrue(times.single().endMinute > times.single().startMinute)

        var dupRejected = false
        try {
            ChaoxingProtocol.parseSeatPlaces(
                """{"success":true,"data":[{"seatNum":"01","xleft":10.0,"ytop":10.0,"width":20.0,"height":20.0},{"seatNum":"01","xleft":40.0,"ytop":10.0,"width":20.0,"height":20.0}]}""",
            )
        } catch (_: ChaoxingException) {
            dupRejected = true
        }
        assertTrue(dupRejected)
    }

    @Test
    fun `UpcLibraryAuthenticator exchanges token, verifies self, and halts on captcha or mismatch`() = runBlocking {
        val casUrl = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Flibspace.upc.edu.cn%2Fremote%2Fstatic%2FcasAuth%2FgetServiceByVerifyTicket%2FcasLogin"
        val callbackUrl = "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin?ticket=ST-12345678"
        var posts = 0

        val okTransport = CasHttpTransport { req ->
            if (req.method == "POST") posts++
            when {
                req.url == UpcLibraryAuthEndpoints.ENTRY_URL ->
                    CasHttpResponse(302, casUrl, emptyList(), "")
                req.url == casUrl && req.method == "GET" ->
                    CasHttpResponse(200, null, emptyList(), casFormHtml(casUrl))
                req.url == casUrl && req.method == "POST" ->
                    CasHttpResponse(302, callbackUrl, emptyList(), "")
                req.url == callbackUrl ->
                    CasHttpResponse(302, "https://libspace.upc.edu.cn/libseat/#/login?token=app-tok-9", emptyList(), "")
                req.url.startsWith(UpcLibraryAuthEndpoints.SSO_AUTH_URL) -> {
                    assertEquals("app-tok-9", req.headers["Authorization"])
                    CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"token":"seat-tok-9"}}""")
                }
                req.url.startsWith(UpcLibraryAuthEndpoints.USER_URL) -> {
                    assertEquals("seat-tok-9", req.headers["Authorization"])
                    CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"id":"u1","username":"$sampleStudentId","name":"测试同学"}}""")
                }
                else -> error("Unexpected request ${req.method} ${req.url}")
            }
        }

        val auth = UpcLibraryAuthenticator(okTransport)
        val okRes = auth.authenticate(sampleCredential(sampleStudentId))
        val success = okRes as UpcLibraryAuthResult.Success
        assertEquals("seat-tok-9", success.seatToken)
        assertFalse(success.toString().contains("seat-tok-9"))
        assertEquals(2, posts)

        val mismatchTransport = CasHttpTransport { req ->
            when {
                req.url == UpcLibraryAuthEndpoints.ENTRY_URL -> CasHttpResponse(302, casUrl, emptyList(), "")
                req.url == casUrl && req.method == "GET" -> CasHttpResponse(200, null, emptyList(), casFormHtml(casUrl))
                req.url == casUrl && req.method == "POST" -> CasHttpResponse(302, callbackUrl, emptyList(), "")
                req.url == callbackUrl -> CasHttpResponse(302, "https://libspace.upc.edu.cn/libseat/#/login?token=app-tok-9", emptyList(), "")
                req.url.startsWith(UpcLibraryAuthEndpoints.SSO_AUTH_URL) -> CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"token":"seat-tok-9"}}""")
                req.url.startsWith(UpcLibraryAuthEndpoints.USER_URL) -> CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"id":"u2","username":"99999999","name":"他人"}}""")
                else -> error("Unexpected request")
            }
        }
        val mismatchRes = UpcLibraryAuthenticator(mismatchTransport).authenticate(sampleCredential(sampleStudentId))
        assertEquals(UpcLibraryAuthFailure.IDENTITY_MISMATCH, (mismatchRes as UpcLibraryAuthResult.Failure).reason)

        var captchaPosts = 0
        val captchaTransport = CasHttpTransport { req ->
            if (req.method == "POST") captchaPosts++
            when (req.url) {
                UpcLibraryAuthEndpoints.ENTRY_URL -> CasHttpResponse(302, casUrl, emptyList(), "")
                casUrl -> CasHttpResponse(200, null, emptyList(), casFormHtml(casUrl, captcha = true))
                else -> error("Should stop before POST")
            }
        }
        val captchaRes = UpcLibraryAuthenticator(captchaTransport).authenticate(sampleCredential(sampleStudentId))
        assertEquals(UpcLibraryAuthFailure.INTERACTION_REQUIRED, (captchaRes as UpcLibraryAuthResult.Failure).reason)
        assertEquals(0, captchaPosts)
    }

    @Test
    fun `UpcChaoxingAuthenticator handles parent chaoxing cookie domain and blocks foreign hops`() = runBlocking {
        val jar = UpcCasCookieJar(
            allowedHosts = setOf(
                UpcChaoxingAuthEndpoints.CAS_HOST,
                UpcChaoxingAuthEndpoints.OFFICE_HOST,
                UpcChaoxingAuthEndpoints.CHAOXING_COOKIE_DOMAIN,
            ),
        )
        jar.saveFromHeaders(
            "https://passport2-api.chaoxing.com/api/v2/casreceive",
            listOf("fid=fid1; Domain=.chaoxing.com; Path=/; Secure"),
        )
        // passport2 host not in this minimal jar's saveFromHeaders allowedHosts, so test allowed save host:
        jar.saveFromHeaders(
            "https://office.chaoxing.com/front/third/apps/seat/index",
            listOf("fid=fid1; Domain=.chaoxing.com; Path=/; Secure", "JSESSIONID=js1; Path=/; Secure"),
        )
        val hdr = jar.cookieHeader(UpcChaoxingAuthEndpoints.SEAT_ENTRY).orEmpty()
        assertTrue(hdr.contains("fid=fid1"))
        assertTrue(hdr.contains("JSESSIONID=js1"))

        val initUrl = "https://tyrzfw.chaoxing.com/auth3/upc/cas/init?refer=https%3A%2F%2Foffice.chaoxing.com%2Ffront%2Fapps%2Fseat%2Findex%3FfidEnc%3De32f8fbde51f9dba&mappId=4197453"
        val casUrl = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Ftyrzfw.chaoxing.com%2Fauth3%2Fupc%2Fcas%2Findex"
        val cbUrl = "https://tyrzfw.chaoxing.com/auth3/upc/cas/index?ticket=ST-chaoxing-123"
        val passportUrl = "https://passport2-api.chaoxing.com/api/v2/casreceive?thirdinfo=abc&refer=https%3A%2F%2Foffice.chaoxing.com%2Ffront%2Fapps%2Fseat%2Findex%3FfidEnc%3De32f8fbde51f9dba&setmain=true"
        val officeRefer = "https://office.chaoxing.com/front/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453"
        var entryCalls = 0

        val transport = CasHttpTransport { req ->
            when {
                req.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY -> {
                    if (entryCalls++ == 0) {
                        CasHttpResponse(303, initUrl, emptyList(), "")
                    } else {
                        assertTrue(req.headers["Cookie"].orEmpty().contains("fid=cx-fid"))
                        assertTrue(req.headers["Cookie"].orEmpty().contains("JSESSIONID=cx-js"))
                        assertFalse(req.headers["Cookie"].orEmpty().contains("CASTGC"))
                        CasHttpResponse(200, null, emptyList(), """<script>var userLoginInfo={"userInfo":{"sno":"$sampleStudentId"}};</script>""")
                    }
                }
                req.url == initUrl -> CasHttpResponse(200, null, emptyList(), """<script>window.location="$casUrl";</script>""")
                req.url == casUrl && req.method == "GET" -> CasHttpResponse(200, null, emptyList(), casFormHtml(casUrl))
                req.url == casUrl && req.method == "POST" ->
                    CasHttpResponse(302, cbUrl, listOf("CASTGC=cas-secret; Path=/cas/; Secure"), "")
                req.url == cbUrl -> CasHttpResponse(200, null, emptyList(), """<script>window.location="$passportUrl";</script>""")
                req.url == passportUrl -> CasHttpResponse(
                    200,
                    null,
                    listOf("fid=cx-fid; Domain=.chaoxing.com; Path=/; Secure"),
                    """<input type="hidden" id="refer" value="$officeRefer">""",
                )
                req.url == officeRefer ->
                    CasHttpResponse(302, UpcChaoxingAuthEndpoints.SEAT_ENTRY, listOf("JSESSIONID=cx-js; Path=/; Secure"), "")
                else -> error("Unexpected request ${req.url}")
            }
        }

        val okRes = UpcChaoxingAuthenticator(transport).authenticate(sampleCredential(sampleStudentId)) as UpcChaoxingAuthResult.Success
        assertFalse(okRes.toString().contains("cx-fid"))
        assertTrue(okRes.cookieHeader.contains("fid=cx-fid"))

        val evilTransport = CasHttpTransport { req ->
            when (req.url) {
                UpcChaoxingAuthEndpoints.SEAT_ENTRY -> CasHttpResponse(303, "https://evil.example.com/auth3/upc/cas/init", emptyList(), "")
                else -> error("Must reject foreign init hop")
            }
        }
        val badRes = UpcChaoxingAuthenticator(evilTransport).authenticate(sampleCredential(sampleStudentId)) as UpcChaoxingAuthResult.Failure
        assertEquals(UpcChaoxingAuthFailure.PROTOCOL_ERROR, badRes.reason)
    }

    @Test
    fun `LibrarySeatRepository drops late response on account switch and validates WebView adoption`() = runBlocking {
        val currentState = AtomicReference(
            SchoolAccountState(
                status = AccountStatus.SAVED_UNVERIFIED,
                generation = 1L,
                accountId = sampleAccountId,
                maskedLoginName = "22******01",
                isValidStudentId = true,
            ),
        )
        val firstFiltersStarted = CompletableDeferred<Unit>()
        val releaseFilters = CompletableDeferred<Unit>()

        val casUrl = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Flibspace.upc.edu.cn%2Fremote%2Fstatic%2FcasAuth%2FgetServiceByVerifyTicket%2FcasLogin"
        val cbUrl = "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin?ticket=ST-99999999"

        val transport = CasHttpTransport { req ->
            when {
                req.url == UpcLibraryAuthEndpoints.ENTRY_URL -> CasHttpResponse(302, casUrl, emptyList(), "")
                req.url == casUrl && req.method == "GET" -> CasHttpResponse(200, null, emptyList(), casFormHtml(casUrl))
                req.url == casUrl && req.method == "POST" -> CasHttpResponse(302, cbUrl, emptyList(), "")
                req.url == cbUrl -> CasHttpResponse(302, "https://libspace.upc.edu.cn/libseat/#/login?token=app-t", emptyList(), "")
                req.url.startsWith(UpcLibraryAuthEndpoints.SSO_AUTH_URL) -> CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"token":"seat-t"}}""")
                req.url.startsWith(UpcLibraryAuthEndpoints.USER_URL) -> CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"id":"1","username":"$sampleStudentId","name":"A"}}""")
                req.url.contains("/rest/v2/free/filters") -> {
                    firstFiltersStarted.complete(Unit)
                    releaseFilters.await()
                    CasHttpResponse(200, null, emptyList(), """{"status":"success","data":{"dates":["2026-11-21"],"buildings":[["1","主馆"]]}}""")
                }
                else -> CasHttpResponse(200, null, emptyList(), """{"status":"success","data":[]}""")
            }
        }

        val repo = LibrarySeatRepository(
            accountStateProvider = { currentState.get() },
            matchesAccountGeneration = { accId, gen ->
                val s = currentState.get()
                s.status == AccountStatus.SAVED_UNVERIFIED && s.accountId == accId && s.generation == gen
            },
            credentialAccess = object : LibraryCredentialAccess {
                override suspend fun <T> withCredential(block: suspend (SchoolLoginCredential) -> T): T? {
                    val s = currentState.get()
                    return if (s.status != AccountStatus.SAVED_UNVERIFIED) {
                        null
                    } else {
                        block(sampleCredential(sampleStudentId, s.accountId!!, s.generation))
                    }
                }
            },
            transport = transport,
        )
        repo.syncAccountStatusAndCampus(me.chius.upcpocket.core.model.CampusPreference.TANGDAOWAN)
        assertEquals(LibrarySystemTab.LIBSEAT, repo.uiState.value.activeTab)

        val job = async { repo.refreshActiveSystem() }
        firstFiltersStarted.await()

        // Switch/clear account while filters call is still in-flight
        currentState.set(SchoolAccountState(status = AccountStatus.UNSET, generation = 2L, accountId = null))
        repo.onAccountInvalidated(AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED)
        releaseFilters.complete(Unit)
        job.await()

        assertEquals(LibraryConnectionStatus.UNCONFIGURED, repo.uiState.value.connectionStatus)
        assertTrue(repo.uiState.value.libseatRooms.isEmpty())

        // Stale WebView context from generation 1 must be ignored
        val staleCtx = ControlledWebViewContract.SessionContext(
            accountId = sampleAccountId,
            generation = 1L,
            entryUrl = UpcLibraryAuthEndpoints.ENTRY_URL,
        )
        repo.adoptLibseatWebViewToken("https://libspace.upc.edu.cn/libseat/#/login?token=app-t", staleCtx)
        assertEquals(LibraryConnectionStatus.UNCONFIGURED, repo.uiState.value.connectionStatus)
    }

    private fun sampleCredential(
        loginName: String,
        accountId: String = sampleAccountId,
        generation: Long = 1L,
    ) = SchoolLoginCredential(
        accountId = accountId,
        generation = generation,
        loginName = loginName,
        password = "synthetic-password".toCharArray(),
    )

    private fun casFormHtml(actionUrl: String, captcha: Boolean = false): String {
        val captchaSnippet = if (captcha) """<img src="/cas/captcha.jpg" id="captchaImg"/>""" else ""
        return """
            <html><body>
            <form action="$actionUrl" method="post">
              <input type="text" name="username"/>
              <input type="password" name="password"/>
              <input type="hidden" name="type" value="username_password"/>
              <input type="hidden" name="execution" value="e1s1"/>
              <input type="hidden" name="_eventId" value="submit"/>
              <input type="submit" name="submit" value="登录"/>
              $captchaSnippet
            </form>
            </body></html>
        """.trimIndent()
    }
}
