package me.chius.upcpocket.core.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.chius.upcpocket.core.model.SchoolLoginCredential

object UpcTimetableAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val APP_HOST = "app.upc.edu.cn"
    const val PAGE_URL = "https://app.upc.edu.cn/site/weekschedule/index"
    const val INDEX_URL = "https://app.upc.edu.cn/timetable/wap/default/get-index"
    const val WEEK_URL = "https://app.upc.edu.cn/timetable/wap/default/get-datatmp"

    // Inner redirect uses single-level percent encoding per UPC CAS gateway expectation.
    const val SERVICE =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex"
    const val SERVICE_DECODED =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https://app.upc.edu.cn/site/weekschedule/index"
    const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=$SERVICE&from=wap&noAutoRedirect=1"
    const val APP_CALLBACK_PATH = "/a_upc/api/cas/login"
}

sealed interface TimetableAuthResult {
    class Success(
        val cookies: Map<String, String>,
        val indexBody: String,
    ) : TimetableAuthResult {
        override fun toString(): String =
            "TimetableAuthResult.Success(cookieCount=${cookies.size})"
    }

    data object InvalidCredentials : TimetableAuthResult

    data class InteractionRequired(
        val interactionUrl: String,
        val reason: String,
    ) : TimetableAuthResult

    data class NetworkError(val message: String) : TimetableAuthResult

    data class UpstreamError(val message: String) : TimetableAuthResult
}

sealed interface TimetableVerifyResult {
    class Valid(val indexBody: String) : TimetableVerifyResult
    data object AuthRequired : TimetableVerifyResult
    data class NetworkError(val message: String) : TimetableVerifyResult
    data class UpstreamError(val message: String) : TimetableVerifyResult
}

/**
 * Authenticates the active school account against CAS for `app.upc.edu.cn` timetable access
 * and verifies captured session cookies using `get-index`.
 */
