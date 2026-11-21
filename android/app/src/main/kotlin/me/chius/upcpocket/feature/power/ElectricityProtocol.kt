package me.chius.upcpocket.feature.power

import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder

/**
 * Tangdaowan dormitory electricity query protocol (`dfcz.upc.edu.cn`, ASP.NET WebForms).
 * Read-only only; preserves exact room matching to avoid ASP.NET fuzzy substring false positives.
 */
object ElectricityProtocol {
    const val HOST = "http://dfcz.upc.edu.cn"
    const val PAGE_PATH = "/ShiYou/Default.aspx"
    const val NEUTRAL_CODE = "0"
    const val MAX_RESPONSE_BYTES = 1024 * 1024

    data class Form(
        val viewState: String,
        val viewStateGenerator: String,
        val eventValidation: String,
        val buildings: List<String>,
    )

    data class Candidate(
        val systemNo: String,
        val building: String,
        val room: String,
    )

    data class Reading(
        val building: String,
        val room: String,
        val systemNo: String,
        val kwh: BigDecimal,
    )

    sealed interface Outcome {
        data class Success(val reading: Reading) : Outcome
        data class Ambiguous(val candidates: List<Candidate>) : Outcome
    }

    class PageException(
        message: String,
        val kind: Kind,
    ) : IOException(message) {
        enum class Kind {
            AUTH_REQUIRED,
            FORMAT_CHANGED,
            ROOM_NOT_FOUND,
        }
    }

    fun parseForm(html: String): Form {
        if (html.contains("open.weixin.qq.com") && !html.contains("id=\"form1\"")) {
            throw PageException(
                "学校电费页面要求先完成微信授权，当前会话不可用",
                PageException.Kind.AUTH_REQUIRED,
            )
        }
        val viewState = hidden(html, "__VIEWSTATE")
        val generator = hidden(html, "__VIEWSTATEGENERATOR") ?: ""
        val validation = hidden(html, "__EVENTVALIDATION")
        if (viewState == null || validation == null || !html.contains("id=\"form1\"")) {
            throw PageException(
                "学校电费页面结构已变化，暂时无法查询",
                PageException.Kind.FORMAT_CHANGED,
            )
        }
        val buildings = Regex("<option\\s+value=\"([^\"]+)\"")
            .findAll(html)
            .map { decodeEntities(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (buildings.isEmpty()) {
            throw PageException(
                "学校电费页面没有返回楼号列表，请稍后再试",
                PageException.Kind.FORMAT_CHANGED,
            )
        }
        return Form(viewState, generator, validation, buildings)
    }

    fun parseResult(html: String, requestedRoom: String): Outcome {
        val requested = requestedRoom.trim()
        val grid = parseGrid(html)
        val candidates = parseCandidates(html)

        if (candidates.isNotEmpty()) {
            val exact = candidates.filter { it.room.trim() == requested }
            val plotted = grid?.reading
            val consistent = plotted == null ||
                plotted.room.trim().isEmpty() ||
                plotted.room.trim() == requested
            if (exact.size == 1 && consistent) {
                val hit = exact.single()
                val kwh = plotted?.kwh ?: throw notFound()
                return Outcome.Success(
                    Reading(
                        building = hit.building,
                        room = hit.room,
                        systemNo = hit.systemNo,
                        kwh = kwh,
                    ),
                )
            }
            return Outcome.Ambiguous(candidates)
        }

        if (grid != null) {
            val reading = grid.reading
            if (reading.room.isNotBlank() && reading.room.trim() != requested) {
                return Outcome.Ambiguous(
                    listOf(
                        Candidate(
                            systemNo = reading.systemNo,
                            building = reading.building,
                            room = reading.room,
                        ),
                    ),
                )
            }
            return Outcome.Success(
                Reading(
                    building = reading.building,
                    room = reading.room.ifBlank { requested },
                    systemNo = reading.systemNo,
                    kwh = reading.kwh,
                ),
            )
        }

        if (html.contains("楼列表为空")) {
            throw PageException(
                "学校电费页面没有可用楼号，请稍后再试",
                PageException.Kind.FORMAT_CHANGED,
            )
        }
        throw notFound()
    }

    fun parseCandidates(html: String): List<Candidate> {
        val table = tableById(html, "yonghuGridView") ?: return emptyList()
        val rows = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(table).map { it.groupValues[1] }.toList()
        val result = ArrayList<Candidate>()
        rows.forEach { row ->
            val cells = cells(row)
            if (cells.size < 3) return@forEach
            if (cells.any { it.contains("系统编号") }) return@forEach
            val systemNo = cells[0].trim()
            val building = cells[1].trim()
            val room = cells[2].trim()
            if (systemNo.isEmpty() || room.isEmpty()) return@forEach
            result += Candidate(systemNo = systemNo, building = building, room = room)
        }
        return result
    }

    fun parseKwh(raw: String): BigDecimal {
        val text = decodeEntities(raw).replace("\u00a0", " ").trim()
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*度$").find(text)
            ?: throw PageException(
                "剩余电量字段格式已变化：$text",
                PageException.Kind.FORMAT_CHANGED,
            )
        return runCatching { BigDecimal(match.groupValues[1]) }.getOrElse {
            throw PageException("剩余电量不是有效数字", PageException.Kind.FORMAT_CHANGED)
        }
    }

    fun formBody(form: Form, building: String, room: String): String = listOf(
        "__VIEWSTATE" to form.viewState,
        "__VIEWSTATEGENERATOR" to form.viewStateGenerator,
        "__EVENTVALIDATION" to form.eventValidation,
        "lou" to building,
        "yonghu" to room,
        "chaxun" to "查询",
    ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun notFound() =
        PageException("没有找到该房间，请核对楼号与房间号", PageException.Kind.ROOM_NOT_FOUND)

    private data class Grid(val reading: Reading)

    private fun parseGrid(html: String): Grid? {
        val table = tableById(html, "yongdianGrid") ?: return null
        val cells = cells(table)
        val labelIndex = cells.indexOfFirst { it.contains("当前剩余电量") }
        if (labelIndex < 0 || labelIndex + 1 >= cells.size) {
            throw PageException(
                "学校电费页面已变化，未能读到剩余电量",
                PageException.Kind.FORMAT_CHANGED,
            )
        }
        val raw = cells[labelIndex + 1]
        val hidden = cells.drop(labelIndex + 2).filter { it.isNotBlank() }
        return Grid(
            Reading(
                building = "",
                room = hidden.getOrNull(1).orEmpty(),
                systemNo = hidden.getOrNull(0).orEmpty(),
                kwh = parseKwh(raw),
            ),
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun hidden(html: String, name: String): String? {
        val match = Regex(
            "name=\"" + Regex.escape(name) + "\"[^>]*value=\"([^\"]*)\"",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        return decodeEntities(match.groupValues[1])
    }

    private fun tableById(html: String, id: String): String? {
        val open = Regex(
            "<table[^>]*id=\"" + Regex.escape(id) + "\"[^>]*>",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        val end = html.indexOf("</table>", open.range.last)
        if (end < 0) return null
        return html.substring(open.range.first, end)
    }

    private fun cells(fragment: String): List<String> =
        Regex("(?is)<td[^>]*>(.*?)</td>")
            .findAll(fragment)
            .map { stripTags(it.groupValues[1]) }
            .toList()

    private fun stripTags(raw: String): String =
        decodeEntities(raw.replace(Regex("(?is)<[^>]+>"), " "))
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun decodeEntities(raw: String): String = raw
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
