package me.chius.upcpocket.core.webview

import java.net.URI
import java.util.Locale
import me.chius.upcpocket.core.model.SchoolAccountState

/**
 * Single controlled WebView contract for school captcha / secondary-challenge fallback.
 * Enforces exact HTTPS host allowlist and binds each session to the active accountId + generation.
 */
object ControlledWebViewContract {
    private const val MAX_URL_LENGTH = 16_384
    private val COOKIE_NAME_REGEX = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}")

    private val upgradableHttpCallbackHosts: Set<String> = setOf(
        "jwxt.upc.edu.cn",
        "mb-xyk.upc.edu.cn",
        "dekt.upc.edu.cn",
    )

    val allowedHttpsHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "app.upc.edu.cn",
        "jwxt.upc.edu.cn",
        "libspace.upc.edu.cn",
        "mb-xyk.upc.edu.cn",
        "dekt.upc.edu.cn",
        "i.upc.edu.cn",
        "tyrzfw.chaoxing.com",
        "passport2-api.chaoxing.com",
        "i.chaoxing.com",
        "office.chaoxing.com",
    )

    val timetableScopeHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "app.upc.edu.cn",
    )

    val jwxtScopeHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "jwxt.upc.edu.cn",
    )

    val cardScopeHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "mb-xyk.upc.edu.cn",
        "i.upc.edu.cn",
    )

    val shiguangScopeHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "dekt.upc.edu.cn",
    )

    val libraryScopeHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "libspace.upc.edu.cn",
    )

    val chaoxingScopeHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "tyrzfw.chaoxing.com",
        "passport2-api.chaoxing.com",
        "i.chaoxing.com",
        "office.chaoxing.com",
    )

    enum class Decision {
        ALLOW,
        BLOCK,
    }

    data class SessionContext(
        val accountId: String?,
        val generation: Long,
        val entryUrl: String,
    ) {
        fun isStillValidFor(current: SchoolAccountState): Boolean =
            current.accountId == accountId && current.generation == generation
    }

    fun upgradeCallbackHttpToHttpsIfAllowed(
        rawUrl: String,
        scopeHosts: Set<String> = allowedHttpsHosts,
    ): String? {
        if (rawUrl.isBlank() || rawUrl.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { !it.endsWith('.') } ?: return null
        if (host !in upgradableHttpCallbackHosts || host !in scopeHosts) return null
        if (uri.port !in setOf(-1, 80)) return null

        val upgraded = "https://$host${uri.rawPath.orEmpty()}" +
            (uri.rawQuery?.let { "?$it" } ?: "") +
            (uri.rawFragment?.let { "#$it" } ?: "")
        return upgraded.takeIf {
            inspectNavigation(it, method = "GET", isMainFrame = true, scopeHosts = scopeHosts) == Decision.ALLOW
        }
    }

    fun inspectEntryUrl(
        rawUrl: String,
        scopeHosts: Set<String> = allowedHttpsHosts,
    ): Decision {
        if (rawUrl.equals("about:blank", ignoreCase = true)) return Decision.BLOCK
        return inspectNavigation(
            rawUrl = rawUrl,
            method = "GET",
            isMainFrame = true,
            scopeHosts = scopeHosts,
        )
    }

    fun inspectNavigation(
        rawUrl: String,
        method: String = "GET",
        isMainFrame: Boolean = true,
        scopeHosts: Set<String> = allowedHttpsHosts,
    ): Decision {
        if (rawUrl.isBlank() || rawUrl.length > MAX_URL_LENGTH) return Decision.BLOCK
        if (rawUrl.any { it <= '\u001f' || it == '\u007f' }) return Decision.BLOCK

        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return Decision.BLOCK
        if (uri.isOpaque || uri.rawUserInfo != null) return Decision.BLOCK

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Decision.BLOCK
        if (scheme != "https") return Decision.BLOCK

        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { !it.endsWith('.') } ?: return Decision.BLOCK
        if (host !in allowedHttpsHosts || host !in scopeHosts) return Decision.BLOCK
        if (uri.port !in setOf(-1, 443)) return Decision.BLOCK

        val path = uri.rawPath.orEmpty()
        if (path.contains("..") || path.contains('\\')) return Decision.BLOCK

        val normalizedMethod = method.uppercase(Locale.ROOT)
        if (normalizedMethod !in setOf("GET", "POST")) return Decision.BLOCK

        return when (host) {
            "cas.upc.edu.cn" -> {
                if (path.startsWith("/cas/")) Decision.ALLOW else Decision.BLOCK
            }
            "jwxt.upc.edu.cn" -> {
                if (path == "/sso.jsp" || path.startsWith("/jsxsd/")) Decision.ALLOW else Decision.BLOCK
            }
            "mb-xyk.upc.edu.cn" -> {
                if (path.startsWith("/berserker-auth/") ||
                    path.startsWith("/berserker-base/") ||
                    path == "/plat" ||
                    path.startsWith("/plat/") ||
                    path.startsWith("/campus-card/")
                ) {
                    Decision.ALLOW
                } else {
                    Decision.BLOCK
                }
            }
            "dekt.upc.edu.cn" -> {
                if (path == "/caslogin.jsp" ||
                    path.startsWith("/sysmng/") ||
                    path.startsWith("/actvMng/") ||
                    path.startsWith("/reportCardMng/")
                ) {
                    Decision.ALLOW
                } else {
                    Decision.BLOCK
                }
            }
            "libspace.upc.edu.cn" -> {
                if (path == "/libseat" ||
                    path.startsWith("/libseat/") ||
                    path.startsWith("/remote/static/")
                ) {
                    Decision.ALLOW
                } else {
                    Decision.BLOCK
                }
            }
            "tyrzfw.chaoxing.com" -> {
                if (path.startsWith("/auth3/upc/cas/")) Decision.ALLOW else Decision.BLOCK
            }
            "passport2-api.chaoxing.com" -> {
                if (path == "/api/v2/casreceive") Decision.ALLOW else Decision.BLOCK
            }
            "i.chaoxing.com" -> {
                if (path.isEmpty() || path == "/" || path == "/base" || path == "/base/") {
                    Decision.ALLOW
                } else {
                    Decision.BLOCK
                }
            }
            "office.chaoxing.com" -> {
                if (path.startsWith("/front/third/apps/seat/") ||
                    path.startsWith("/front/third/apps/seatengine/") ||
                    path.startsWith("/front/apps/seat/") ||
                    path.startsWith("/data/apps/seat/") ||
                    path.startsWith("/data/apps/seatengine/")
                ) {
                    Decision.ALLOW
                } else {
                    Decision.BLOCK
                }
            }
            else -> Decision.ALLOW
        }
    }

    fun parseCookieHeader(rawCookieHeader: String?): Map<String, String> {
        if (rawCookieHeader.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawCookieHeader.split(';').forEach { segment ->
            val trimmed = segment.trim()
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) return@forEach
            val name = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            if (COOKIE_NAME_REGEX.matches(name) &&
                value.length <= 4096 &&
                value.none { it <= '\u001f' || it == '\u007f' }
            ) {
                result[name] = value
            }
        }
        return result
    }
}
