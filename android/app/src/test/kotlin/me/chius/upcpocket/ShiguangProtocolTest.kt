package me.chius.upcpocket

import kotlinx.coroutines.runBlocking
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpResponse
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.ShiguangAuthResult
import me.chius.upcpocket.core.auth.UpcShiguangAuthEndpoints
import me.chius.upcpocket.core.auth.UpcShiguangAuthenticator
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.feature.shiguang.ShiguangKind
import me.chius.upcpocket.feature.shiguang.ShiguangProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiguangProtocolTest {
    @Test
    fun `parses shiguang activity list, strips phones in detail and orders 5 summary modules`() {
        val listHtml = """
            <html><body>
            <div class="active_2_list">
              <div class="li">
                <a class="ellipsis" href="/actvMng/actvMngFront!jumpActvCampViewPageForWeb.action?actvModel.activityId=98765">科技文化节讲座</a>
                <a class="more" href="/actvMng/actvMngFront!jumpActvCampViewPageForWeb.action?actvModel.activityId=98765">详情</a>
                <div>地点：逸夫楼 活动时间：2026-11-22 学时：2</div>
              </div>
            </div>
            <div>共 3 页</div>
            </body></html>
        """.trimIndent()
        val page = ShiguangProtocol.parseList(listHtml, page = 1)
        assertEquals(3, page.totalPages)
        assertEquals(1, page.items.size)
        assertEquals(ShiguangKind.ACTIVITY, page.items.single().kind)
        assertEquals("98765", page.items.single().id)

        val detailHtml = """
            <div class="activeD_l">
              <div class="t2">科技文化节讲座</div>
              <div class="li">活动地点：逸夫楼报告厅</div>
              <div class="djs">欢迎参加。联系人：张老师 13800138000</div>
            </div>
        """.trimIndent()
        val detail = ShiguangProtocol.parseDetail(detailHtml)
        assertEquals("逸夫楼报告厅", detail.fields["活动地点"])
        assertFalse(detail.description.orEmpty().contains("13800138000"))

        val summaryHtml = """
            <form>
              <input id="credit0" value="10" /><input id="credit00" value="1.0" />
              <input id="credit1" value="8" /><input id="credit01" value="0.5" />
              <input id="credit2" value="6" /><input id="credit02" value="0.5" />
              <input id="credit3" value="12" /><input id="credit03" value="1.0" />
              <input id="credit4" value="4" /><input id="credit04" value="0.5" />
              <input id="flag" value="10,100,20,100,,,,,," />
              <input id="orderNum" value="5,4,3,2,1" />
            </form>
        """.trimIndent()
        val summary = ShiguangProtocol.parseSummary(summaryHtml)
        assertEquals(5, summary.modules.size)
        assertEquals(5, summary.modules.first().id)
        assertEquals("40", summary.totalHours?.stripTrailingZeros()?.toPlainString())
        assertTrue((summary.totalCredits?.toDouble() ?: 0.0) > 3.0)
    }

    @Test
    fun `verifies shiguang self identity via querySelectedHour json`() {
        val auth = UpcShiguangAuthenticator()
        assertEquals(
            UpcShiguangAuthenticator.IdentityCheckOutcome.Verified,
            auth.checkIdentityJson("""{"gridModel":[{"stuNo":"2300000001"}]}""", "2300000001"),
        )
        assertEquals(
            UpcShiguangAuthenticator.IdentityCheckOutcome.Mismatch,
            auth.checkIdentityJson("""{"gridModel":[{"stuNo":"2399999999"}]}""", "2300000001"),
        )
        assertEquals(
            UpcShiguangAuthenticator.IdentityCheckOutcome.Unverified,
            auth.checkIdentityJson("""{"gridModel":[]}""", "2300000001"),
        )
    }

    @Test
    fun `authenticator fails over via CASTGC without resubmitting password when cluster node emits ticket redirect`() = runBlocking {
        var passwordPosts = 0
        var casGetCount = 0
        val fakeCasForm = """
            <html><body>
            <form method="post">
              <input type="hidden" name="type" value="username_password" />
              <input type="hidden" name="_eventId" value="submit" />
              <input type="hidden" name="execution" value="exec-token-001" />
              <input type="password" name="password" />
            </form>
            </body></html>
        """.trimIndent()

        val transport = CasHttpTransport { req: CasHttpRequest ->
            when {
                req.url == UpcShiguangAuthEndpoints.LOGIN_URL && req.method == "GET" -> {
                    casGetCount += 1
                    val cookie = req.headers["Cookie"].orEmpty()
                    if ("CASTGC=TGT-1" in cookie) {
                        CasHttpResponse(
                            statusCode = 302,
                            location = "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-goodnode2",
                            setCookieHeaders = emptyList(),
                            body = "",
                        )
                    } else {
                        CasHttpResponse(
                            statusCode = 200,
                            location = null,
                            setCookieHeaders = listOf("_7da9a=1; Path=/cas; HttpOnly"),
                            body = fakeCasForm,
                        )
                    }
                }
                req.url == UpcShiguangAuthEndpoints.LOGIN_URL && req.method == "POST" -> {
                    passwordPosts += 1
                    CasHttpResponse(
                        statusCode = 302,
                        location = "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-badnode1",
                        setCookieHeaders = listOf("CASTGC=TGT-1; Path=/cas; HttpOnly; Secure"),
                        body = "",
                    )
                }
                req.url == "https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-badnode1" -> {
                    CasHttpResponse(
                        statusCode = 200,
                        location = null,
                        setCookieHeaders = listOf("SESSION=bad-session; Path=/; HttpOnly"),
                        body = """<script>var pcRedirectUrl = "/sysmng/login!loginByUserNoForCas.action?ticket=ST-badnode1";</script>""",
                    )
                }
                req.url == "https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-goodnode2" -> {
                    CasHttpResponse(
                        statusCode = 200,
                        location = null,
                        setCookieHeaders = listOf("SESSION=good-session; Path=/; HttpOnly"),
                        body = """<script>var pcRedirectUrl = "/sysmng/login!loginByUserNoForCas.action?userName=enc123";</script>""",
                    )
                }
                req.url == "https://dekt.upc.edu.cn/sysmng/login!loginByUserNoForCas.action?userName=enc123" -> {
                    CasHttpResponse(
                        statusCode = 302,
                        location = "http://dekt.upc.edu.cn/web/index.html",
                        setCookieHeaders = emptyList(),
                        body = "",
                    )
                }
                req.url == "https://dekt.upc.edu.cn/web/index.html" -> {
                    CasHttpResponse(statusCode = 200, location = null, setCookieHeaders = emptyList(), body = "<html>ok</html>")
                }
                req.url == UpcShiguangAuthEndpoints.LIST_URL -> {
                    val hasGood = req.headers["Cookie"].orEmpty().contains("SESSION=good-session")
                    if (hasGood) {
                        CasHttpResponse(
                            statusCode = 200,
                            location = null,
                            setCookieHeaders = emptyList(),
                            body = """<div class="active_2_list"></div>""",
                        )
                    } else {
                        CasHttpResponse(statusCode = 302, location = "/caslogin.jsp", setCookieHeaders = emptyList(), body = "")
                    }
                }
                req.url == UpcShiguangAuthEndpoints.REPORT_URL -> {
                    CasHttpResponse(
                        statusCode = 200,
                        location = null,
                        setCookieHeaders = emptyList(),
                        body = """{"gridModel":[{"stuNo":"2300000001"}]}""",
                    )
                }
                else -> CasHttpResponse(statusCode = 404, location = null, setCookieHeaders = emptyList(), body = "")
            }
        }

        val credential = SchoolLoginCredential("acct-1", 1L, "2300000001", "pass".toCharArray())
        val result = UpcShiguangAuthenticator(transport).authenticate(credential)
        assertTrue("Expected Success but got $result", result is ShiguangAuthResult.Success)
        val success = result as ShiguangAuthResult.Success
        assertTrue(success.identityVerified)
        assertEquals("good-session", success.cookies["SESSION"])
        assertEquals(1, passwordPosts)
        assertEquals(2, casGetCount)
    }

    @Test
    fun `authenticator retries cleanly when node serves exception alert page`() = runBlocking {
        var passwordPosts = 0
        val fakeCasForm = """
            <form method="post">
              <input type="hidden" name="type" value="username_password" />
              <input type="hidden" name="_eventId" value="submit" />
              <input type="hidden" name="execution" value="exec-token-002" />
              <input type="password" name="password" />
            </form>
        """.trimIndent()
        var ticketSeq = 0
        val transport = CasHttpTransport { req: CasHttpRequest ->
            when {
                req.url == UpcShiguangAuthEndpoints.LOGIN_URL && req.method == "GET" -> {
                    if ("CASTGC=TGT-2" in req.headers["Cookie"].orEmpty()) {
                        ticketSeq += 1
                        CasHttpResponse(302, "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-ok-00$ticketSeq", emptyList(), "")
                    } else {
                        CasHttpResponse(200, null, emptyList(), fakeCasForm)
                    }
                }
                req.url == UpcShiguangAuthEndpoints.LOGIN_URL && req.method == "POST" -> {
                    passwordPosts += 1
                    CasHttpResponse(302, "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-err-0", listOf("CASTGC=TGT-2; Path=/cas; HttpOnly"), "")
                }
                req.url.startsWith("https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-err-") -> {
                    CasHttpResponse(200, null, listOf("SESSION=err-sess; Path=/"), "<title>异常提醒</title>null 用户,您好")
                }
                req.url.startsWith("https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-ok-") -> {
                    CasHttpResponse(200, null, listOf("SESSION=ok-sess; Path=/"), """<script>var pcRedirectUrl = "/sysmng/login!loginByUserNoForCas.action?userName=enc999";</script>""")
                }
                req.url == "https://dekt.upc.edu.cn/sysmng/login!loginByUserNoForCas.action?userName=enc999" -> {
                    CasHttpResponse(302, "http://dekt.upc.edu.cn/web/index.html", emptyList(), "")
                }
                req.url == "https://dekt.upc.edu.cn/web/index.html" -> {
                    CasHttpResponse(200, null, emptyList(), "<html>ok</html>")
                }
                req.url == UpcShiguangAuthEndpoints.LIST_URL -> {
                    CasHttpResponse(200, null, emptyList(), """<div class="active_2_list"></div>""")
                }
                req.url == UpcShiguangAuthEndpoints.REPORT_URL -> {
                    CasHttpResponse(200, null, emptyList(), """{"gridModel":[{"stuNo":"2300000001"}]}""")
                }
                else -> CasHttpResponse(404, null, emptyList(), "")
            }
        }
        val credential = SchoolLoginCredential("acct-2", 1L, "2300000001", "pass".toCharArray())
        val result = UpcShiguangAuthenticator(transport).authenticate(credential)
        assertTrue("Expected Success but got $result", result is ShiguangAuthResult.Success)
        assertEquals(1, passwordPosts)
    }
}
