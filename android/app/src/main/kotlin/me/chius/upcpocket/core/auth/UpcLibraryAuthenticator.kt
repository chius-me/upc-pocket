package me.chius.upcpocket.core.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.chius.upcpocket.core.model.SchoolLoginCredential

object UpcLibraryAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val LIBRARY_HOST = "libspace.upc.edu.cn"
    const val ENTRY_URL =
        "https://libspace.upc.edu.cn/remote/static/sso/login" +
            "?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Flibseat%2F%23%2Flogin"
    const val PAGE_URL = "https://libspace.upc.edu.cn/libseat/"
    const val SERVICE =
        "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
    const val SSO_AUTH_URL = "https://libspace.upc.edu.cn/rest/ssoAuth"
    const val USER_URL = "https://libspace.upc.edu.cn/rest/v2/user"
    const val CALLBACK_PATH = "/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
    const val MAX_REDIRECT_HOPS = 8
}

data class UpcLibraryUser(
    val id: String,
    val username: String,
    val name: String,
)

enum class UpcLibraryAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCESS_DENIED,
    IDENTITY_MISMATCH,
}

sealed interface UpcLibraryAuthResult {
    class Success(
        val seatToken: String,
        val user: UpcLibraryUser,
    ) : UpcLibraryAuthResult {
        override fun toString(): String = "UpcLibraryAuthResult.Success(seatToken=<redacted>)"
    }

    data class Failure(
        val reason: UpcLibraryAuthFailure,
        val interactionUrl: String? = null,
        val message: String? = null,
    ) : UpcLibraryAuthResult
}

/**
 * Single-shot CAS authenticator for `libspace.upc.edu.cn` (`libseat`):
 *
 * 1. Opens library SSO entry and follows redirect into exact CAS `/cas/login`.
 * 2. Authenticates via [UpcCasLogin.authenticateToCallback].
 * 3. Follows allowed `libspace.upc.edu.cn` callback hops to obtain `appToken`.
 * 4. Exchanges `POST /rest/ssoAuth` for `seatToken` using [UpcLibrarySigning].
 * 5. Reads `GET /rest/v2/user` and verifies `user.username.trim() == credential.loginName.trim()`.
 */
