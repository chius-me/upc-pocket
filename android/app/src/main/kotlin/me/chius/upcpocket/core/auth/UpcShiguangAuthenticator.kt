package me.chius.upcpocket.core.auth

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chius.upcpocket.core.model.SchoolLoginCredential

object UpcShiguangAuthEndpoints {
    const val HOST = "dekt.upc.edu.cn"
    const val CAS_HOST = "cas.upc.edu.cn"
    const val BASE = "https://dekt.upc.edu.cn"
    // CAS registration uses HTTP service bytes; callbacks are upgraded to HTTPS before requesting.
    const val SERVICE = "http://dekt.upc.edu.cn/caslogin.jsp"
    const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=http%3A%2F%2Fdekt.upc.edu.cn%2Fcaslogin.jsp"
    const val SECOND_STAGE = "/sysmng/login!loginByUserNoForCas.action"
    const val LIST_PATH = "/actvMng/actvMngFront!jumpActvCampListPageForWeb.action"
    const val LIST_URL = "$BASE$LIST_PATH?page=1&oper=1"
    const val REPORT_URL = "$BASE/reportCardMngAjax/reportCardMngFront!querySelectedHour.action"
    const val REPORT_REFERER = "$BASE/reportCardMng/reportCardMngFront!jumpReportCardFirstForWeb.action"
}

sealed interface ShiguangAuthResult {
    class Success(
        val cookies: Map<String, String>,
        val identityVerified: Boolean,
    ) : ShiguangAuthResult {
        override fun toString(): String =
            "ShiguangAuthResult.Success(cookieCount=${cookies.size}, identityVerified=$identityVerified)"
    }

    data object InvalidCredentials : ShiguangAuthResult

    data object IdentityMismatch : ShiguangAuthResult

    data class InteractionRequired(
        val interactionUrl: String,
        val reason: String,
    ) : ShiguangAuthResult

    data class NetworkError(val message: String) : ShiguangAuthResult

    data class UpstreamError(val message: String) : ShiguangAuthResult
}

/**
 * Single-shot CAS authenticator for Shiguang Second Classroom (`dekt.upc.edu.cn`).
 * Upgrades HTTP `dekt.upc.edu.cn` callbacks to HTTPS and verifies student identity via `querySelectedHour`.
 */
