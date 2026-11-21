package me.chius.upcpocket.core.auth

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chius.upcpocket.core.model.SchoolLoginCredential

object UpcCardAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val CARD_HOST = "mb-xyk.upc.edu.cn"
    const val PORTAL_HOST = "i.upc.edu.cn"
    const val BASE = "https://$CARD_HOST"
    const val ENTRY_URL =
        "$BASE/berserker-auth/cas/redirect/wislearn" +
            "?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit"
    const val OAUTH_TOKEN_URL = "$BASE/berserker-auth/oauth/token"
    const val USER_INFO_URL = "$BASE/berserker-base/user?synAccessSource=h5"
    private const val CLIENT_BASIC_AUTH =
        "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"

    internal fun basicAuthHeader(): String = CLIENT_BASIC_AUTH
}

sealed interface CardAuthResult {
    class Success(val token: String) : CardAuthResult {
        override fun toString(): String = "CardAuthResult.Success(token=[REDACTED])"
    }

    data object InvalidCredentials : CardAuthResult

    data class InteractionRequired(
        val interactionUrl: String,
        val reason: String,
    ) : CardAuthResult

    data class NetworkError(val message: String) : CardAuthResult

    data class UpstreamError(val message: String) : CardAuthResult
}

/**
 * Single-shot CAS authenticator for the UPC campus card platform (`mb-xyk.upc.edu.cn`).
 * Supports both direct `synjones-auth` tokens and exchangeable platform `ticket` callbacks,
 * upgrades HTTP callbacks on `mb-xyk.upc.edu.cn` to HTTPS, and verifies self studentId via
 * `/berserker-base/user`.
 */
