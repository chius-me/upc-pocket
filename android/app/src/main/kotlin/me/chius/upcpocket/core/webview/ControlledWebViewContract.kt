package me.chius.upcpocket.core.webview

import java.net.URI
import me.chius.upcpocket.core.model.SchoolAccountState

/**
 * Single controlled WebView contract for future school captcha / secondary-challenge fallback.
 * Enforces exact HTTPS host allowlist and binds each session to the active accountId + generation.
 */
object ControlledWebViewContract {
    private const val MAX_URL_LENGTH = 16_384

    val allowedHttpsHosts: Set<String> = setOf(
        "cas.upc.edu.cn",
        "app.upc.edu.cn",
        "libspace.upc.edu.cn",
        "mb-xyk.upc.edu.cn",
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

    fun inspectNavigation(
        rawUrl: String,
        method: String = "GET",
        isMainFrame: Boolean = true,
    ): Decision {
        if (rawUrl.isBlank() || rawUrl.length > MAX_URL_LENGTH) return Decision.BLOCK
        if (rawUrl.any { it <= '\u001f' || it == '\u007f' }) return Decision.BLOCK

        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return Decision.BLOCK
        if (uri.isOpaque || uri.rawUserInfo != null) return Decision.BLOCK

        val scheme = uri.scheme?.lowercase() ?: return Decision.BLOCK
        if (scheme != "https") return Decision.BLOCK

        val host = uri.host?.lowercase()?.takeIf { !it.endsWith('.') } ?: return Decision.BLOCK
        if (host !in allowedHttpsHosts) return Decision.BLOCK
        if (uri.port !in setOf(-1, 443)) return Decision.BLOCK

        val normalizedMethod = method.uppercase()
        val path = uri.rawPath.orEmpty()
        return when (host) {
            "cas.upc.edu.cn" -> {
                val allowedCasLoginPost = path == "/cas/login" && normalizedMethod == "POST"
                val allowedCasGet = normalizedMethod == "GET" &&
                    (path == "/cas/login" || (!isMainFrame && path.startsWith("/cas/")))
                if (allowedCasLoginPost || allowedCasGet) Decision.ALLOW else Decision.BLOCK
            }
            else -> if (normalizedMethod in setOf("GET", "POST")) Decision.ALLOW else Decision.BLOCK
        }
    }
}