class UpcShiguangAuthenticator(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    private val casService = UpcCasService(
        casHost = UpcShiguangAuthEndpoints.CAS_HOST,
        serviceUrl = UpcShiguangAuthEndpoints.SERVICE,
        callbackHost = UpcShiguangAuthEndpoints.HOST,
        callbackPath = "/caslogin.jsp",
        callbackParameterNames = setOf("ticket"),
        maxRedirectHops = 8,
        upgradeHttpCallbackToHttps = true,
    )

    suspend fun authenticate(credential: SchoolLoginCredential): ShiguangAuthResult {
        val jar = UpcCasCookieJar(
            allowedHosts = setOf(UpcShiguangAuthEndpoints.CAS_HOST, UpcShiguangAuthEndpoints.HOST),
        )
        return try {
            performAuth(credential, jar)
        } catch (failure: CasTransportException) {
            when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> ShiguangAuthResult.NetworkError(failure.message ?: "无法连接石光二课平台，请检查网络后重试")
                CasTransportFailure.TLS,
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> ShiguangAuthResult.UpstreamError(failure.message ?: "石光平台连接或协议异常")
            }
        } finally {
            jar.clear()
        }
    }

    suspend fun verifyIdentityWithCookieHeader(
        cookieHeader: String,
        expectedLogin: String,
    ): IdentityCheckOutcome {
        val response = transport.execute(
            CasHttpRequest(
                url = UpcShiguangAuthEndpoints.REPORT_URL,
                method = "POST",
                headers = mapOf(
                    "Referer" to UpcShiguangAuthEndpoints.REPORT_REFERER,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json",
                    "Cookie" to cookieHeader,
                ),
                formBody = "",
            ),
        )
        if (response.statusCode != 200) {
            return IdentityCheckOutcome.Unverified
        }
        return checkIdentityJson(response.body, expectedLogin)
    }

    private suspend fun performAuth(
        credential: SchoolLoginCredential,
        jar: UpcCasCookieJar,
    ): ShiguangAuthResult {
        val initialCallback = when (
            val res = UpcCasLogin(casService, transport).authenticateToCallback(
                credential = credential,
                casUrl = UpcShiguangAuthEndpoints.LOGIN_URL,
                jar = jar,
            )
        ) {
            is CasCallbackResult.Callback -> casService.upgradeIfNeeded(res.callbackUri)
            is CasCallbackResult.InvalidCredentials -> return ShiguangAuthResult.InvalidCredentials
            is CasCallbackResult.InteractionRequired -> return ShiguangAuthResult.InteractionRequired(
                interactionUrl = UpcShiguangAuthEndpoints.LOGIN_URL,
                reason = res.reason,
            )
            is CasCallbackResult.NetworkError -> return ShiguangAuthResult.NetworkError(res.message)
            is CasCallbackResult.UpstreamError -> return ShiguangAuthResult.UpstreamError(res.message)
        }

        var lastFailure: ShiguangAuthResult = ShiguangAuthResult.UpstreamError("石光二课会话建立失败，请重试")
        for (attempt in 0 until MAX_BUSINESS_ATTEMPTS) {
            val callbackUri = if (attempt == 0) {
                initialCallback
            } else {
                jar.clearHost(UpcShiguangAuthEndpoints.HOST)
                requestSsoTicketCallback(jar) ?: break
            }
            val result = tryCompleteBusinessHandshake(callbackUri, credential.loginName, jar)
            if (result !is ShiguangAuthResult.UpstreamError) {
                return result
            }
            lastFailure = result
        }
        return lastFailure
    }

    private suspend fun tryCompleteBusinessHandshake(
        callbackUri: URI,
        expectedLogin: String,
        jar: UpcCasCookieJar,
    ): ShiguangAuthResult {
        establishBusinessSession(callbackUri, jar)?.let { failure ->
            return failure
        }

        val listResponse = executeWithJar(
            jar = jar,
            request = CasHttpRequest(url = UpcShiguangAuthEndpoints.LIST_URL, method = "GET"),
        )
        if (listResponse.statusCode != 200) {
            return ShiguangAuthResult.UpstreamError("石光二课首页返回 HTTP ${listResponse.statusCode}")
        }
        if (!ACTIVITY_CONTAINER.containsMatchIn(listResponse.body)) {
            return ShiguangAuthResult.UpstreamError("石光二课登录页结构异常，请重试")
        }

        val cookies = jar.cookiesForHost(UpcShiguangAuthEndpoints.HOST)
        if ("SESSION" !in cookies) {
            return ShiguangAuthResult.UpstreamError("石光二课未返回有效的业务会话 Cookie")
        }

        val reportResponse = executeWithJar(
            jar = jar,
            request = CasHttpRequest(
                url = UpcShiguangAuthEndpoints.REPORT_URL,
                method = "POST",
                headers = mapOf(
                    "Referer" to UpcShiguangAuthEndpoints.REPORT_REFERER,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json",
                ),
                formBody = "",
            ),
        )
        if (reportResponse.statusCode != 200) {
            return ShiguangAuthResult.UpstreamError("石光身份核验返回 HTTP ${reportResponse.statusCode}")
        }

        return when (checkIdentityJson(reportResponse.body, expectedLogin)) {
            IdentityCheckOutcome.Verified -> ShiguangAuthResult.Success(cookies, identityVerified = true)
            IdentityCheckOutcome.Unverified -> ShiguangAuthResult.Success(cookies, identityVerified = false)
            IdentityCheckOutcome.Mismatch -> ShiguangAuthResult.IdentityMismatch
        }
    }

    /** Re-requests a fresh ST via existing `CASTGC` SSO session without ever resubmitting the password. */
    private suspend fun requestSsoTicketCallback(jar: UpcCasCookieJar): URI? {
        var current = URI(UpcShiguangAuthEndpoints.LOGIN_URL)
        repeat(MAX_SSO_HOPS) {
            val response = executeWithJar(jar, CasHttpRequest(url = current.toString(), method = "GET"))
            if (response.statusCode !in REDIRECT_STATUSES) return null
            val loc = response.location ?: return null
            val resolved = runCatching { current.resolve(loc) }.getOrNull() ?: return null
            val upgraded = casService.upgradeIfNeeded(resolved)
            if (casService.isCallbackUrl(upgraded)) {
                return upgraded
            }
            val isCasHost = upgraded.scheme.equals("https", ignoreCase = true) &&
                upgraded.host?.equals(UpcShiguangAuthEndpoints.CAS_HOST, ignoreCase = true) == true &&
                upgraded.port in setOf(-1, 443) &&
                upgraded.rawUserInfo == null &&
                upgraded.rawFragment == null
            if (!isCasHost) return null
            current = upgraded
        }
        return null
    }

    private suspend fun establishBusinessSession(
        initialUri: URI,
        jar: UpcCasCookieJar,
    ): ShiguangAuthResult? {
        var current = initialUri
        repeat(6) {
            val url = current.toString()
            val response = executeWithJar(jar, CasHttpRequest(url = url, method = "GET"))
            if (response.statusCode in REDIRECT_STATUSES) {
                val loc = response.location
                    ?: return ShiguangAuthResult.UpstreamError("石光回调重定向缺少目标地址")
                current = resolveShiguangTarget(current, loc)
                    ?: return ShiguangAuthResult.UpstreamError("石光回调重定向超出了允许范围")
                return@repeat
            }
            if (response.statusCode != 200) {
                return ShiguangAuthResult.UpstreamError("石光二课回调返回 HTTP ${response.statusCode}")
            }
            val matches = PC_REDIRECT.findAll(response.body).toList()
            if (matches.size > 1) {
                return ShiguangAuthResult.UpstreamError("石光登录页存在多个异常跳转")
            }
            if (matches.isNotEmpty()) {
                val rawTarget = matches.single().groupValues[1].replace("&amp;", "&")
                val resolved = resolveShiguangTarget(current, rawTarget)
                    ?: return ShiguangAuthResult.UpstreamError("石光登录脚本跳转超出了允许路径")
                if (resolved.rawPath != UpcShiguangAuthEndpoints.SECOND_STAGE) {
                    return ShiguangAuthResult.UpstreamError("石光二阶段登录路径不匹配")
                }
                current = resolved
                return@repeat
            }
            if (ERROR_TITLE.containsMatchIn(response.body)) {
                return ShiguangAuthResult.UpstreamError("石光二课节点返回异常提醒页")
            }
            return null
        }
        return ShiguangAuthResult.UpstreamError("石光二课登录跳转次数超过上限")
    }

    private suspend fun executeWithJar(
        jar: UpcCasCookieJar,
        request: CasHttpRequest,
    ): CasHttpResponse {
        val cookieHeader = jar.cookieHeader(request.url)
        val mergedHeaders = if (cookieHeader != null) {
            request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } +
                ("Cookie" to cookieHeader)
        } else {
            request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        }
        val response = transport.execute(request.copy(headers = mergedHeaders))
        jar.saveFromHeaders(request.url, response.setCookieHeaders)
        return response
    }

    private fun resolveShiguangTarget(base: URI, raw: String): URI? {
        if (raw.any { it <= ' ' || it == '\\' }) return null
        val parsedRaw = runCatching { URI(raw) }.getOrNull() ?: return null
        if (parsedRaw.rawUserInfo != null || parsedRaw.port != -1) return null
        val resolved = runCatching { base.resolve(raw) }.getOrNull() ?: return null
        val scheme = resolved.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = resolved.host?.lowercase(Locale.ROOT) ?: return null
        val path = resolved.rawPath.orEmpty()
        if (scheme !in setOf("http", "https") ||
            host != UpcShiguangAuthEndpoints.HOST ||
            resolved.rawUserInfo != null ||
            resolved.rawFragment != null ||
            resolved.port !in setOf(-1, 80, 443)
        ) {
            return null
        }
        if (path.split('/').any { it == "." || it == ".." } ||
            path.contains('%') ||
            path.contains("//")
        ) {
            return null
        }
        val allowedPaths = setOf(
            UpcShiguangAuthEndpoints.SECOND_STAGE,
            UpcShiguangAuthEndpoints.LIST_PATH,
            "/",
            "/index.jsp",
            "/web/index.html",
        )
        if (path !in allowedPaths) return null
        if (path == UpcShiguangAuthEndpoints.SECOND_STAGE) {
            val query = resolved.rawQuery.orEmpty()
            val parts = query.split('&').filter { it.isNotEmpty() }
            val single = parts.singleOrNull() ?: return null
            val key = single.substringBefore('=')
            val value = single.substringAfter('=', "")
            if (key != "userName" || value.isBlank() || value.length > 4096) return null
        } else if (!resolved.rawQuery.isNullOrEmpty()) {
            return null
        }
        val rawQuery = resolved.rawQuery?.let { "?$it" }.orEmpty()
        return runCatching { URI("https://${UpcShiguangAuthEndpoints.HOST}$path$rawQuery") }.getOrNull()
    }

    enum class IdentityCheckOutcome {
        Verified,
        Unverified,
        Mismatch,
    }

    internal fun checkIdentityJson(body: String, expectedLogin: String): IdentityCheckOutcome {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return IdentityCheckOutcome.Unverified
        val rows = root["gridModel"] as? JsonArray ?: return IdentityCheckOutcome.Unverified
        if (rows.isEmpty()) return IdentityCheckOutcome.Unverified
        var complete = true
        var populated = false
        for (element in rows) {
            val row = element as? JsonObject ?: return IdentityCheckOutcome.Unverified
            if (row.isEmpty()) {
                complete = false
                continue
            }
            populated = true
            val stuNo = (row["stuNo"] as? JsonPrimitive)?.contentOrNull
            if (stuNo.isNullOrBlank()) {
                complete = false
            } else if (stuNo != expectedLogin) {
                return IdentityCheckOutcome.Mismatch
            }
        }
        return if (populated && complete) IdentityCheckOutcome.Verified else IdentityCheckOutcome.Unverified
    }

    private companion object {
        const val MAX_BUSINESS_ATTEMPTS = 4
        const val MAX_SSO_HOPS = 4
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val PC_REDIRECT = Regex("var\\s+pcRedirectUrl\\s*=\\s*[\"']([^\"']+)[\"']")
        val ERROR_TITLE = Regex("<title>\\s*异常提醒\\s*</title>", RegexOption.IGNORE_CASE)
        val ACTIVITY_CONTAINER = Regex(
            "<div\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\bactive_2_list\\b[^\"']*[\"']",
            RegexOption.IGNORE_CASE,
        )
    }
}
