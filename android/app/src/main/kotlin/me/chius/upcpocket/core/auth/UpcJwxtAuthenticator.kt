package me.chius.upcpocket.core.auth

import java.net.URI
import java.util.Locale
import me.chius.upcpocket.core.model.SchoolLoginCredential

object UpcJwxtAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val JWXT_HOST = "jwxt.upc.edu.cn"
    const val SSO_URL = "https://jwxt.upc.edu.cn/sso.jsp"
    const val GRADES_QUERY_URL = "https://jwxt.upc.edu.cn/jsxsd/kscj/cjcx_query"
    const val GRADES_LIST_URL = "https://jwxt.upc.edu.cn/jsxsd/kscj/cjcx_list"
    const val SERVICE = "http://jwxt.upc.edu.cn/sso.jsp"
    const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=http%3A%2F%2Fjwxt.upc.edu.cn%2Fsso.jsp"
}

sealed interface JwxtAuthResult {
    class Success(val cookies: Map<String, String>) : JwxtAuthResult {
        override fun toString(): String = "JwxtAuthResult.Success(cookieCount=${cookies.size})"
    }

    data object InvalidCredentials : JwxtAuthResult

    data class InteractionRequired(
        val interactionUrl: String,
        val reason: String,
    ) : JwxtAuthResult

    data class NetworkError(val message: String) : JwxtAuthResult

    data class UpstreamError(val message: String) : JwxtAuthResult
}

/**
 * Single-shot CAS authenticator for Qiangzhi Educational Affairs (`jwxt.upc.edu.cn`).
 * Upgrades `http://jwxt.upc.edu.cn` CAS callbacks to HTTPS while keeping all cookies in RAM.
 */
