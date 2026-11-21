package me.chius.upcpocket.feature.calendar

import java.net.URI
import java.util.Locale

/**
 * Pure parser for UPC Academic Affairs public school calendar (`https://jwc.upc.edu.cn/603/list.htm`).
 * Validates all article and image URLs strictly to HTTPS `jwc.upc.edu.cn`.
 */
object CalendarProtocol {
    const val HOST = "jwc.upc.edu.cn"
    const val LIST_URL = "https://jwc.upc.edu.cn/603/list.htm"

    private val ANCHOR_REGEX = Regex(
        """<a\b([^>]*)href\s*=\s*["']([^"']*/\d{4}/\d{4}/c\d+a\d+/page\.htm)["']([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TITLE_ATTR_REGEX = Regex(
        """\btitle\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_IN_PATH_REGEX = Regex("""/(\d{4})/(\d{2})(\d{2})/c\d+a\d+/page\.htm""")
    private val IMG_SRC_REGEX = Regex(
        """<img\b[^>]*\bsrc\s*=\s*["']([^"']*/_upload/article/images/[^"']+\.(?:jpg|jpeg|png|webp)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val UPLOAD_PATH_REGEX = Regex(
        """(/_upload/article/images/[^"'\s<>)]+\.(?:jpg|jpeg|png|webp))""",
        RegexOption.IGNORE_CASE,
    )
    private val TAG_REGEX = Regex("""<[^>]+>""")
    private val WS_REGEX = Regex("""\s+""")

    fun parseList(html: String): List<CalendarArticleSummary> {
        val seen = linkedMapOf<String, CalendarArticleSummary>()
        ANCHOR_REGEX.findAll(html).forEach { match ->
            val beforeAttrs = match.groupValues[1]
            val rawHref = match.groupValues[2].trim()
            val afterAttrs = match.groupValues[3]
            val innerHtml = match.groupValues[4]

            val resolvedUrl = resolveAllowedJwcUrl(LIST_URL, rawHref) ?: return@forEach
            val innerTitle = innerHtml
                .replace(TAG_REGEX, " ")
                .let(::decodeEntities)
                .replace(WS_REGEX, " ")
                .trim()
            val attrTitle = TITLE_ATTR_REGEX.find("$beforeAttrs $afterAttrs")
                ?.groupValues?.get(1)
                ?.let(::decodeEntities)
                ?.replace(WS_REGEX, " ")
                ?.trim()
                .orEmpty()
            val title = attrTitle.ifBlank { innerTitle }
            if (title.isBlank()) return@forEach

            val dateMatch = DATE_IN_PATH_REGEX.find(resolvedUrl)
            val publishDate = dateMatch?.let { m ->
                "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
            }
            seen.putIfAbsent(
                resolvedUrl,
                CalendarArticleSummary(
                    title = title,
                    pageUrl = resolvedUrl,
                    publishDate = publishDate,
                ),
            )
        }
        return seen.values.toList()
    }

    fun parseArticleImages(html: String, articleUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        IMG_SRC_REGEX.findAll(html).forEach { match ->
            resolveAllowedJwcUrl(articleUrl, match.groupValues[1].trim())?.let { urls += it }
        }
        if (urls.isEmpty()) {
            UPLOAD_PATH_REGEX.findAll(html).forEach { match ->
                resolveAllowedJwcUrl(articleUrl, match.groupValues[1].trim())?.let { urls += it }
            }
        }
        return urls.toList()
    }

    fun resolveAllowedJwcUrl(baseUrl: String, raw: String): String? {
        if (raw.isBlank() || raw.any { it <= ' ' || it == '\\' }) return null
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(raw) }.getOrNull() ?: return null
        val scheme = resolved.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = resolved.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("https", "http") || host != HOST || resolved.rawUserInfo != null) {
            return null
        }
        if (resolved.port !in setOf(-1, 80, 443)) return null
        return URI(
            "https",
            null,
            HOST,
            -1,
            resolved.rawPath,
            resolved.rawQuery,
            null,
        ).toString()
    }

    private fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
