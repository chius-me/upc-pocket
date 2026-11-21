package me.chius.upcpocket.feature.grades

import java.io.IOException

class GradesProtocolException(message: String) : IOException(message)

sealed interface GradesParseOutcome {
    data class Ok(val records: List<CourseGradeRecord>) : GradesParseOutcome
    data object AuthRequired : GradesParseOutcome
    data class IdentityMismatch(val message: String) : GradesParseOutcome
}

/**
 * Parser for UPC Qiangzhi `/jsxsd/kscj/cjcx_list` grade tables.
 * Verifies self identity (`expectedStudentId`) and rejects unauthenticated or partial documents.
 */
object GradesProtocol {
    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    fun formBody(): String = "kksj=&kcxz=&kcmc=&xsfs=all"

    fun parse(html: String, expectedStudentId: String): GradesParseOutcome {
        if (looksLikeLoginPage(html)) {
            return GradesParseOutcome.AuthRequired
        }
        val tableHtml = TABLE_REGEX.find(html)?.groupValues?.get(1)
            ?: throw GradesProtocolException("教务成绩页结构已变化，未找到成绩列表")

        if (!html.contains(expectedStudentId)) {
            return GradesParseOutcome.IdentityMismatch("教务页面未核对到本人学号，请重新登录")
        }

        val rows = TR_REGEX.findAll(tableHtml).map { it.groupValues[1] }.toList()
        if (rows.isEmpty()) {
            throw GradesProtocolException("教务成绩表格缺少表头")
        }

        val headers = TH_REGEX.findAll(rows.first()).map { cleanText(it.groupValues[1]) }.toList()
        val idxTerm = headers.indexOfFirst { it.contains("开课学期") }
        val idxCode = headers.indexOfFirst { it.contains("课程编号") }
        val idxName = headers.indexOfFirst { it.contains("课程名称") }
        val idxScore = headers.indexOfFirst { it == "成绩" || it.startsWith("成绩") }
        val idxCredit = headers.indexOfFirst { it.contains("学分") }
        val idxGpa = headers.indexOfFirst { it.contains("绩点") }
        val idxHours = headers.indexOfFirst { it.contains("总学时") }
        val idxRetake = headers.indexOfFirst { it.contains("补重") }
        val idxMode = headers.indexOfFirst { it.contains("考核") }
        val idxExamNature = headers.indexOfFirst { it.contains("考试性质") }
        val idxAttr = headers.indexOfFirst { it.contains("课程属性") }
        val idxNature = headers.indexOfFirst { it.contains("课程性质") }

        if (idxTerm < 0 || idxName < 0 || idxScore < 0 || idxCredit < 0) {
            throw GradesProtocolException("教务成绩列表表头字段缺失")
        }

        val records = mutableListOf<CourseGradeRecord>()
        val seenIds = mutableSetOf<String>()

        rows.drop(1).forEachIndexed { rowIndex, rowHtml ->
            val cells = TD_REGEX.findAll(rowHtml).map { cleanText(it.groupValues[1]) }.toList()
            if (cells.isEmpty()) return@forEachIndexed
            if (cells.size == 1 && ("未查询到" in cells.first() || "无数据" in cells.first())) {
                return@forEachIndexed
            }
            if (cells.size <= maxOf(idxTerm, idxName, idxScore, idxCredit)) return@forEachIndexed

            val term = cells[idxTerm]
            val courseName = cells[idxName]
            if (courseName.isBlank()) return@forEachIndexed

            val courseCode = cells.getOrNull(idxCode).orEmpty()
            val scoreText = cells[idxScore]
            val credit = cells[idxCredit].toDoubleOrNull() ?: return@forEachIndexed
            val numericScore = parseScoreToNumeric(scoreText)
            val gpaPoint = cells.getOrNull(idxGpa)?.toDoubleOrNull()
                ?: numericScore?.let { GpaCalculator.scoreToUpcGpa(it) }
                ?: 0.0

            val baseId = "${term}_${courseCode.ifBlank { courseName }}_$rowIndex"
            val uniqueId = if (seenIds.add(baseId)) baseId else "${baseId}_dup"

            records += CourseGradeRecord(
                id = uniqueId,
                term = term,
                courseCode = courseCode,
                courseName = courseName,
                scoreText = scoreText,
                numericScore = numericScore,
                credit = credit,
                totalHours = cells.getOrNull(idxHours).orEmpty(),
                gpaPoint = gpaPoint,
                retakeTerm = cells.getOrNull(idxRetake).orEmpty(),
                assessmentMode = cells.getOrNull(idxMode).orEmpty(),
                examNature = cells.getOrNull(idxExamNature).orEmpty(),
                courseAttribute = cells.getOrNull(idxAttr).orEmpty(),
                courseNature = cells.getOrNull(idxNature).orEmpty(),
            )
        }

        return GradesParseOutcome.Ok(records)
    }

    fun parseScoreToNumeric(rawScore: String): Double? {
        val trimmed = rawScore.trim()
        trimmed.toDoubleOrNull()?.let { return it.coerceIn(0.0, 100.0) }
        return when (trimmed) {
            "优", "优秀" -> 95.0
            "良", "良好" -> 85.0
            "中", "中等" -> 75.0
            "及格", "合格", "通过" -> 65.0
            "不及格", "不合格", "未通过", "缺考", "缓考", "旷考" -> 0.0
            else -> null
        }
    }

    private fun looksLikeLoginPage(html: String): Boolean {
        val head = html.take(12_000).lowercase()
        return ("cas.upc.edu.cn/cas/login" in head ||
            "/cas/login" in head ||
            "window.location.href='/sso.jsp'" in head) ||
            (("id=\"loginform\"" in head || "name=\"execution\"" in head) && "password" in head)
    }

    private fun cleanText(raw: String): String = raw
        .replace(TAG_REGEX, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private val TABLE_REGEX = Regex(
        """<table\b[^>]*id\s*=\s*["']?dataList["']?[^>]*>(.*?)</table>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TR_REGEX = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TH_REGEX = Regex("""<th\b[^>]*>(.*?)</th>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TD_REGEX = Regex("""<td\b[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
}