class UpcTimetableAuthenticator(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    private val casService = UpcCasService(
        casHost = UpcTimetableAuthEndpoints.CAS_HOST,
        serviceUrl = UpcTimetableAuthEndpoints.SERVICE_DECODED,
        callbackHost = UpcTimetableAuthEndpoints.APP_HOST,
        callbackPath = UpcTimetableAuthEndpoints.APP_CALLBACK_PATH,
        callbackParameterNames = setOf("ticket", "redirect", "from"),
        maxRedirectHops = 8,
        isAllowedCallbackRedirect = ::isAllowedCallbackQuery,
    )

    suspend fun authenticate(credential: SchoolLoginCredential): TimetableAuthResult {
        val loginResult = UpcCasLogin(
            service = casService,
            transport = transport,
        ).login(
            credential = credential,
            casUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
            isAllowedAppHop = ::isAllowedAppHop,
        )

        return when (loginResult) {
            is CasLoginResult.InvalidCredentials -> TimetableAuthResult.InvalidCredentials
            is CasLoginResult.InteractionRequired -> TimetableAuthResult.InteractionRequired(
                interactionUrl = loginResult.interactionUrl,
                reason = loginResult.reason,
            )
            is CasLoginResult.NetworkError -> TimetableAuthResult.NetworkError(loginResult.message)
            is CasLoginResult.UpstreamError -> TimetableAuthResult.UpstreamError(loginResult.message)
            is CasLoginResult.Success -> {
                when (val verified = verifySessionCookies(loginResult.targetCookies)) {
                    is TimetableVerifyResult.Valid -> TimetableAuthResult.Success(
                        cookies = loginResult.targetCookies,
                        indexBody = verified.indexBody,
                    )
                    TimetableVerifyResult.AuthRequired -> TimetableAuthResult.InteractionRequired(
                        interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                        reason = "业务会话未能生效，请通过交互页面完成登录",
                    )
                    is TimetableVerifyResult.NetworkError ->
                        TimetableAuthResult.NetworkError(verified.message)
                    is TimetableVerifyResult.UpstreamError ->
                        TimetableAuthResult.UpstreamError(verified.message)
                }
            }
        }
    }

    suspend fun verifySessionCookies(cookies: Map<String, String>): TimetableVerifyResult {
        if (cookies.isEmpty()) return TimetableVerifyResult.AuthRequired
        val cookieHeader = formatCookieHeader(cookies) ?: return TimetableVerifyResult.AuthRequired
        val response = try {
            transport.execute(
                CasHttpRequest(
                    url = UpcTimetableAuthEndpoints.INDEX_URL,
                    method = "GET",
                    headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to UpcTimetableAuthEndpoints.PAGE_URL,
                        "Cookie" to cookieHeader,
                    ),
                ),
            )
        } catch (failure: CasTransportException) {
            return when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> TimetableVerifyResult.NetworkError(
                    failure.message ?: "无法连接课表服务，请检查网络后重试",
                )
                CasTransportFailure.TLS,
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> TimetableVerifyResult.UpstreamError(
                    failure.message ?: "课表服务安全连接或协议异常",
                )
            }
        }

        if (response.statusCode in 300..399 || response.statusCode == 401) {
            return TimetableVerifyResult.AuthRequired
        }
        if (response.statusCode != 200) {
            return TimetableVerifyResult.UpstreamError(
                "课表接口暂时不可用（HTTP ${response.statusCode}）",
            )
        }

        val trimmed = response.body.trimStart('\uFEFF', '\u200B').trim()
        if (trimmed.isEmpty()) {
            return TimetableVerifyResult.UpstreamError("课表索引返回空响应")
        }
        if (trimmed.startsWith("<")) {
            return TimetableVerifyResult.AuthRequired
        }

        val code = parseStrictJsonIntField(trimmed, "e")
            ?: return TimetableVerifyResult.UpstreamError("课表索引响应结构无法解析")
        return when (code) {
            10013 -> TimetableVerifyResult.AuthRequired
            0 -> TimetableVerifyResult.Valid(response.body)
            else -> TimetableVerifyResult.UpstreamError("课表服务返回业务错误码（$code）")
        }
    }

    private fun isAllowedCallbackQuery(uri: URI): Boolean {
        val rawQuery = uri.rawQuery ?: return true
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
            val key = runCatching { URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name()) }.getOrNull()
                ?: return false
            val value = runCatching { URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name()) }.getOrNull()
                ?: return false
            if (key == "redirect" && value != UpcTimetableAuthEndpoints.PAGE_URL) return false
            if (key == "from" && value != "wap") return false
        }
        return true
    }

    private fun isAllowedAppHop(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase(Locale.ROOT) != UpcTimetableAuthEndpoints.APP_HOST ||
            (uri.port != -1 && uri.port != 443) ||
            uri.rawUserInfo != null ||
            uri.rawFragment != null
        ) {
            return false
        }
        return when (uri.rawPath) {
            UpcTimetableAuthEndpoints.APP_CALLBACK_PATH -> casService.isCallbackUrl(uri)
            "/site/weekschedule/index",
            "/timetable/wap/default/get-index",
            -> true
            else -> false
        }
    }

    companion object {
        private val COOKIE_NAME_REGEX = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}")

        fun formatCookieHeader(cookies: Map<String, String>): String? {
            if (cookies.isEmpty()) return null
            val pairs = cookies.entries.mapNotNull { (name, value) ->
                if (!COOKIE_NAME_REGEX.matches(name)) return@mapNotNull null
                if (value.length > 4096 || value.any { it < '\u0021' || it > '\u007e' }) {
                    return@mapNotNull null
                }
                "$name=$value"
            }
            return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        fun parseStrictJsonIntField(jsonText: String, field: String): Int? {
            return try {
                val root = Json.parseToJsonElement(jsonText) as? JsonObject ?: return null
                val element = root[field] as? JsonPrimitive ?: return null
                if (element is JsonNull) return null
                // Strictly accepts integer numerals/strings only; booleans ("false") and decimals fail regex.
                val content = element.content.trim()
                if (!content.matches(Regex("[+-]?\\d+"))) return null
                content.toIntOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}
