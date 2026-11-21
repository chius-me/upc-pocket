package me.chius.upcpocket.core.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import me.chius.upcpocket.core.model.SchoolLoginCredential

object UpcChaoxingAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val TYRZFW_HOST = "tyrzfw.chaoxing.com"
    const val PASSPORT2_API_HOST = "passport2-api.chaoxing.com"
    const val OFFICE_HOST = "office.chaoxing.com"
    const val I_CHAOXING_HOST = "i.chaoxing.com"
    const val CHAOXING_COOKIE_DOMAIN = "chaoxing.com"

    const val FID_ENC = "e32f8fbde51f9dba"
    const val MAPP_ID = "4197453"
    const val SEATENGINE_MAPP_ID = "20853788"
    const val SEATENGINE_SEAT_ID = "4258"

    const val SEAT_ENTRY =
        "https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=$FID_ENC&mappId=$MAPP_ID"
    const val SEATENGINE_ENTRY =
        "https://office.chaoxing.com/front/third/apps/seatengine/index" +
            "?fidEnc=$FID_ENC&mappId=$SEATENGINE_MAPP_ID&seatId=$SEATENGINE_SEAT_ID"
    const val TYRZFW_INIT_PATH = "/auth3/upc/cas/init"
    const val TYRZFW_CALLBACK_PATH = "/auth3/upc/cas/index"
    const val PASSPORT2_CASRECEIVE_PATH = "/api/v2/casreceive"
    const val OFFICE_SEAT_PATH = "/front/third/apps/seat/index"
    const val OFFICE_LEGACY_SEAT_PATH = "/front/apps/seat/index"
    const val CAS_SERVICE = "https://tyrzfw.chaoxing.com/auth3/upc/cas/index"
    const val MAX_REDIRECT_HOPS = 8
}

enum class UpcChaoxingAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCESS_DENIED,
}

sealed interface UpcChaoxingAuthResult {
    class Success(
        val cookieHeader: String,
        val indexHtml: String,
    ) : UpcChaoxingAuthResult {
        override fun toString(): String = "UpcChaoxingAuthResult.Success(cookieHeader=<redacted>)"
    }

    data class Failure(
        val reason: UpcChaoxingAuthFailure,
        val interactionUrl: String? = null,
        val message: String? = null,
    ) : UpcChaoxingAuthResult
}

/**
 * Single-shot CAS -> Chaoxing SSO authenticator (`office.chaoxing.com`):
 * `SEAT_ENTRY -> tyrzfw/init -> JS location -> CAS -> tyrzfw/index -> JS location -> passport2-api/casreceive -> refer -> office seat index`.
 *
 * Strictly validates every hop against fixed HTTPS hosts, paths, and query keys. Cookies stay
 * in RAM only and never leak into logs or disk storage.
 */
