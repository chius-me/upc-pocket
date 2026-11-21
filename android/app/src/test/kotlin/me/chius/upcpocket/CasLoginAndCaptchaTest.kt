package me.chius.upcpocket

import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.core.auth.CasCaptchaChallengeDetector
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpResponse
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasLoginResult
import me.chius.upcpocket.core.auth.TimetableAuthResult
import me.chius.upcpocket.core.auth.UpcCasLogin
import me.chius.upcpocket.core.auth.UpcCasService
import me.chius.upcpocket.core.auth.UpcTimetableAuthEndpoints
import me.chius.upcpocket.core.auth.UpcTimetableAuthenticator
import me.chius.upcpocket.core.model.SchoolLoginCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CasLoginAndCaptchaTest {

    @Test
    fun `captcha detector distinguishes inactive script placeholders from active challenges`() {
        val benignHtml = """
            <html>
              <form><input type="hidden" name="captcha"></form>
              <!-- <input name="captcha"> -->
              <div id="captchaParent" style="display:none"><input name="captcha"></div>
              <script>
                const sel = "input[name='captcha']";
                // config.captcha = { id: 'commented' };
                config.captcha = null;
                config.captcha = false;
                config.captcha = undefined;
              </script>
            </html>
        """.trimIndent()
        assertFalse(CasCaptchaChallengeDetector.inspect(benignHtml).active)

        val scriptConfig = CasCaptchaChallengeDetector.inspect(
            """<script>config.captcha = { id: 'cap-fixture-1' };</script>""",
        )
        assertTrue(scriptConfig.active)
        assertEquals("cap-fixture-1", scriptConfig.captchaId)

        assertTrue(CasCaptchaChallengeDetector.inspect("<script>config.captcha ||= false;</script>").active)
        assertTrue(CasCaptchaChallengeDetector.inspect("<form><input name='captcha'></form>").active)
        assertTrue(
            CasCaptchaChallengeDetector.inspect(
                "<div id='captchaParent' style='display:none;display:block'></div>",
            ).active,
        )
    }

    @Test
    fun `successful CAS login follows callback and exports only app host cookies`() = runTest {
        val requests = mutableListOf<CasHttpRequest>()
        val transport = CasHttpTransport { req ->
            requests += req
            when {
                req.method == "GET" && req.url.startsWith("https://cas.upc.edu.cn/cas/login") -> {
                    CasHttpResponse(
                        statusCode = 200,
                        location = null,
                        setCookieHeaders = listOf("JSESSIONID=cas-temp-1; Path=/cas; HttpOnly; Secure"),
                        body = validCasFormHtml(action = UpcTimetableAuthEndpoints.LOGIN_URL),
                    )
                }
                req.method == "POST" && req.url.startsWith("https://cas.upc.edu.cn/cas/login") -> {
                    assertTrue(req.headers["Cookie"].orEmpty().contains("JSESSIONID=cas-temp-1"))
                    assertEquals("https://cas.upc.edu.cn", req.headers["Origin"])
                    CasHttpResponse(
                        statusCode = 302,
                        location = "https://app.upc.edu.cn/a_upc/api/cas/login" +
                            "?ticket=ST-fixture-123456&redirect=" +
                            "https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex&from=wap",
                        setCookieHeaders = listOf("CASTGC=cas-tgt-secret; Path=/cas; HttpOnly; Secure"),
                        body = "",
                    )
                }
                req.method == "GET" && req.url.startsWith("https://app.upc.edu.cn/a_upc/api/cas/login") -> {
                    CasHttpResponse(
                        statusCode = 302,
                        location = "https://app.upc.edu.cn/site/weekschedule/index",
                        setCookieHeaders = listOf("eai-sess=app-session-xyz; Path=/; HttpOnly; Secure"),
                        body = "",
                    )
                }
                req.method == "GET" && req.url == UpcTimetableAuthEndpoints.PAGE_URL -> {
                    assertTrue(req.headers["Cookie"].orEmpty().contains("eai-sess=app-session-xyz"))
                    assertFalse(req.headers["Cookie"].orEmpty().contains("CASTGC"))
                    CasHttpResponse(statusCode = 200, location = null, setCookieHeaders = emptyList(), body = "ok")
                }
                req.method == "GET" && req.url == UpcTimetableAuthEndpoints.INDEX_URL -> {
                    assertEquals("app-session-xyz", parseCookieValue(req.headers["Cookie"], "eai-sess"))
                    CasHttpResponse(
                        statusCode = 200,
                        location = null,
                        setCookieHeaders = emptyList(),
                        body = """{"e":0,"m":"ok","d":{"termInfo":[{"year":"2026-2027","term":"1","startday":"2026-09-07","countweek":19}],"params":{"year":"2026-2027","term":"1","week":8},"weekday":1,"weekdays":["2026-10-25","2026-10-26","2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31"]}}""",
                    )
                }
                else -> error("Unexpected request: ${req.method} ${req.url}")
            }
        }

        val credential = SchoolLoginCredential(
            accountId = "acct0123456789ab",
            generation = 1L,
            loginName = "2024010101",
            password = "fixture-password".toCharArray(),
        )
        val result = UpcTimetableAuthenticator(transport).authenticate(credential)
        assertTrue(result is TimetableAuthResult.Success)
        val success = result as TimetableAuthResult.Success
        assertEquals(mapOf("eai-sess" to "app-session-xyz"), success.cookies)
        assertFalse(success.cookies.containsKey("CASTGC"))
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test
    fun `active captcha stops before submitting password`() = runTest {
        var postCount = 0
        val transport = CasHttpTransport { req ->
            if (req.method == "POST") postCount++
            CasHttpResponse(
                statusCode = 200,
                location = null,
                setCookieHeaders = emptyList(),
                body = """
                    <html>
                      <script>config.captcha = { id: 'cap-2' };</script>
                      ${validCasFormHtml(action = UpcTimetableAuthEndpoints.LOGIN_URL)}
                    </html>
                """.trimIndent(),
            )
        }
        val credential = SchoolLoginCredential(
            accountId = "acct0123456789ab",
            generation = 1L,
            loginName = "2024010101",
            password = "fixture-password".toCharArray(),
        )
        val result = UpcTimetableAuthenticator(transport).authenticate(credential)
        assertTrue(result is TimetableAuthResult.InteractionRequired)
        assertEquals(0, postCount)
    }

    @Test
    fun `explicit password error returns InvalidCredentials without retry`() = runTest {
        var postCount = 0
        val transport = CasHttpTransport { req ->
            if (req.method == "GET") {
                CasHttpResponse(
                    statusCode = 200,
                    location = null,
                    setCookieHeaders = emptyList(),
                    body = validCasFormHtml(action = UpcTimetableAuthEndpoints.LOGIN_URL),
                )
            } else {
                postCount++
                CasHttpResponse(
                    statusCode = 200,
                    location = null,
                    setCookieHeaders = emptyList(),
                    body = """
                        <html>
                          <div id="errorDiv">用户名或密码错误</div>
                          ${validCasFormHtml(action = UpcTimetableAuthEndpoints.LOGIN_URL)}
                        </html>
                    """.trimIndent(),
                )
            }
        }
        val credential = SchoolLoginCredential(
            accountId = "acct0123456789ab",
            generation = 1L,
            loginName = "2024010101",
            password = "wrong-password".toCharArray(),
        )
        val result = UpcTimetableAuthenticator(transport).authenticate(credential)
        assertEquals(TimetableAuthResult.InvalidCredentials, result)
        assertEquals(1, postCount)
    }

    @Test
    fun `rejects foreign form action and foreign callback redirect`() = runTest {
        var postCount = 0
        val foreignActionTransport = CasHttpTransport { req ->
            if (req.method == "POST") postCount++
            CasHttpResponse(
                statusCode = 200,
                location = null,
                setCookieHeaders = emptyList(),
                body = validCasFormHtml(action = "https://evil.example.com/cas/login"),
            )
        }
        val credential = SchoolLoginCredential(
            accountId = "acct0123456789ab",
            generation = 1L,
            loginName = "2024010101",
            password = "fixture-password".toCharArray(),
        )
        val actionRejected = UpcCasLogin(
            service = UpcCasService(
                casHost = UpcTimetableAuthEndpoints.CAS_HOST,
                serviceUrl = UpcTimetableAuthEndpoints.SERVICE_DECODED,
                callbackHost = UpcTimetableAuthEndpoints.APP_HOST,
                callbackPath = UpcTimetableAuthEndpoints.APP_CALLBACK_PATH,
                callbackParameterNames = setOf("ticket", "redirect", "from"),
            ),
            transport = foreignActionTransport,
        ).login(credential, UpcTimetableAuthEndpoints.LOGIN_URL)

        assertTrue(actionRejected is CasLoginResult.UpstreamError)
        assertEquals(0, postCount)
    }

    private fun validCasFormHtml(action: String): String = """
        <form id="fm1" action="$action" method="post">
          <input type="text" name="username" value="" />
          <input type="password" name="password" value="" />
          <input type="hidden" name="type" value="username_password" />
          <input type="hidden" name="execution" value="e1s1" />
          <input type="hidden" name="_eventId" value="submit" />
          <input type="submit" name="submit" value="登录" />
        </form>
    """.trimIndent()

    private fun parseCookieValue(header: String?, name: String): String? =
        header?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
}
