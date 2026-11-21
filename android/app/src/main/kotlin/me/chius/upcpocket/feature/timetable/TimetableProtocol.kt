package me.chius.upcpocket.feature.timetable

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface TimetableParseResult<out T> {
    data class Ok<T>(val value: T) : TimetableParseResult<T>
    data object AuthRequired : TimetableParseResult<Nothing>
    data class UpstreamError(val message: String) : TimetableParseResult<Nothing>
}

/**
 * Strict read-only parser for `app.upc.edu.cn/timetable/wap/default/get-index` and `get-datatmp`.
 */
object TimetableProtocol {
    const val MAX_WEEKS = 30
    const val MAX_PERIOD = 14
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    private val TERM_LABELS = mapOf("1" to "秋季", "2" to "春季", "3" to "夏季")
    private val DATE_REGEX = Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$")
    private val CELL_KEY_REGEX = Regex("^c_(\\d{1,2})$")
    private val WEEK_TOKEN_REGEX = Regex("\\d{1,2}")
    private val WEEK_RANGE_REGEX = Regex("^(\\d{1,2})\\s*[-~—－]\\s*(\\d{1,2})$")
    private val WEEK_SEPARATOR_REGEX = Regex("[,，、;；\\s]+")

    fun parseIndex(raw: String): TimetableParseResult<TimetableIndex> {
        val root = decodeJson(raw) ?: return nonJsonFailure(raw)
        val obj = root as? JsonObject
            ?: return TimetableParseResult.UpstreamError("课表索引根节点格式异常")

        val code = integralIntOrNull(obj["e"])
            ?: return TimetableParseResult.UpstreamError("课表索引响应缺少合法状态码 e")
        if (code == 10013) return TimetableParseResult.AuthRequired
        if (code != 0) return TimetableParseResult.UpstreamError("课表索引服务返回错误码（$code）")

        val data = obj["d"] as? JsonObject
            ?: return TimetableParseResult.UpstreamError("课表索引缺少 d 数据对象")
        val rawTerms = data["termInfo"] as? JsonArray
            ?: return TimetableParseResult.UpstreamError("课表索引缺少学期列表 termInfo")
        if (rawTerms.isEmpty()) {
            return TimetableParseResult.UpstreamError("课表学期列表为空")
        }

        val terms = mutableListOf<TimetableTermInfo>()
        val seenTerms = mutableSetOf<Pair<String, String>>()
        for (element in rawTerms) {
            val item = element as? JsonObject ?: continue
            val year = optionalText(item["year"]) ?: continue
            val term = optionalText(item["term"]) ?: continue
            val startDay = parseLocalDateOrNull(optionalText(item["startday"])) ?: continue
            val totalWeeks = integralIntOrNull(item["countweek"]) ?: continue
            if (totalWeeks !in 1..MAX_WEEKS) continue
            if (!seenTerms.add(year to term)) continue
            terms += TimetableTermInfo(
                year = year,
                term = term,
                startDayIso = startDay.format(DateTimeFormatter.ISO_LOCAL_DATE),
                totalWeeks = totalWeeks,
                id = optionalText(item["id"]),
                label = "$year ${TERM_LABELS[term] ?: "第${term}学期"}",
            )
        }
        if (terms.isEmpty()) {
            return TimetableParseResult.UpstreamError("未识别到任何有效学期配置")
        }

        val params = data["params"] as? JsonObject
        val currentYear = optionalText(params?.get("year"))
        val currentTerm = optionalText(params?.get("term"))
        if (currentYear != null && currentTerm != null &&
            terms.none { it.year == currentYear && it.term == currentTerm }
        ) {
            // Do not silently fall back to historical terms when the active term is unreadable.
            return TimetableParseResult.UpstreamError("当前学期配置缺失或无法解析")
        }

        val currentWeek = params?.get("week")?.let { integralIntOrNull(it) }
            ?.takeIf { it in 1..MAX_WEEKS }
        val currentWeekdays = parseConsecutiveWeekDates(data["weekdays"])
            ?.map { it.format(DateTimeFormatter.ISO_LOCAL_DATE) }
        val currentWeekday = normalizeWeekday(data["weekday"])

        return TimetableParseResult.Ok(
            TimetableIndex(
                terms = terms,
                currentYear = currentYear,
                currentTerm = currentTerm,
                currentWeek = currentWeek,
                currentWeekdaysIso = currentWeekdays,
                currentWeekday = currentWeekday,
            ),
        )
    }