class UpcJwxtAuthenticator(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    private val casService = UpcCasService(
        casHost = UpcJwxtAuthEndpoints.CAS_HOST,
        serviceUrl = UpcJwxtAuthEndpoints.SERVICE,
        callbackHost = UpcJwxtAuthEndpoints.JWXT_HOST,
        callbackPath = "/sso.jsp",
        callbackParameterNames = setOf("ticket"),
        maxRedirectHops = 8,
        upgradeHttpCallbackToHttps = true,
    )

    suspend fun authenticate(credential: SchoolLoginCredential): JwxtAuthResult {
        val jar = UpcCasCookieJar(
            allowedHosts = setOf(UpcJwxtAuthEndpoints.CAS_HOST, UpcJwxtAuthEndpoints.JWXT_HOST),
        )
        return try {
            performAuth(credential, jar)
        } catch (failure: CasTransportException) {
            when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> JwxtAuthResult.NetworkError(failure.message ?: "无法连接教务系统，请检查网络后重试")
                CasTransportFailure.TLS,
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> JwxtAuthResult.UpstreamError(failure.message ?: "教务系统连接或协议异常")
            }
        } finally {
            jar.clear()
        }
    }

    private suspend fun performAuth(
        credential: SchoolLoginCredential,
        jar: UpcCasCookieJar,
    ): JwxtAuthResult {
        val entryResponse = executeWithJar(jar, CasHttpRequest(url = UpcJwxtAuthEndpoints.SSO_URL, method = "GET"))
        val casUri = when (entryResponse.statusCode) {
            in 300..399 -> entryResponse.location?.let { resolveUri(UpcJwxtAuthEndpoints.SSO_URL, it) }
            200 -> extractJsLocation(entryResponse.body)?.let { resolveUri(UpcJwxtAuthEndpoints.SSO_URL, it) }
            else -> return JwxtAuthResult.UpstreamError("教务入口返回 HTTP ${entryResponse.statusCode}")
        } ?: return JwxtAuthResult.UpstreamError("未能解析教务统一认证入口")

        if (!casService.isCasLoginUrl(casUri)) {
            return JwxtAuthResult.UpstreamError("教务入口跳转超出官方 CAS 地址范围")
        }

        val callbackUri = when (
            val res = UpcCasLogin(casService, transport).authenticateToCallback(
                credential = credential,
                casUrl = casUri.toString(),
                jar = jar,
            )
        ) {
            is CasCallbackResult.Callback -> casService.upgradeIfNeeded(res.callbackUri)
            is CasCallbackResult.InvalidCredentials -> return JwxtAuthResult.InvalidCredentials
            is CasCallbackResult.InteractionRequired -> return JwxtAuthResult.InteractionRequired(
                interactionUrl = UpcJwxtAuthEndpoints.SSO_URL,
                reason = res.reason,
            )
            is CasCallbackResult.NetworkError -> return JwxtAuthResult.NetworkError(res.message)
            is CasCallbackResult.UpstreamError -> return JwxtAuthResult.UpstreamError(res.message)
        }

        var current: URI = callbackUri
        repeat(8) {
            current = upgradeJwxtHttp(current)
            if (!isAllowedJwxtUri(current)) {
                return JwxtAuthResult.UpstreamError("教务回调跳转超出了允许路径")
            }
            val currentUrl = current.toString()
            val response = executeWithJar(jar, CasHttpRequest(url = currentUrl, method = "GET"))
            when (response.statusCode) {
                in 300..399 -> {
                    val loc = response.location ?: return JwxtAuthResult.UpstreamError("教务重定向缺少目标地址")
                    current = resolveUri(currentUrl, loc)
                        ?: return JwxtAuthResult.UpstreamError("教务重定向目标无效")
                    return@repeat
                }
                200 -> {
                    val jsNext = extractJsLocation(response.body)
                    if (jsNext != null && current.rawPath == "/sso.jsp") {
                        current = resolveUri(currentUrl, jsNext)
                            ?: return JwxtAuthResult.UpstreamError("教务脚本跳转无效")
                        return@repeat
                    }
                    val cookies = jar.cookiesForHost(UpcJwxtAuthEndpoints.JWXT_HOST)
                    return if (cookies.isNotEmpty()) {
                        JwxtAuthResult.Success(cookies)
                    } else {
                        JwxtAuthResult.UpstreamError("教务认证完成但未获得有效会话 Cookie")
                    }
                }
                else -> return JwxtAuthResult.UpstreamError("教务回调返回 HTTP ${response.statusCode}")
            }
        }
        return JwxtAuthResult.UpstreamError("教务登录跳转次数超过上限")
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

    private fun upgradeJwxtHttp(uri: URI): URI {
        if (uri.scheme.equals("http", ignoreCase = true) &&
            uri.host?.lowercase(Locale.ROOT) == UpcJwxtAuthEndpoints.JWXT_HOST &&
            uri.port in setOf(-1, 80) &&
            uri.rawUserInfo == null
        ) {
            val rawQuery = uri.rawQuery?.let { "?$it" }.orEmpty()
            return runCatching { URI("https://${UpcJwxtAuthEndpoints.JWXT_HOST}${uri.rawPath.orEmpty()}$rawQuery") }
                .getOrDefault(uri)
        }
        return uri
    }

    private fun isAllowedJwxtUri(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.host?.lowercase(Locale.ROOT) != UpcJwxtAuthEndpoints.JWXT_HOST) return false
        if (uri.port !in setOf(-1, 443) || uri.rawUserInfo != null) return false
        val path = uri.rawPath.orEmpty()
        return path == "/sso.jsp" || path.startsWith("/jsxsd/")
    }

    private fun resolveUri(base: String, raw: String): URI? = runCatching {
        URI(base).resolve(raw)
    }.getOrNull()

    private fun extractJsLocation(html: String): String? =
        JS_LOCATION_REGEX.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")

    private companion object {
        val JS_LOCATION_REGEX = Regex(
            """(?:window\.)?location(?:\.href)?\s*=\s*["']([^"'\r\n]+)["']""",
            RegexOption.IGNORE_CASE,
        )
    }
}