class UpcChaoxingAuthenticator(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    private val casService = UpcCasService(
        casHost = UpcChaoxingAuthEndpoints.CAS_HOST,
        serviceUrl = UpcChaoxingAuthEndpoints.CAS_SERVICE,
        callbackHost = UpcChaoxingAuthEndpoints.TYRZFW_HOST,
        callbackPath = UpcChaoxingAuthEndpoints.TYRZFW_CALLBACK_PATH,
        callbackParameterNames = setOf("ticket"),
        maxRedirectHops = UpcChaoxingAuthEndpoints.MAX_REDIRECT_HOPS,
    )

    suspend fun authenticate(credential: SchoolLoginCredential): UpcChaoxingAuthResult {
        val jar = UpcCasCookieJar(
            allowedHosts = setOf(
                UpcChaoxingAuthEndpoints.CAS_HOST,
                UpcChaoxingAuthEndpoints.TYRZFW_HOST,
                UpcChaoxingAuthEndpoints.PASSPORT2_API_HOST,
                UpcChaoxingAuthEndpoints.I_CHAOXING_HOST,
                UpcChaoxingAuthEndpoints.OFFICE_HOST,
                UpcChaoxingAuthEndpoints.CHAOXING_COOKIE_DOMAIN,
            ),
        )
        return try {
            performLogin(credential, jar)
        } finally {
            jar.clear()
        }
    }

    private suspend fun performLogin(
        credential: SchoolLoginCredential,
        jar: UpcCasCookieJar,
    ): UpcChaoxingAuthResult {
        val entry = executeWithJar(jar, CasHttpRequest(url = UpcChaoxingAuthEndpoints.SEAT_ENTRY, method = "GET"))
            .getOrFailure { return it }
        if (entry.statusCode !in 300..399) return failureForStatus(entry.statusCode)

        val initUri = entry.location
            ?.let { resolveUri(UpcChaoxingAuthEndpoints.SEAT_ENTRY, it) }
            ?: return protocolFailure()
        if (!isAllowedTyrzfwInit(initUri)) return protocolFailure()

        val initPage = executeWithJar(jar, CasHttpRequest(url = initUri.toString(), method = "GET"))
            .getOrFailure { return it }
        if (initPage.statusCode != 200) return failureForStatus(initPage.statusCode)

        val casUri = extractJsLocation(initPage.body)
            ?.let { resolveUri(initUri.toString(), it) }
            ?: return protocolFailure()
        if (!casService.isCasLoginUrl(casUri)) return protocolFailure()

        val callbackUri = when (val cas = UpcCasLogin(casService, transport).authenticateToCallback(credential, casUri.toString(), jar)) {
            is CasCallbackResult.Callback -> cas.callbackUri
            is CasCallbackResult.InvalidCredentials ->
                return UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.INVALID_CREDENTIALS)
            is CasCallbackResult.InteractionRequired ->
                return UpcChaoxingAuthResult.Failure(
                    reason = UpcChaoxingAuthFailure.INTERACTION_REQUIRED,
                    interactionUrl = cas.interactionUrl,
                    message = cas.reason,
                )
            is CasCallbackResult.NetworkError ->
                return UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE, message = cas.message)
            is CasCallbackResult.UpstreamError ->
                return UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.UPSTREAM_ERROR, message = cas.message)
        }

        var callbackPage = executeWithJar(jar, CasHttpRequest(url = callbackUri.toString(), method = "GET"))
            .getOrFailure { return it }
        if (callbackPage.statusCode in 300..399) {
            val next = callbackPage.location
                ?.let { resolveUri(callbackUri.toString(), it) }
                ?: return protocolFailure()
            if (!isAllowedTyrzfwCallback(next, requireTicket = false)) return protocolFailure()
            callbackPage = executeWithJar(jar, CasHttpRequest(url = next.toString(), method = "GET"))
                .getOrFailure { return it }
        }
        if (callbackPage.statusCode != 200) return failureForStatus(callbackPage.statusCode)

        val passportRaw = extractJsLocation(callbackPage.body) ?: return protocolFailure()
        val passportUri = resolveUri(callbackUri.toString(), passportRaw) ?: return protocolFailure()
        if (!isAllowedPassport2Casreceive(passportUri)) return protocolFailure()

        var receive = executeWithJar(jar, CasHttpRequest(url = passportUri.toString(), method = "GET"))
            .getOrFailure { return it }
        if (receive.statusCode in 300..399) {
            val next = receive.location
                ?.let { resolveUri(passportUri.toString(), it) }
                ?: return protocolFailure()
            if (!isAllowedIChaoxing(next)) return protocolFailure()
            receive = executeWithJar(jar, CasHttpRequest(url = next.toString(), method = "GET"))
                .getOrFailure { return it }
        }
        if (receive.statusCode != 200) return failureForStatus(receive.statusCode)

        val officeUri = extractHiddenInput(receive.body, "refer")
            ?.let(::decodeHtmlEntities)
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: return protocolFailure()
        if (!isAllowedOfficeSeatEntry(officeUri)) return protocolFailure()

        var currentOffice = officeUri
        var office = executeWithJar(jar, CasHttpRequest(url = currentOffice.toString(), method = "GET"))
            .getOrFailure { return it }
        var hops = 0
        while (office.statusCode in 300..399 && hops++ < UpcChaoxingAuthEndpoints.MAX_REDIRECT_HOPS) {
            val next = office.location
                ?.let { resolveUri(currentOffice.toString(), it) }
                ?: return protocolFailure()
            if (!isAllowedOfficeSeatEntry(next)) return protocolFailure()
            currentOffice = next
            office = executeWithJar(jar, CasHttpRequest(url = next.toString(), method = "GET"))
                .getOrFailure { return it }
        }
        if (office.statusCode != 200) return failureForStatus(office.statusCode)
        if (!office.body.contains("userLoginInfo")) return protocolFailure()

        val cookieHeader = jar.cookieHeader(UpcChaoxingAuthEndpoints.SEAT_ENTRY)
            ?.takeIf(::isPlausibleCookieHeader)
            ?: return protocolFailure()
        return UpcChaoxingAuthResult.Success(
            cookieHeader = cookieHeader,
            indexHtml = office.body,
        )
    }

    private suspend fun executeWithJar(
        jar: UpcCasCookieJar,
        request: CasHttpRequest,
    ): ChaoxingStepResult<CasHttpResponse> {
        return try {
            val cookieHeader = jar.cookieHeader(request.url)
            val headers = request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
                .let { if (cookieHeader != null) it + ("Cookie" to cookieHeader) else it }
            val response = transport.execute(request.copy(headers = headers))
            jar.saveFromHeaders(request.url, response.setCookieHeaders)
            ChaoxingStepResult.Ok(response)
        } catch (failure: CasTransportException) {
            val reason = when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE
                CasTransportFailure.TLS -> UpcChaoxingAuthFailure.TLS_ERROR
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> UpcChaoxingAuthFailure.UPSTREAM_ERROR
            }
            ChaoxingStepResult.Err(UpcChaoxingAuthResult.Failure(reason, message = failure.message))
        }
    }

    private fun failureForStatus(status: Int): UpcChaoxingAuthResult.Failure = when {
        status == 401 || status == 403 || status == 429 ->
            UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.ACCESS_DENIED)
        status >= 500 ->
            UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.UPSTREAM_ERROR)
        else -> protocolFailure()
    }

    private fun protocolFailure(): UpcChaoxingAuthResult.Failure =
        UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.PROTOCOL_ERROR)

    companion object {
        private val JS_LOCATION = Regex(
            """window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val HIDDEN_INPUT = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val ATTRIBUTE_ID = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val ATTRIBUTE_VALUE = Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
        private val HEX_ESCAPE = Regex("""\\x([0-9a-fA-F]{2})""")

        internal fun isAllowedHttpsHost(uri: URI, host: String): Boolean =
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase(Locale.ROOT) == host &&
                uri.port in setOf(-1, 443) &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null

        internal fun isAllowedTyrzfwInit(uri: URI): Boolean {
            if (!isAllowedHttpsHost(uri, UpcChaoxingAuthEndpoints.TYRZFW_HOST)) return false
            if (uri.rawPath != UpcChaoxingAuthEndpoints.TYRZFW_INIT_PATH) return false
            val query = parseSingleQuery(uri.rawQuery) ?: return false
            if (query.keys.any { it !in setOf("refer", "mappId") }) return false
            val refer = query["refer"] ?: return false
            if (!isAllowedSeatRefer(refer)) return false
            val mappId = query["mappId"]
            return mappId == null || mappId == UpcChaoxingAuthEndpoints.MAPP_ID
        }

        internal fun isAllowedTyrzfwCallback(uri: URI, requireTicket: Boolean): Boolean {
            if (!isAllowedHttpsHost(uri, UpcChaoxingAuthEndpoints.TYRZFW_HOST)) return false
            if (uri.rawPath != UpcChaoxingAuthEndpoints.TYRZFW_CALLBACK_PATH) return false
            val query = parseSingleQuery(uri.rawQuery) ?: return false
            if (requireTicket) {
                if (query.keys != setOf("ticket")) return false
                val ticket = query["ticket"] ?: return false
                return ticket.startsWith("ST-") && ticket.length in 8..4096
            }
            return query.isEmpty()
        }

        internal fun isAllowedPassport2Casreceive(uri: URI): Boolean {
            if (!isAllowedHttpsHost(uri, UpcChaoxingAuthEndpoints.PASSPORT2_API_HOST)) return false
            if (uri.rawPath != UpcChaoxingAuthEndpoints.PASSPORT2_CASRECEIVE_PATH) return false
            val allowedNames = setOf(
                "thirdinfo",
                "refer",
                "setmain",
                "time",
                "signature",
                "appid",
                "casfrom",
                "casinfo",
                "casenc",
            )
            val query = parseSingleQuery(uri.rawQuery) ?: return false
            if (query.isEmpty() || query.keys.any { it !in allowedNames }) return false
            if (query["thirdinfo"].isNullOrBlank()) return false
            if (query["refer"]?.let(::isAllowedSeatRefer) != true) return false
            if (query["setmain"] != "true") return false
            if (query["casfrom"] != null && query["casfrom"] != "login") return false
            return true
        }

        internal fun isAllowedIChaoxing(uri: URI): Boolean {
            if (!isAllowedHttpsHost(uri, UpcChaoxingAuthEndpoints.I_CHAOXING_HOST)) return false
            val path = uri.rawPath.orEmpty()
            return path.isEmpty() || path == "/" || path == "/base" || path == "/base/"
        }

        internal fun isAllowedOfficeSeatEntry(uri: URI): Boolean {
            if (!isAllowedHttpsHost(uri, UpcChaoxingAuthEndpoints.OFFICE_HOST)) return false
            val path = uri.rawPath
            if (path != UpcChaoxingAuthEndpoints.OFFICE_SEAT_PATH &&
                path != UpcChaoxingAuthEndpoints.OFFICE_LEGACY_SEAT_PATH
            ) {
                return false
            }
            val query = parseSingleQuery(uri.rawQuery) ?: return false
            if (query.keys != setOf("fidEnc", "mappId")) return false
            return query["fidEnc"] == UpcChaoxingAuthEndpoints.FID_ENC &&
                query["mappId"] == UpcChaoxingAuthEndpoints.MAPP_ID
        }

        internal fun isAllowedSeatRefer(raw: String): Boolean {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return false
            if (!isAllowedHttpsHost(uri, UpcChaoxingAuthEndpoints.OFFICE_HOST)) return false
            val path = uri.rawPath
            if (path != UpcChaoxingAuthEndpoints.OFFICE_SEAT_PATH &&
                path != UpcChaoxingAuthEndpoints.OFFICE_LEGACY_SEAT_PATH
            ) {
                return false
            }
            val query = parseSingleQuery(uri.rawQuery) ?: return false
            if (query["fidEnc"] != UpcChaoxingAuthEndpoints.FID_ENC) return false
            val allowedNames = setOf("fidEnc", "mappId", "seatId")
            return query.keys.all { it in allowedNames }
        }

        private fun parseSingleQuery(rawQuery: String?): Map<String, String>? {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val result = linkedMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                val key = runCatching { URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name()) }.getOrNull()
                    ?: return null
                val value = runCatching { URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name()) }.getOrNull()
                    ?: return null
                if (result.containsKey(key)) return null
                result[key] = value
            }
            return result
        }

        private fun resolveUri(base: String, raw: String): URI? =
            runCatching { URI(base).resolve(raw) }.getOrNull()

        private fun extractJsLocation(html: String): String? {
            val match = JS_LOCATION.find(html) ?: return null
            return decodeJsString(match.groupValues[1])
        }

        private fun extractHiddenInput(html: String, id: String): String? {
            val tag = HIDDEN_INPUT.findAll(html).firstOrNull { match ->
                ATTRIBUTE_ID.find(match.value)?.groupValues?.get(1) == id
            }?.value ?: return null
            return ATTRIBUTE_VALUE.find(tag)?.groupValues?.get(1)
        }

        private fun decodeJsString(value: String): String {
            var out = value.replace("\\/", "/")
            out = UNICODE_ESCAPE.replace(out) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            out = HEX_ESCAPE.replace(out) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            return out.replace("\\\"", "\"").replace("\\\\", "\\")
        }

        private fun decodeHtmlEntities(value: String): String = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        private fun isPlausibleCookieHeader(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= 8 * 1024 &&
                value.contains('=') &&
                !value.contains('\r') &&
                !value.contains('\n')
    }
}

private sealed interface ChaoxingStepResult<out T> {
    data class Ok<out T>(val value: T) : ChaoxingStepResult<T>
    data class Err(val failure: UpcChaoxingAuthResult.Failure) : ChaoxingStepResult<Nothing>
}

private inline fun <T> ChaoxingStepResult<T>.getOrFailure(onFailure: (UpcChaoxingAuthResult.Failure) -> Nothing): T =
    when (this) {
        is ChaoxingStepResult.Ok -> value
        is ChaoxingStepResult.Err -> onFailure(failure)
    }