    fun parseWeek(
        raw: String,
        term: TimetableTermInfo,
        requestedWeek: Int,
        fetchedAtEpochMillis: Long = 0L,
    ): TimetableParseResult<TimetableWeek> {
        if (requestedWeek !in 1..term.totalWeeks) {
            return TimetableParseResult.UpstreamError("请求教学周超出学期周数范围")
        }
        val termStart = runCatching { LocalDate.parse(term.startDayIso, DateTimeFormatter.ISO_LOCAL_DATE) }
            .getOrNull()
            ?: return TimetableParseResult.UpstreamError("学期起始日期格式异常")

        val root = decodeJson(raw) ?: return nonJsonFailure(raw)
        val obj = root as? JsonObject
            ?: return TimetableParseResult.UpstreamError("周课表根节点格式异常")

        val code = integralIntOrNull(obj["e"])
            ?: return TimetableParseResult.UpstreamError("周课表缺少合法状态码 e")
        if (code == 10013) return TimetableParseResult.AuthRequired
        if (code != 0) return TimetableParseResult.UpstreamError("周课表服务返回错误码（$code）")

        val data = obj["d"] as? JsonObject
            ?: return TimetableParseResult.UpstreamError("周课表缺少 d 数据对象")
        val classes = data["classes"] as? JsonArray
            ?: return TimetableParseResult.UpstreamError("周课表缺少 classes 数组")
        if (classes.size != 7) {
            return TimetableParseResult.UpstreamError("周课表天数不为 7 天")
        }

        val dates = parseConsecutiveWeekDates(data["weekdays"])
            ?: return TimetableParseResult.UpstreamError("周课表 weekdays 日期格式或连续性异常")
        val expectedStart = termStart.plusDays((requestedWeek - 1L) * 7L)
        if (dates.first() != expectedStart) {
            return TimetableParseResult.UpstreamError("返回日期与请求第 $requestedWeek 周不匹配")
        }

        val allMeetings = mutableListOf<CourseMeeting>()
        val dayModels = mutableListOf<TimetableDay>()

        for ((dayIndex, dayElement) in classes.withIndex()) {
            val dayObj = dayElement as? JsonObject
                ?: return TimetableParseResult.UpstreamError("周课表第 ${dayIndex + 1} 天数据结构异常")
            val date = dates[dayIndex]
            val dateIso = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weekday = date.dayOfWeek.value
            val dayMeetings = mutableListOf<CourseMeeting>()

            for ((key, value) in dayObj) {
                val match = CELL_KEY_REGEX.matchEntire(key)
                if (match == null) {
                    if (key.startsWith("c_")) {
                        return TimetableParseResult.UpstreamError("周课表存在未知节次键 $key")
                    }
                    continue
                }
                val cellStart = match.groupValues[1].toInt()
                val entries = when (value) {
                    is JsonNull -> continue
                    is JsonArray -> value
                    else -> return TimetableParseResult.UpstreamError("节次键 $key 非课程数组")
                }
                if (cellStart !in 1..MAX_PERIOD) {
                    if (entries.isNotEmpty()) {
                        return TimetableParseResult.UpstreamError("节次 $cellStart 超出 1–14 范围")
                    }
                    continue
                }

                for (entryElement in entries) {
                    val entry = entryElement as? JsonObject
                        ?: return TimetableParseResult.UpstreamError("课程条目类型异常")
                    val courseName = optionalText(entry["course_name"])
                        ?: return TimetableParseResult.UpstreamError("课程条目缺少 course_name")
                    val rawPeriods = entry["lessArr"] as? JsonArray
                        ?: return TimetableParseResult.UpstreamError("课程条目缺少 lessArr")
                    if (rawPeriods.isEmpty()) {
                        return TimetableParseResult.UpstreamError("课程条目 lessArr 为空")
                    }

                    val sourcePeriods = rawPeriods.map { element ->
                        integralIntOrNull(element)
                            ?: return TimetableParseResult.UpstreamError("课程 lessArr 包含非整数节次")
                    }
                    if (sourcePeriods.any { it !in 1..MAX_PERIOD }) {
                        return TimetableParseResult.UpstreamError("课程节次超出 1–14 合法范围")
                    }

                    val weekLabel = optionalText(entry["week"])
                        ?: return TimetableParseResult.UpstreamError("课程条目缺少 week 周次标签")
                    val parsedWeeks = parseWeekNumbers(weekLabel, term.totalWeeks)
                        ?: return TimetableParseResult.UpstreamError("课程周次标签无法解析：$weekLabel")
                    if (requestedWeek !in parsedWeeks) {
                        return TimetableParseResult.UpstreamError("课程周次不包含当前请求的第 $requestedWeek 周")
                    }

                    val rawCourseTime = optionalText(entry["course_time"])
                    val distinctSorted = sourcePeriods.distinct().sorted()
                    val groups = consecutiveGroups(distinctSorted)
                    val split = groups.size > 1

                    groups.forEach { (start, end) ->
                        val meeting = CourseMeeting(
                            courseName = courseName,
                            weekday = weekday,
                            startPeriod = start,
                            endPeriod = end,
                            periods = (start..end).toList(),
                            sourcePeriods = sourcePeriods,
                            weeks = parsedWeeks,
                            weekLabel = weekLabel,
                            dateIso = dateIso,
                            timeRangeLabel = TimetableSchedule.resolveTimeRange(start, end, rawCourseTime),
                            teacher = optionalText(entry["teacher"]),
                            location = optionalText(entry["location"]),
                            courseId = optionalText(entry["course_id"]),
                            weekType = optionalText(entry["week_type"]),
                            lessons = optionalText(entry["lessons"]),
                            courseTime = rawCourseTime,
                            sectionsSource = if (split) "lessArr_split_noncontiguous" else "lessArr",
                            credit = optionalText(entry["credit"]),
                            courseType = optionalText(entry["course_type"]),
                            courseHour = optionalText(entry["course_hour"]),
                            examType = optionalText(entry["khfs"]),
                        )
                        allMeetings += meeting
                        dayMeetings += meeting
                    }
                }
            }

            dayMeetings.sortBy { it.startPeriod }
            dayModels += TimetableDay(
                weekday = weekday,
                dateIso = dateIso,
                meetings = dayMeetings,
            )
        }

        val datesIso = dates.map { it.format(DateTimeFormatter.ISO_LOCAL_DATE) }
        return TimetableParseResult.Ok(
            TimetableWeek(
                term = term,
                week = requestedWeek,
                datesIso = datesIso,
                days = dayModels,
                meetings = allMeetings,
                fetchedAtEpochMillis = fetchedAtEpochMillis,
                complete = true,
            ),
        )
    }