class UpcLibraryAuthenticator(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    private val casService = UpcCasService(
        casHost = UpcLibraryAuthEndpoints.CAS_HOST,
        serviceUrl = UpcLibraryAuthEndpoints.SERVICE,
        callbackHost = UpcLibraryAuthEndpoints.LIBRARY_HOST,
        callbackPath = UpcLibraryAuthEndpoints.CALLBACK_PATH,
        callbackParameterNames = setOf("ticket"),
        maxRedirectHops = UpcLibraryAuthEndpoints.MAX_REDIRECT_HOPS,
    )

    suspend fun authenticate(credential: SchoolLoginCredential): UpcLibraryAuthResult {
        val jar = UpcCasCookieJar(
            allowedHosts = setOf(UpcLibraryAuthEndpoints.CAS_HOST, UpcLibraryAuthEndpoints.LIBRARY_HOST),
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
    ): UpcLibraryAuthResult {
        val entry = executeWithJar(jar, CasHttpRequest(url = UpcLibraryAuthEndpoints.ENTRY_URL, method = "GET"))
            .getOrElse { return it }

        if (entry.statusCode in setOf(401, 403, 429)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (entry.statusCode !in setOf(301, 302, 303, 307, 308)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        val casUri = entry.location
            ?.let { resolveUri(UpcLibraryAuthEndpoints.ENTRY_URL, it) }
            ?: return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        if (!casService.isCasLoginUrl(casUri)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }

        val callback = when (val casResult = UpcCasLogin(casService, transport).authenticateToCallback(credential, casUri.toString(), jar)) {
            is CasCallbackResult.Callback -> casResult.callbackUri
            is CasCallbackResult.InvalidCredentials ->
                return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.INVALID_CREDENTIALS)
            is CasCallbackResult.InteractionRequired ->
                return UpcLibraryAuthResult.Failure(
                    reason = UpcLibraryAuthFailure.INTERACTION_REQUIRED,
                    interactionUrl = casResult.interactionUrl,
                    message = casResult.reason,
                )
            is CasCallbackResult.NetworkError ->
                return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.NETWORK_UNAVAILABLE, message = casResult.message)
            is CasCallbackResult.UpstreamError ->
                return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR, message = casResult.message)
        }

        val appToken = followToAppToken(jar, callback).getOrElse { return it }

        val exchange = executeWithJar(
            jar = jar,
            request = CasHttpRequest(
                url = "${UpcLibraryAuthEndpoints.SSO_AUTH_URL}?token=${encode(appToken)}",
                method = "POST",
                headers = UpcLibrarySigning.signedHeaders(appToken, "POST") + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                ),
                formBody = "token=${encode(appToken)}",
            ),
        ).getOrElse { return it }

        if (exchange.statusCode in setOf(401, 403, 429)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (exchange.statusCode != 200) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
        }
        val seatToken = parseLibraryToken(exchange.body)
            .getOrElse { return UpcLibraryAuthResult.Failure(it) }

        val userResponse = executeWithJar(
            jar = jar,
            request = CasHttpRequest(
                url = "${UpcLibraryAuthEndpoints.USER_URL}?token=${encode(seatToken)}",
                method = "GET",
                headers = UpcLibrarySigning.signedHeaders(seatToken, "GET"),
            ),
        ).getOrElse { return it }

        if (userResponse.statusCode in setOf(401, 403, 429)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (userResponse.statusCode != 200) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
        }
        val user = parseLibraryUser(userResponse.body)
            .getOrElse { return UpcLibraryAuthResult.Failure(it) }
        if (user.username.trim() != credential.loginName.trim()) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.IDENTITY_MISMATCH)
        }
        return UpcLibraryAuthResult.Success(seatToken = seatToken, user = user)
    }

    private suspend fun followToAppToken(
        jar: UpcCasCookieJar,
        start: URI,
    ): EitherResult<String, UpcLibraryAuthResult.Failure> {
        var current = start
        repeat(UpcLibraryAuthEndpoints.MAX_REDIRECT_HOPS) {
            tokenFromUrl(current.toString())?.let { return EitherResult.Ok(it) }
            if (!isAllowedLibraryPage(current)) {
                return EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR))
            }
            val response = executeWithJar(jar, CasHttpRequest(url = current.toString(), method = "GET"))
                .getOrElse { return EitherResult.Err(it) }
            if (response.statusCode in 300..399) {
                val location = response.location
                    ?: return EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR))
                val target = resolveUri(current.toString(), location)
                    ?: return EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR))
                tokenFromUrl(target.toString())?.let { return EitherResult.Ok(it) }
                current = target
                return@repeat
            }
            if (response.statusCode in setOf(401, 403, 429)) {
                return EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED))
            }
            if (response.statusCode != 200) {
                return EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR))
            }
            return tokenFromUrl(current.toString())
                ?.let { EitherResult.Ok(it) }
                ?: EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR))
        }
        return EitherResult.Err(UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR))
    }

    private suspend fun executeWithJar(
        jar: UpcCasCookieJar,
        request: CasHttpRequest,
    ): EitherResult<CasHttpResponse, UpcLibraryAuthResult.Failure> {
        return try {
            val cookieHeader = jar.cookieHeader(request.url)
            val headers = request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
                .let { if (cookieHeader != null) it + ("Cookie" to cookieHeader) else it }
            val response = transport.execute(request.copy(headers = headers))
            jar.saveFromHeaders(request.url, response.setCookieHeaders)
            EitherResult.Ok(response)
        } catch (failure: CasTransportException) {
            val reason = when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> UpcLibraryAuthFailure.NETWORK_UNAVAILABLE
                CasTransportFailure.TLS -> UpcLibraryAuthFailure.TLS_ERROR
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> UpcLibraryAuthFailure.UPSTREAM_ERROR
            }
            EitherResult.Err(UpcLibraryAuthResult.Failure(reason, message = failure.message))
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun isAllowedLibraryPage(uri: URI): Boolean {
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host?.lowercase(Locale.ROOT) != UpcLibraryAuthEndpoints.LIBRARY_HOST ||
                uri.port !in setOf(-1, 443) ||
                uri.rawUserInfo != null
            ) {
                return false
            }
            val path = uri.rawPath.orEmpty()
            return path == UpcLibraryAuthEndpoints.CALLBACK_PATH ||
                path == "/libseat" ||
                path.startsWith("/libseat/")
        }

        fun tokenFromUrl(raw: String): String? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val candidates = mutableListOf<String>()
            val query = uri.rawQuery.orEmpty()
            if (query.isNotEmpty()) {
                extractTokenParam(query)?.let(candidates::add)
            }
            val fragment = uri.rawFragment.orEmpty()
            if (fragment.isNotEmpty()) {
                val fragmentQuery = fragment.substringAfter('?', fragment)
                extractTokenParam(fragmentQuery)?.let(candidates::add)
            }
            return candidates.firstOrNull { it.isNotBlank() && it.length <= 4096 }
        }

        private fun extractTokenParam(rawQuery: String): String? {
            for (pair in rawQuery.split('&')) {
                if (!pair.startsWith("token=")) continue
                val decoded = runCatching {
                    URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8.name())
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
            return null
        }

        private fun parseLibraryToken(body: String): EitherResult<String, UpcLibraryAuthFailure> {
            val root = parseRoot(body).getOrElse { return EitherResult.Err(it) }
            val data = root["data"] as? JsonObject ?: return EitherResult.Err(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            val token = (data["token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (token.isBlank()) return EitherResult.Err(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            return EitherResult.Ok(token)
        }

        private fun parseLibraryUser(body: String): EitherResult<UpcLibraryUser, UpcLibraryAuthFailure> {
            val root = parseRoot(body).getOrElse { return EitherResult.Err(it) }
            val data = root["data"] as? JsonObject ?: return EitherResult.Err(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            val id = (data["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val username = (data["username"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (id.isBlank() || username.isBlank()) {
                return EitherResult.Err(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            }
            val name = (data["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { username }
            return EitherResult.Ok(UpcLibraryUser(id = id, username = username, name = name))
        }

        private fun parseRoot(body: String): EitherResult<JsonObject, UpcLibraryAuthFailure> {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return EitherResult.Err(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            val code = (root["code"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
            if (code == 12) return EitherResult.Err(UpcLibraryAuthFailure.ACCESS_DENIED)
            val status = root["status"] as? JsonPrimitive
            if (status?.booleanOrNull == false ||
                status?.contentOrNull?.lowercase(Locale.ROOT) in setOf("fail", "error")
            ) {
                return EitherResult.Err(UpcLibraryAuthFailure.UPSTREAM_ERROR)
            }
            if (code != null && code != 0 && code != 200) {
                return EitherResult.Err(UpcLibraryAuthFailure.UPSTREAM_ERROR)
            }
            if (root["status"] == null && root["data"] == null && root["success"] == null) {
                return EitherResult.Err(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            }
            return EitherResult.Ok(root)
        }

        private fun resolveUri(base: String, raw: String): URI? =
            runCatching { URI(base).resolve(raw) }.getOrNull()

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}

private sealed interface EitherResult<out OkT, out ErrT> {
    data class Ok<out OkT>(val value: OkT) : EitherResult<OkT, Nothing>
    data class Err<out ErrT>(val error: ErrT) : EitherResult<Nothing, ErrT>
}

private inline fun <OkT, ErrT> EitherResult<OkT, ErrT>.getOrElse(onError: (ErrT) -> Nothing): OkT = when (this) {
    is EitherResult.Ok -> value
    is EitherResult.Err -> onError(error)
}