class UpcCardAuthenticator(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    suspend fun authenticate(credential: SchoolLoginCredential): CardAuthResult {
        val jar = UpcCasCookieJar(
            allowedHosts = setOf(
                UpcCardAuthEndpoints.CAS_HOST,
                UpcCardAuthEndpoints.CARD_HOST,
                UpcCardAuthEndpoints.PORTAL_HOST,
            ),
        )
        return try {
            performAuth(credential, jar)
        } catch (failure: CasTransportException) {
            when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> CardAuthResult.NetworkError(failure.message ?: "无法连接校园卡认证服务，请检查网络")
                CasTransportFailure.TLS,
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> CardAuthResult.UpstreamError(failure.message ?: "校园卡认证连接或协议异常")
            }
        } finally {
            jar.clear()
        }
    }

    suspend fun completeWebLoginResult(
        callbackUrl: String,
        expectedStudentId: String,
    ): CardAuthResult {
        return try {
            val token = exchangeOrExtractToken(callbackUrl)
            verifySelfIdentity(token, expectedStudentId)
            CardAuthResult.Success(token)
        } catch (failure: CasTransportException) {
            CardAuthResult.NetworkError(failure.message ?: "校园卡登录网络异常")
        } catch (error: IOException) {
            CardAuthResult.UpstreamError(error.message ?: "校园卡登录验证失败")
        }
    }

    private suspend fun performAuth(
        credential: SchoolLoginCredential,
        jar: UpcCasCookieJar,
    ): CardAuthResult {
        val casUri = followToCasForm(UpcCardAuthEndpoints.ENTRY_URL, jar)
            ?: return CardAuthResult.UpstreamError("未能跳转至校园卡统一认证页面")

        val serviceParam = parseSingleQueryParam(casUri, "service")
            ?: return CardAuthResult.UpstreamError("校园卡统一认证参数不完整")

        val casService = UpcCasService(
            casHost = UpcCardAuthEndpoints.CAS_HOST,
            serviceUrl = serviceParam,
            callbackHost = UpcCardAuthEndpoints.CARD_HOST,
            callbackPath = URI(serviceParam).rawPath ?: "/berserker-auth/cas/login/wislearn",
            callbackParameterNames = setOf("ticket", "synjones-auth", "visitor", "name"),
            maxRedirectHops = 10,
            upgradeHttpCallbackToHttps = true,
        )

        val getRes = executeWithJar(jar, CasHttpRequest(url = casUri.toString(), method = "GET"))
        if (getRes.statusCode !in setOf(200, 401, 403)) {
            return CardAuthResult.UpstreamError("学校认证页返回 HTTP ${getRes.statusCode}")
        }
        val casForm = runCatching { UpcCasLogin.parseCasLoginFormPublic(getRes.body) }.getOrNull()
        if (casForm != null && (casForm.captchaRequired || casForm.hasSecondFactor)) {
            return CardAuthResult.InteractionRequired(
                interactionUrl = UpcCardAuthEndpoints.ENTRY_URL,
                reason = if (casForm.hasSecondFactor) "需要短信/二次验证" else "需要验证码验证",
            )
        }
        if (casForm == null || !casForm.hasPassword || casForm.execution.isNullOrBlank()) {
            return CardAuthResult.UpstreamError("未能解析校园卡 CAS 登录表单")
        }

        val actionUri = resolveHttpUri(casUri.toString(), casForm.action ?: casUri.toString())
        if (actionUri == null || !casService.isCasLoginUrl(actionUri)) {
            return CardAuthResult.UpstreamError("校园卡 CAS 提交地址校验未通过")
        }

        val formBody = DefaultCasHttpTransport.formEncode(
            listOf(
                "username" to credential.loginName,
                "password" to String(credential.password),
                "type" to casForm.type,
                "execution" to casForm.execution,
                "_eventId" to casForm.eventId,
                "submit" to casForm.submit,
            ),
        )

        val postRes = executeWithJar(
            jar = jar,
            request = CasHttpRequest(
                url = actionUri.toString(),
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to "https://${UpcCardAuthEndpoints.CAS_HOST}",
                    "Referer" to casUri.toString(),
                ),
                formBody = formBody,
            ),
        )

        if (postRes.statusCode !in 300..399) {
            val returned = runCatching { UpcCasLogin.parseCasLoginFormPublic(postRes.body) }.getOrNull()
            if (returned != null && (returned.captchaRequired || returned.hasSecondFactor)) {
                return CardAuthResult.InteractionRequired(
                    interactionUrl = UpcCardAuthEndpoints.ENTRY_URL,
                    reason = "需要完成学校安全验证",
                )
            }
            if (returned != null && UpcCasLogin.isExplicitCasCredentialRejectionPublic(returned.errorText)) {
                return CardAuthResult.InvalidCredentials
            }
            return CardAuthResult.InteractionRequired(
                interactionUrl = UpcCardAuthEndpoints.ENTRY_URL,
                reason = "请通过学校验证页完成校园卡登录",
            )
        }

        var current = resolveAndUpgradeCard(actionUri.toString(), postRes.location ?: "")
            ?: return CardAuthResult.UpstreamError("校园卡登录回调地址无效")

        repeat(10) {
            if (isTokenLandingUrl(current) && hasLoginResultMarker(current)) {
                val token = exchangeOrExtractToken(current)
                verifySelfIdentity(token, credential.loginName)
                return CardAuthResult.Success(token)
            }
            if (!isAllowedHost(current)) {
                return CardAuthResult.UpstreamError("校园卡回调跳转超出允许域")
            }
            val resp = executeWithJar(jar, CasHttpRequest(url = current, method = "GET"))
            if (resp.statusCode in 300..399) {
                val loc = resp.location ?: return CardAuthResult.UpstreamError("校园卡回调重定向缺失目标")
                current = resolveAndUpgradeCard(current, loc)
                    ?: return CardAuthResult.UpstreamError("校园卡回调重定向非法")
                return@repeat
            }
            if (resp.statusCode == 200) {
                val jsTarget = extractJsRedirect(resp.body)
                if (jsTarget != null) {
                    current = resolveAndUpgradeCard(current, jsTarget)
                        ?: return CardAuthResult.UpstreamError("校园卡脚本跳转地址非法")
                    return@repeat
                }
                if (isCasHost(current)) {
                    val returned = runCatching { UpcCasLogin.parseCasLoginFormPublic(resp.body) }.getOrNull()
                    if (returned != null && UpcCasLogin.isExplicitCasCredentialRejectionPublic(returned.errorText)) {
                        return CardAuthResult.InvalidCredentials
                    }
                    return CardAuthResult.InteractionRequired(
                        interactionUrl = UpcCardAuthEndpoints.ENTRY_URL,
                        reason = "需要完成学校验证",
                    )
                }
            }
            return CardAuthResult.UpstreamError("校园卡认证未返回有效登录票据（HTTP ${resp.statusCode}）")
        }
        return CardAuthResult.UpstreamError("校园卡认证重定向次数超出上限")
    }

    private suspend fun followToCasForm(entryUrl: String, jar: UpcCasCookieJar): URI? {
        var current = entryUrl
        repeat(8) {
            if (!isAllowedHost(current)) return null
            val uri = URI(current)
            if (isCasHost(current) && uri.rawPath == "/cas/login") {
                return uri
            }
            val resp = executeWithJar(jar, CasHttpRequest(url = current, method = "GET"))
            if (resp.statusCode in 300..399) {
                val loc = resp.location ?: return null
                current = resolveAndUpgradeCard(current, loc) ?: return null
                return@repeat
            }
            if (resp.statusCode == 200) {
                val js = extractJsRedirect(resp.body) ?: return null
                current = resolveAndUpgradeCard(current, js) ?: return null
                return@repeat
            }
            return null
        }
        val finalUri = runCatching { URI(current) }.getOrNull()
        return if (finalUri != null && isCasHost(current) && finalUri.rawPath == "/cas/login") finalUri else null
    }

    private suspend fun exchangeOrExtractToken(url: String): String {
        val uri = URI(url)
        val rawAuth = parseSingleQueryParam(uri, "synjones-auth")
        if (rawAuth != null) {
            if (parseSingleQueryParam(uri, "visitor") == "1") {
                throw IOException("校园卡返回访客会话，请重新验证")
            }
            val trimmed = if (rawAuth.startsWith("bearer ", ignoreCase = true)) {
                rawAuth.substring(7).trim()
            } else {
                rawAuth.trim()
            }
            if (trimmed.isBlank() || trimmed.length > 16_384 || trimmed.any { it <= ' ' || it == '\u007f' }) {
                throw IOException("校园卡登录令牌格式异常")
            }
            return trimmed
        }

        val ticket = parseSingleQueryParam(uri, "ticket")
            ?.takeIf { it.isNotBlank() && it.length <= 16_384 }
            ?: throw IOException("未获取到校园卡登录票据")

        val body = DefaultCasHttpTransport.formEncode(
            listOf(
                "username" to ticket,
                "password" to ticket,
                "grant_type" to "password",
                "scope" to "all",
                "loginFrom" to "h5",
                "logintype" to "sso",
                "device_token" to "h5",
                "synAccessSource" to "h5",
            ),
        )
        val resp = transport.execute(
            CasHttpRequest(
                url = UpcCardAuthEndpoints.OAUTH_TOKEN_URL,
                method = "POST",
                headers = mapOf(
                    "Authorization" to UpcCardAuthEndpoints.basicAuthHeader(),
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Accept" to "application/json",
                ),
                formBody = body,
            ),
        )
        if (resp.statusCode != 200) {
            throw IOException("校园卡票据兑换失败（HTTP ${resp.statusCode}）")
        }
        val root = runCatching { Json.parseToJsonElement(resp.body) as? JsonObject }.getOrNull()
            ?: throw IOException("校园卡票据响应解析失败")
        val token = (root["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("校园卡未返回 access_token")
        return token
    }

    private suspend fun verifySelfIdentity(token: String, expectedStudentId: String) {
        val resp = transport.execute(
            CasHttpRequest(
                url = UpcCardAuthEndpoints.USER_INFO_URL,
                method = "GET",
                headers = mapOf(
                    "synjones-auth" to "bearer $token",
                    "synAccessSource" to "h5",
                    "Accept" to "application/json",
                ),
            ),
        )
        if (resp.statusCode != 200) {
            throw IOException("校园卡本人身份核验失败（HTTP ${resp.statusCode}）")
        }
        val root = runCatching { Json.parseToJsonElement(resp.body) as? JsonObject }.getOrNull()
            ?: throw IOException("校园卡身份信息解析失败")
        val code = (root["code"] as? JsonPrimitive)?.contentOrNull
        if (code != "200") {
            throw IOException("校园卡身份接口未通过")
        }
        val data = root["data"] as? JsonObject ?: throw IOException("校园卡身份数据缺失")
        val foundIds = mutableListOf<String>()
        fun collectIds(obj: JsonObject, depth: Int) {
            if (depth > 3) return
            for ((k, v) in obj) {
                if (k in setOf("sno", "username", "userName", "studentId", "studentNo")) {
                    (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { foundIds += it }
                } else if (v is JsonObject) {
                    collectIds(v, depth + 1)
                }
            }
        }
        collectIds(data, 0)
        if (foundIds.isEmpty() || foundIds.any { it != expectedStudentId }) {
            throw IOException("登录账号与校园卡本人学号不一致")
        }
    }

    private suspend fun executeWithJar(jar: UpcCasCookieJar, request: CasHttpRequest): CasHttpResponse {
        val cookieHeader = jar.cookieHeader(request.url)
        val mergedHeaders = if (cookieHeader != null) {
            request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } + ("Cookie" to cookieHeader)
        } else {
            request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        }
        val response = transport.execute(request.copy(headers = mergedHeaders))
        jar.saveFromHeaders(request.url, response.setCookieHeaders)
        return response
    }

    private fun resolveAndUpgradeCard(base: String, raw: String): String? {
        if (raw.isBlank()) return null
        val resolved = runCatching { URI(base).resolve(raw) }.getOrNull() ?: return null
        val upgraded = if (resolved.scheme.equals("http", ignoreCase = true) &&
            resolved.host?.lowercase(Locale.ROOT) == UpcCardAuthEndpoints.CARD_HOST &&
            resolved.port in setOf(-1, 80) &&
            resolved.rawUserInfo == null
        ) {
            "https://${UpcCardAuthEndpoints.CARD_HOST}${resolved.rawPath.orEmpty()}" +
                (resolved.rawQuery?.let { "?$it" } ?: "") +
                (resolved.rawFragment?.let { "#$it" } ?: "")
        } else {
            resolved.toString()
        }
        return upgraded.takeIf { isAllowedHost(it) }
    }

    private fun resolveHttpUri(base: String, raw: String): URI? =
        runCatching { URI(base).resolve(raw) }.getOrNull()

    private fun isAllowedHost(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null &&
            uri.host?.lowercase(Locale.ROOT) in setOf(
                UpcCardAuthEndpoints.CAS_HOST,
                UpcCardAuthEndpoints.CARD_HOST,
                UpcCardAuthEndpoints.PORTAL_HOST,
            )
    }.getOrDefault(false)

    private fun isCasHost(url: String): Boolean = runCatching {
        URI(url).host.equals(UpcCardAuthEndpoints.CAS_HOST, ignoreCase = true)
    }.getOrDefault(false)

    private fun isTokenLandingUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val path = uri.rawPath.orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(UpcCardAuthEndpoints.CARD_HOST, ignoreCase = true) &&
            (path == "/plat" || path.startsWith("/plat/") || path.startsWith("/campus-card/"))
    }.getOrDefault(false)

    private fun hasLoginResultMarker(url: String): Boolean = runCatching {
        val uri = URI(url)
        parseSingleQueryParam(uri, "synjones-auth") != null || parseSingleQueryParam(uri, "ticket") != null
    }.getOrDefault(false)

    private fun parseSingleQueryParam(uri: URI, key: String): String? {
        val query = uri.rawQuery ?: return null
        val matches = query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            val k = URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, StandardCharsets.UTF_8.name())
            if (k != key) return@mapNotNull null
            URLDecoder.decode(if (eq >= 0) pair.substring(eq + 1) else "", StandardCharsets.UTF_8.name())
        }
        return matches.singleOrNull()
    }

    private fun extractJsRedirect(body: String): String? {
        val script = Regex("(?is)<script\\b[^>]*>(.*?)</script>").findAll(body).joinToString("\n") { it.groupValues[1] }
        val raw = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"'\r\n]+)["']""").find(script)?.groupValues?.get(1)
            ?: Regex("""(?:window\.)?location\.(?:replace|assign)\(\s*["']([^"'\r\n]+)["']\s*\)""").find(script)?.groupValues?.get(1)
            ?: return null
        return raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
    }
}
