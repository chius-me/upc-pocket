package me.chius.upcpocket.feature.shiguang

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Strictly read-only HTML parser for Shiguang Second Classroom (`dekt.upc.edu.cn`).
 * Contact/phone snippets inside activity descriptions are stripped before UI presentation.
 */
object ShiguangProtocol {
    const val BASE_URL = "https://dekt.upc.edu.cn"
    const val ACTIVITIES_PATH = "/actvMng/actvMngFront!jumpActvCampListPageForWeb.action"
    const val PARTICIPATIONS_PATH = "/actvMng/actvMngFront!jumpActvMyJoinListPageForWeb.action"
    const val ACTIVITY_DETAIL_PATH = "/actvMng/actvMngFront!jumpActvCampViewPageForWeb.action"
    const val PRACTICE_DETAIL_PATH = "/actvMng/actvMngFront!jumpSocialPracticeViewPageForWeb.action"
    const val SUMMARY_PATH = "/reportCardMng/reportCardMngFront!jumpReportCardFirstForWeb.action"

    private val PAGE_COUNT_REGEX = Regex("""共\s*([0-9]+)\s*页""")
    private val NUMERIC_ID_REGEX = Regex("""[0-9]{1,20}""")
    private val DECIMAL_REGEX = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")
    private val DETAIL_FIELD_REGEX = Regex("""^([^：:]{1,24})[：:]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
    private val LOGIN_HEAD_REGEX = Regex("""(?:id\s*=\s*['"]loginform|name\s*=\s*['"]execution|type\s*=\s*['"]password)""", RegexOption.IGNORE_CASE)
    private val SCRIPT_STYLE_REGEX = Regex(
        """<(script|style)\b[^>]*>(?:.*?</\1\s*>|.*$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENT_REGEX = Regex("""<!--[\s\S]*?-->""")
    private val HTML_TOKEN_REGEX = Regex("""<[^>]+>|[^<]+""")
    private val TAG_NAME_REGEX = Regex("""^<([A-Za-z0-9]+)""")
    private val ATTR_REGEX = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val ENTITY_REGEX = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);""")
    private val CONTACT_REGEX = Regex("""(?:联系人|联系电话)\s*[：:][^\n]*""")
    private val PHONE_REGEX = Regex("""(?<![0-9])(?:1[3-9][0-9]{9}|0[0-9]{2,3}[- ]?[0-9]{7,8})(?![0-9])""")
    private val HORIZONTAL_WS = Regex("""[ \t\r]+""")
    private val MULTI_NEWLINES = Regex("""\n\s*\n+""")

    private val VOID_TAGS = setOf(
        "input", "img", "br", "hr", "meta", "link", "area", "base", "wbr", "source", "embed",
    )
    private val LIST_FIELD_LABELS = listOf(
        "报名截止时间", "报名时间", "活动时间", "分类", "地点", "学时", "学分",
    )
    private val DETAIL_LABELS = setOf(
        "归属组织", "活动分类", "活动标签", "组织形式", "培养目标", "能力指标",
        "活动地点", "报名时间", "活动时间", "活动院系", "活动年级", "学时", "学分",
    )
    private val MODULE_LABELS = listOf(
        "思想成长", "创新创业", "文体发展", "社会实践与志愿服务", "工作履历与技能培训",
    )

    fun parseList(html: String, page: Int): ShiguangPage {
        val root = parseTree(html)
        val container = root.findClass("active_2_list") ?: protocolFail()
        val cards = container.walk().filter { "li" in it.classes }.toList()
        if (cards.isEmpty() && container.walk().any { it.tag == "li" }) protocolFail()

        val items = cards.map { card ->
            val link = card.walk().firstOrNull { it.tag == "a" && "ellipsis" in it.classes }
                ?: card.walk().firstOrNull { it.tag == "a" && "more" in it.classes }
                ?: protocolFail()
            val detailLink = card.walk().firstOrNull { it.tag == "a" && "more" in it.classes } ?: link
            val target = detailTarget(detailLink.attrs["href"].orEmpty())
                ?: detailTarget(link.attrs["href"].orEmpty())
                ?: protocolFail()
            val title = link.text().takeIf { it.isNotBlank() } ?: protocolFail()
            ShiguangActivity(
                id = target.first,
                kind = target.second,
                title = title,
                fields = extractFields(card.text(), LIST_FIELD_LABELS),
            )
        }
        if (items.isEmpty() && container.walk().any {
                it.tag == "a" && ("ellipsis" in it.classes || "more" in it.classes)
            }
        ) {
            protocolFail()
        }
        val totalPages = PAGE_COUNT_REGEX.find(root.text())?.groupValues?.get(1)?.toIntOrNull()
        if (totalPages != null && (totalPages !in 0..5000 || (items.isNotEmpty() && totalPages < page))) {
            protocolFail()
        }
        return ShiguangPage(page = page, totalPages = totalPages, items = items)
    }

    fun parseDetail(html: String): ShiguangDetail {
        val left = parseTree(html).findClass("activeD_l") ?: protocolFail()
        val title = left.findClass("t2")?.text()?.takeIf { it.isNotBlank() } ?: protocolFail()
        val fields = linkedMapOf<String, String>()
        left.walk().filter { "li" in it.classes }.forEach { node ->
            val match = DETAIL_FIELD_REGEX.find(node.text()) ?: return@forEach
            val label = match.groupValues[1].trim()
            if (label in DETAIL_LABELS) {
                fields[label] = cleanText(match.groupValues[2])
            }
        }
        val description = left.findClass("djs")?.text()?.takeIf { it.isNotBlank() }
        return ShiguangDetail(title = title, description = description, fields = fields)
    }

    fun parseSummary(html: String): ShiguangSummary {
        val inputs = parseTree(html).walk().filter { it.tag == "input" && "id" in it.attrs }.toList()
        val required = (0..4).flatMap { listOf("credit$it", "credit0$it") } + listOf("flag", "orderNum")
        if (required.any { key -> inputs.count { it.attrs["id"] == key } != 1 }) protocolFail()

        val values = inputs.associate { it.attrs.getValue("id") to it.attrs["value"].orEmpty() }
        val order = values.getValue("orderNum")
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
        if (order.sorted() != listOf(1, 2, 3, 4, 5) ||
            values.getValue("orderNum").split(',').count { it.isNotBlank() } != 5
        ) {
            protocolFail()
        }
        val ranks = values.getValue("flag").split(',').map(String::trim)
        if (ranks.size > 10 && ranks.drop(10).any(String::isNotBlank)) protocolFail()

        val modules = (1..5).map { id ->
            val posText = ranks.getOrNull((id - 1) * 2).orEmpty()
            val totalText = ranks.getOrNull((id - 1) * 2 + 1).orEmpty()
            val rank = if (posText.isBlank() || totalText.isBlank()) {
                null
            } else {
                val position = posText.toIntOrNull() ?: protocolFail()
                val total = totalText.toIntOrNull() ?: protocolFail()
                if (position < 0 || total < 0 || position > total) protocolFail()
                ShiguangRank(position = position, total = total)
            }
            ShiguangModule(
                id = id,
                label = MODULE_LABELS[id - 1],
                hours = parseDecimal(values.getValue("credit${id - 1}")),
                credits = parseDecimal(values.getValue("credit0${id - 1}")),
                rank = rank,
            )
        }
        return ShiguangSummary(modules = order.map { id -> modules.first { it.id == id } })
    }

    fun isLoginPage(html: String): Boolean {
        val head = html.take(30_000).lowercase()
        return LOGIN_HEAD_REGEX.containsMatchIn(head) &&
            ("cas/login" in head || "统一身份认证" in head || "caslogin.jsp" in head)
    }

    private fun detailTarget(href: String): Pair<String, ShiguangKind>? {
        if (href.isBlank()) return null
        val uri = runCatching { URI("$BASE_URL/").resolve(href.trim()) }.getOrNull() ?: return null
        if (uri.host != "dekt.upc.edu.cn" ||
            uri.scheme !in setOf("https", "http") ||
            uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 80, 443)
        ) {
            return null
        }
        val kind = when (uri.path) {
            ACTIVITY_DETAIL_PATH -> ShiguangKind.ACTIVITY
            PRACTICE_DETAIL_PATH -> ShiguangKind.SOCIAL_PRACTICE
            else -> return null
        }
        val ids = uri.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .filter { it[0] == "actvModel.activityId" }
            .map { decodeUrl(it.getOrElse(1) { "" }) }
        val id = ids.singleOrNull()?.takeIf { NUMERIC_ID_REGEX.matches(it) } ?: return null
        return id to kind
    }

    private fun parseDecimal(raw: String): BigDecimal? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length > 128 || !DECIMAL_REGEX.matches(trimmed)) protocolFail()
        val decimal = trimmed.toBigDecimalOrNull() ?: protocolFail()
        if (decimal.scale() !in -128..128) protocolFail()
        return decimal
    }

    private fun extractFields(text: String, labels: List<String>): Map<String, String> = buildMap {
        labels.forEach { label ->
            Regex("${Regex.escape(label)}\\s*[：:]\\s*([^\\n]+)")
                .find(text)
                ?.let { put(label, cleanText(it.groupValues[1])) }
        }
    }

    private fun decodeUrl(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrElse { protocolFail() }

    private fun parseTree(html: String): HtmlNode {
        if (isLoginPage(html)) {
            throw ShiguangException("石光二课会话已失效，请重新刷新", authRequired = true)
        }
        val root = HtmlNode("root")
        val stack = mutableListOf(root)
        val sanitized = html.replace(SCRIPT_STYLE_REGEX, "").replace(COMMENT_REGEX, "")
        HTML_TOKEN_REGEX.findAll(sanitized).forEach { token ->
            val raw = token.value
            if (!raw.startsWith('<')) {
                stack.last().parts.add(decodeEntities(raw))
            } else if (raw.startsWith("</")) {
                val tag = raw.removePrefix("</").substringBefore('>').trim().lowercase()
                val idx = stack.indexOfLast { it.tag == tag }
                if (idx > 0) {
                    while (stack.size > idx) stack.removeAt(stack.lastIndex)
                }
            } else if (!raw.startsWith("<!") && !raw.startsWith("<?")) {
                val tag = TAG_NAME_REGEX.find(raw)?.groupValues?.get(1)?.lowercase() ?: return@forEach
                val attrs = ATTR_REGEX.findAll(raw).associate { m ->
                    val valRaw = m.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
                    m.groupValues[1].lowercase() to decodeEntities(valRaw)
                }
                val node = HtmlNode(tag, attrs)
                stack.last().parts.add(node)
                if (tag !in VOID_TAGS && !raw.endsWith("/>")) {
                    stack.add(node)
                }
            }
        }
        return root
    }

    private class HtmlNode(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
    ) {
        val parts = mutableListOf<Any>()
        val classes: Set<String>
            get() = attrs["class"].orEmpty().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()

        fun walk(): Sequence<HtmlNode> = sequence {
            parts.filterIsInstance<HtmlNode>().forEach { child ->
                yield(child)
                yieldAll(child.walk())
            }
        }

        fun findClass(name: String): HtmlNode? = walk().firstOrNull { name in it.classes }

        fun text(): String = cleanText(
            parts.joinToString("") { part ->
                if (part is HtmlNode) {
                    part.text() + if (part.tag in setOf("div", "p", "li", "br", "tr")) "\n" else ""
                } else {
                    part.toString()
                }
            },
        )
    }

    private fun decodeEntities(raw: String): String = ENTITY_REGEX.replace(raw) { m ->
        when (val v = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> runCatching {
                val codePoint = if (v.startsWith("#x", ignoreCase = true)) {
                    v.drop(2).toInt(16)
                } else {
                    v.drop(1).toInt()
                }
                String(Character.toChars(codePoint))
            }.getOrDefault("")
        }
    }

    private fun cleanText(raw: String): String = raw
        .replace(CONTACT_REGEX, "")
        .replace(PHONE_REGEX, "")
        .replace(HORIZONTAL_WS, " ")
        .replace(MULTI_NEWLINES, "\n")
        .trim()

    private fun protocolFail(): Nothing =
        throw ShiguangException("石光二课页面结构发生变化，暂无法读取")
}