    private fun decodeJson(raw: String): JsonElement? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return null
        val text = raw.trimStart('\uFEFF', '\u200B').trim()
        if (text.isEmpty()) return null
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }

    private fun nonJsonFailure(raw: String): TimetableParseResult<Nothing> {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return TimetableParseResult.UpstreamError("课表响应体积超过 2 MiB 上限")
        }
        val text = raw.trimStart('\uFEFF', '\u200B').trim()
        if (text.startsWith("<")) {
            val lower = text.lowercase()
            if (lower.contains("cas/login") || lower.contains("username_password") || lower.contains("execution")) {
                return TimetableParseResult.AuthRequired
            }
            return TimetableParseResult.UpstreamError("课表接口返回了非 JSON 网页内容")
        }
        return TimetableParseResult.UpstreamError("课表响应不是合法 JSON")
    }

    private fun integralIntOrNull(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content.trim()
        if (content.isEmpty()) return null
        // Strictly reject booleans ("true"/"false") and non-integral floats.
        if (content.equals("true", ignoreCase = true) || content.equals("false", ignoreCase = true)) {
            return null
        }
        val number = content.toDoubleOrNull() ?: return null
        if (!number.isFinite() || number % 1.0 != 0.0) return null
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    private fun optionalText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun parseLocalDateOrNull(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (!DATE_REGEX.matches(text)) return null
        val parts = text.split('-')
        return runCatching {
            LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrNull()
    }

    private fun parseConsecutiveWeekDates(element: JsonElement?): List<LocalDate>? {
        val arr = element as? JsonArray ?: return null
        if (arr.size != 7) return null
        val dates = arr.map { item ->
            parseLocalDateOrNull(optionalText(item)) ?: return null
        }
        if (dates.zipWithNext().any { (a, b) -> a.plusDays(1) != b }) return null
        return dates
    }

    private fun normalizeWeekday(element: JsonElement?): Int? {
        return when (val number = integralIntOrNull(element)) {
            0 -> 7
            in 1..7 -> number
            else -> null
        }
    }

    private fun parseWeekNumbers(raw: String, totalWeeks: Int): List<Int>? {
        val tokens = raw.trim()
            .split(WEEK_SEPARATOR_REGEX)
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val result = linkedSetOf<Int>()
        for (original in tokens) {
            val token = original.removeSuffix("周").trim()
            if (token.isEmpty()) return null
            val range = WEEK_RANGE_REGEX.matchEntire(token)
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return null
                val end = range.groupValues[2].toIntOrNull() ?: return null
                if (start > end) return null
                for (w in start..end) {
                    if (w in 1..totalWeeks) result += w
                }
            } else if (WEEK_TOKEN_REGEX.matches(token)) {
                val value = token.toIntOrNull() ?: return null
                if (value in 1..totalWeeks) result += value
            } else {
                return null
            }
        }
        return result.sorted()
    }

    private fun consecutiveGroups(periods: List<Int>): List<Pair<Int, Int>> {
        val ordered = periods.distinct().sorted()
        val groups = mutableListOf<Pair<Int, Int>>()
        for (period in ordered) {
            val last = groups.lastOrNull()
            if (last == null || period != last.second + 1) {
                groups += period to period
            } else {
                groups[groups.lastIndex] = last.first to period
            }
        }
        return groups
    }
}
