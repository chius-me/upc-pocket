package me.chius.upcpocket.core.auth

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * In-memory cookie jar strictly scoped to [allowedHosts].
 * Never persists CAS TGT/session cookies across launches.
 */
class UpcCasCookieJar(
    private val allowedHosts: Set<String> = setOf("cas.upc.edu.cn", "app.upc.edu.cn"),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val expiresAtMillis: Long?, // null = session cookie
    ) {
        fun isExpired(now: Long): Boolean = expiresAtMillis != null && expiresAtMillis <= now

        fun matches(host: String, requestPath: String, isHttps: Boolean, now: Long): Boolean {
            if (isExpired(now)) return false
            if (domain != host) return false
            if (secure && !isHttps) return false
            if (path == "/") return true
            if (requestPath == path) return true
            if (!requestPath.startsWith(path)) return false
            return path.endsWith("/") || requestPath.getOrNull(path.length) == '/'
        }
    }

    private val entries = mutableListOf<StoredCookie>()

    @Synchronized
    fun saveFromHeaders(requestUrl: String, setCookieHeaders: List<String>) {
        val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return
        val requestHost = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return
        if (requestHost !in allowedHosts) return
        val defaultPath = defaultCookiePath(uri.rawPath)
        val now = nowMillis()

        setCookieHeaders.forEach { header ->
            val parsed = parseSetCookie(header, requestHost, defaultPath, now) ?: return@forEach
            if (parsed.domain !in allowedHosts) return@forEach
            entries.removeAll {
                it.name == parsed.name && it.domain == parsed.domain && it.path == parsed.path
            }
            if (!parsed.isExpired(now) && entries.size < MAX_COOKIES) {
                entries += parsed
            }
        }
    }

    @Synchronized
    fun cookieHeader(requestUrl: String): String? {
        val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host !in allowedHosts) return null
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val now = nowMillis()
        entries.removeAll { it.isExpired(now) }
        return entries
            .filter { it.matches(host, path, isHttps, now) }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun cookiesForHost(host: String): Map<String, String> {
        val normalizedHost = host.lowercase(Locale.ROOT).trimEnd('.')
        val now = nowMillis()
        entries.removeAll { it.isExpired(now) }
        val result = linkedMapOf<String, String>()
        entries.forEach { cookie ->
            if (cookie.domain == normalizedHost) {
                result[cookie.name] = cookie.value
            }
        }
        return result
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun parseSetCookie(
        rawHeader: String,
        requestHost: String,
        defaultPath: String,
        nowMillis: Long,
    ): StoredCookie? {
        val parts = rawHeader.split(';')
        val nameValue = parts.firstOrNull()?.trim().orEmpty()
        val eqIndex = nameValue.indexOf('=')
        if (eqIndex <= 0) return null
        val name = nameValue.substring(0, eqIndex).trim()
        val value = nameValue.substring(eqIndex + 1).trim()
        if (!COOKIE_NAME_REGEX.matches(name) || value.length > MAX_COOKIE_VALUE_LEN) return null

        var domain = requestHost
        var path = defaultPath
        var secure = false
        var maxAgeExpiresAt: Long? = null
        var hasMaxAge = false
        var expiresAt: Long? = null

        parts.drop(1).forEach { attr ->
            val trimmed = attr.trim()
            if (trimmed.isEmpty()) return@forEach
            val attrEq = trimmed.indexOf('=')
            val attrName = (if (attrEq >= 0) trimmed.substring(0, attrEq) else trimmed)
                .trim()
                .lowercase(Locale.ROOT)
            val attrValue = if (attrEq >= 0) trimmed.substring(attrEq + 1).trim() else ""
            when (attrName) {
                "domain" -> {
                    val normalized = attrValue.lowercase(Locale.ROOT).trimStart('.').trimEnd('.')
                    if (normalized.isNotEmpty()) {
                        domain = normalized
                    }
                }
                "path" -> {
                    if (attrValue.startsWith("/")) {
                        path = attrValue
                    }
                }
                "secure" -> secure = true
                "max-age" -> {
                    attrValue.toLongOrNull()?.let { seconds ->
                        hasMaxAge = true
                        maxAgeExpiresAt = if (seconds <= 0L) {
                            Long.MIN_VALUE
                        } else {
                            nowMillis + seconds * 1_000L
                        }
                    }
                }
                "expires" -> {
                    parseHttpDateMillis(attrValue)?.let { parsedMillis ->
                        expiresAt = parsedMillis
                    }
                }
            }
        }

        val effectiveExpiresAt = if (hasMaxAge) maxAgeExpiresAt else expiresAt
        return StoredCookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            secure = secure,
            expiresAtMillis = effectiveExpiresAt,
        )
    }

    private fun defaultCookiePath(rawPath: String?): String {
        if (rawPath.isNullOrEmpty() || !rawPath.startsWith("/")) return "/"
        val lastSlash = rawPath.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else rawPath.substring(0, lastSlash)
    }

    private fun parseHttpDateMillis(raw: String): Long? {
        return runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: runCatching {
            val cleaned = raw.replace("-", " ")
            ZonedDateTime.parse(
                cleaned,
                DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    .withZone(ZoneOffset.UTC),
            ).toInstant().toEpochMilli()
        }.getOrNull() ?: runCatching {
            Instant.parse(raw).toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        const val MAX_COOKIES = 64
        const val MAX_COOKIE_VALUE_LEN = 4096
        val COOKIE_NAME_REGEX = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}")
    }
}
