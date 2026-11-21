package me.chius.upcpocket.feature.library

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Protocol parsers and reservation helpers for `office.chaoxing.com` (`seat` and `seatengine`).
 */
object ChaoxingProtocol {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val weekdayKeys = arrayOf("mon", "tues", "wed", "thur", "fri", "sat", "sun")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseIdentity(html: String): ChaoxingUser {
        val raw = extractJsonObject(html, "userLoginInfo")
            ?: throw ChaoxingException("登录页缺少 userLoginInfo", "chaoxing_protocol")
        val root = parseObject(raw) ?: throw ChaoxingException(
            "userLoginInfo 不是 JSON",
            "chaoxing_protocol",
        )
        val user = root["userInfo"] as? JsonObject
            ?: throw ChaoxingException("userLoginInfo 缺少 userInfo", "chaoxing_protocol")
        val sno = stringOrNull(user, "sno")
            ?.takeIf { it.isNotBlank() }
            ?: throw ChaoxingException("userInfo 缺少 sno", "chaoxing_protocol")
        return ChaoxingUser(sno)
    }

    fun parseSeatRooms(body: String, kind: ChaoxingSeatKind, date: String): ChaoxingSeatRoomPage {
        val data = objectData(body)
        val list = data["seatRoomList"] as? JsonArray
            ?: throw ChaoxingException("座位响应缺少 seatRoomList", "chaoxing_protocol")
        val day = parseDate(date)
        val rooms = list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = int(obj, "id") ?: return@mapNotNull null
            val hours = hours(obj["seatSpecialTime"] as? JsonObject, day)
                ?: hours(obj["seatEngineSpecialTime"] as? JsonObject, day)
                ?: return@mapNotNull null
            ChaoxingSeatRoom(
                id = id,
                name = listOfNotNull(
                    stringOrNull(obj, "firstLevelName"),
                    stringOrNull(obj, "secondLevelName"),
                    stringOrNull(obj, "thirdLevelName"),
                ).joinToString("-").ifBlank { "${kind.campusLabel} 区域 $id" },
                floor = stringOrNull(obj, "secondLevelName").orEmpty(),
                capacity = int(obj, "capacity") ?: 0,
                usedCount = int(obj, "curUsedCount"),
                startMinute = hours.first,
                endMinute = hours.second,
            )
        }
        return ChaoxingSeatRoomPage(
            rooms = rooms,
            totalRow = int(data, "totalRow") ?: rooms.size,
            totalPage = int(data, "totalPage") ?: 1,
        )
    }

    fun parseSeatRoomDetail(
        body: String,
        date: String,
        expectedRoomId: Int,
    ): ChaoxingSeatRoomDetail {
        val data = objectData(body)
        val config = data["seatConfig"] as? JsonObject
            ?: throw ChaoxingException("房间详情缺少 seatConfig", "chaoxing_protocol")
        val room = data["seatRoom"] as? JsonObject
            ?: throw ChaoxingException("房间详情缺少 seatRoom", "chaoxing_protocol")
        val timeUnit = int(config, "timeUnit")
            ?.takeIf { it > 0 }
            ?: throw ChaoxingException("房间详情缺少 timeUnit", "chaoxing_protocol")
        val day = parseDate(date)
        val hours = hours(room["seatSpecialTime"] as? JsonObject, day)
            ?: hours(room["seatEngineSpecialTime"] as? JsonObject, day)
            ?: hours(config["commonTimeConfig"] as? JsonObject, day)
            ?: throw ChaoxingException("房间详情缺少开放时段", "chaoxing_protocol")
        val roomId = int(room, "id") ?: throw ChaoxingException("房间详情缺少标识")
        if (roomId != expectedRoomId) {
            throw ChaoxingException("房间详情与请求区域不一致", "chaoxing_protocol")
        }
        return ChaoxingSeatRoomDetail(
            roomId = roomId,
            name = listOfNotNull(
                stringOrNull(room, "firstLevelName"),
                stringOrNull(room, "secondLevelName"),
                stringOrNull(room, "thirdLevelName"),
            ).joinToString("-").ifBlank { "区域 $roomId" },
            capacity = int(room, "capacity") ?: 0,
            timeUnit = timeUnit,
            startMinute = hours.first,
            endMinute = hours.second,
            places = emptyList(),
            disabledSeatNums = (data["seatAttributes"] as? JsonArray).orEmpty().mapNotNull { item ->
                val attribute = item as? JsonObject ?: throw ChaoxingException("座位属性无效")
                val num = stringOrNull(attribute, "seatNum") ?: throw ChaoxingException("属性缺少座号")
                val allowed = int(attribute, "isReserve") ?: throw ChaoxingException("属性缺少可约标记")
                if (allowed !in 0..1) throw ChaoxingException("可约标记无效")
                num.takeIf { allowed == 0 }
            }.toSet(),
            seatRulesKnown = data["seatAttributes"] is JsonArray,
            minDurationMinutes = durationMinutes(config, "minReserveDuration"),
            maxDurationMinutes = durationMinutes(config, "reserveDuration"),
            closed = int(room, "isOpen") == 1,
        )
    }

    fun parseSeatPlaces(body: String): List<ChaoxingSeatPlace> {
        val data = dataArray(body)
        val places = data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val seatNum = stringOrNull(obj, "seatNum") ?: return@mapNotNull null
            val x = float(obj, "xleft") ?: return@mapNotNull null
            val y = float(obj, "ytop") ?: return@mapNotNull null
            val width = float(obj, "width") ?: return@mapNotNull null
            val height = float(obj, "height") ?: return@mapNotNull null
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val rawRotate = float(obj, "rotate") ?: 0f
            val rotate = ((rawRotate % 360f) + 360f) % 360f
            ChaoxingSeatPlace(
                seatNum = seatNum,
                x = x,
                y = y,
                width = width,
                height = height,
                rotate = rotate,
            )
        }
        if (places.size != data.size || places.map { it.seatNum }.toSet().size != places.size) {
            throw ChaoxingException("座位图坐标无效", "chaoxing_protocol")
        }
        return places
    }

    fun parseUsedSeatNums(body: String): Set<String> {
        val data = objectData(body)
        val list = data["seatReserves"] as? JsonArray
            ?: throw ChaoxingException("已用座位响应缺少 seatReserves", "chaoxing_protocol")
        return list.map { item ->
            val obj = item as? JsonObject ?: throw ChaoxingException("占用项无效")
            stringOrNull(obj, "seatNum") ?: throw ChaoxingException("占用项缺少座号")
        }.toSet()
    }

    fun parseUsedTimes(body: String): List<ChaoxingTimeInterval> {
        val data = dataArray(body)
        return data.mapNotNull { item ->
            val pair = item as? JsonArray ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val start = (pair[0] as? JsonPrimitive)?.longOrNull() ?: return@mapNotNull null
            val end = (pair[1] as? JsonPrimitive)?.longOrNull() ?: return@mapNotNull null
            val startMinute = epochMinute(start)
            val endMinute = epochMinute(end)
            ChaoxingTimeInterval(
                startMinute = startMinute,
                endMinute = if (endMinute <= startMinute) endMinute + 24 * 60 else endMinute,
            )
        }
    }

    private fun durationMinutes(config: JsonObject, key: String): Int? {
        val value = (config[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!value.isFinite()) throw ChaoxingException("时长规则无效")
        return (value * 60).toInt().takeIf { it > 0 }
    }

    private fun hours(container: JsonObject?, day: LocalDate): Pair<Int, Int>? {
        container ?: return null
        val key = weekdayKeys[day.dayOfWeek.value - 1]
        val start = parseMinute(stringOrNull(container, "${key}StartTime")) ?: return null
        val end = parseMinute(stringOrNull(container, "${key}EndTime")) ?: return null
        if (end <= start) return null
        return start to end
    }

    private fun parseMinute(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) } ?: return null
        val parts = text.split(':')
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun epochMinute(value: Long): Int {
        val instant = if (value > 1_000_000_000_000L) {
            Instant.ofEpochMilli(value)
        } else {
            Instant.ofEpochSecond(value)
        }
        val time = instant.atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun parseDate(raw: String): LocalDate =
        runCatching { LocalDate.parse(raw) }
            .getOrElse { throw ChaoxingException("日期格式不合法", "chaoxing_protocol") }

    private fun dataArray(body: String): List<JsonElement> =
        (envelope(body)["data"] as? JsonArray)
            ?: throw ChaoxingException("响应缺少 data 数组", "chaoxing_protocol")

    private fun objectData(body: String): JsonObject =
        (envelope(body)["data"] as? JsonObject)
            ?: throw ChaoxingException("响应缺少 data 对象", "chaoxing_protocol")

    private fun envelope(body: String): JsonObject {
        val root = parseObject(body)
            ?: throw ChaoxingException("响应不是 JSON", "chaoxing_protocol")
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw ChaoxingException("学校返回业务失败", "chaoxing_upstream")
        }
        if (root["data"] == null) {
            throw ChaoxingException("响应缺少 data", "chaoxing_protocol")
        }
        return root
    }

    private fun parseObject(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    private fun stringOrNull(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun int(obj: JsonObject?, key: String): Int? {
        val value = obj?.get(key) as? JsonPrimitive ?: return null
        if (value.booleanOrNull != null) return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }

    private fun float(obj: JsonObject?, key: String): Float? {
        val value = obj?.get(key) as? JsonPrimitive ?: return null
        if (value.booleanOrNull != null) return null
        val number = value.doubleOrNull ?: value.contentOrNull?.toDoubleOrNull() ?: return null
        return number.toFloat().takeIf { it.isFinite() }
    }

    private fun JsonPrimitive.longOrNull(): Long? {
        if (booleanOrNull != null) return null
        return contentOrNull?.toLongOrNull()
    }

    private fun extractJsonObject(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private val SUBMIT_ENC_REGEX = Regex(
        """id\s*=\s*["']submit_enc["'][^>]*value\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val VALUE_FIRST_SUBMIT_ENC_REGEX = Regex(
        """value\s*=\s*["']([^"']+)["'][^>]*id\s*=\s*["']submit_enc["']""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val WFW_ENGINE_ENC_REGEX = Regex(
        """wfwEngineEnc\s*=\s*['"]([^'"]*)['"]""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val RISK_CHECK_REGEX = Regex(
        """riskCheckOpen\s*[:=]\s*true""",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun parseSelectPage(html: String): ChaoxingSelectSecrets {
        val submit = SUBMIT_ENC_REGEX.find(html)?.groupValues?.get(1)
            ?: VALUE_FIRST_SUBMIT_ENC_REGEX.find(html)?.groupValues?.get(1)
            ?: throw ChaoxingException("选座页缺少 submit_enc", "chaoxing_protocol")
        if (submit.isBlank() || submit.length > 512) {
            throw ChaoxingException("选座页 submit_enc 不可用", "chaoxing_protocol")
        }
        return ChaoxingSelectSecrets(
            submitEnc = submit,
            wfwEngineEnc = WFW_ENGINE_ENC_REGEX.find(html)?.groupValues?.get(1).orEmpty(),
            riskCheckRequired = RISK_CHECK_REGEX.containsMatchIn(html),
        )
    }

    fun enc(
        kind: ChaoxingSeatKind,
        deptIdEnc: String,
        roomId: Int,
        day: String,
        startTime: String,
        endTime: String,
        seatNum: String,
        submitEnc: String,
        wfwEngineEnc: String,
    ): String {
        val params = orderedParams(
            kind = kind,
            deptIdEnc = deptIdEnc,
            roomId = roomId,
            day = day,
            startTime = startTime,
            endTime = endTime,
            seatNum = seatNum,
            captcha = "",
            wyToken = "",
            wfwEngineEnc = wfwEngineEnc,
        )
        val canonical = buildString {
            params.forEach { (key, value) ->
                append('[').append(key).append('=').append(value).append(']')
            }
            append('[').append(submitEnc).append(']')
        }
        return MessageDigest.getInstance("MD5")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun submitParams(
        kind: ChaoxingSeatKind,
        deptIdEnc: String,
        roomId: Int,
        day: String,
        startTime: String,
        endTime: String,
        seatNum: String,
        submitEnc: String,
        wfwEngineEnc: String,
    ): LinkedHashMap<String, String> {
        val params = orderedParams(
            kind = kind,
            deptIdEnc = deptIdEnc,
            roomId = roomId,
            day = day,
            startTime = startTime,
            endTime = endTime,
            seatNum = seatNum,
            captcha = "",
            wyToken = "",
            wfwEngineEnc = wfwEngineEnc,
        )
        params["enc"] = enc(
            kind = kind,
            deptIdEnc = deptIdEnc,
            roomId = roomId,
            day = day,
            startTime = startTime,
            endTime = endTime,
            seatNum = seatNum,
            submitEnc = submitEnc,
            wfwEngineEnc = wfwEngineEnc,
        )
        return params
    }

    private fun orderedParams(
        kind: ChaoxingSeatKind,
        deptIdEnc: String,
        roomId: Int,
        day: String,
        startTime: String,
        endTime: String,
        seatNum: String,
        captcha: String,
        wyToken: String,
        wfwEngineEnc: String,
    ): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        if (kind == ChaoxingSeatKind.SEAT) put("deptIdEnc", deptIdEnc)
        put("roomId", roomId.toString())
        put("day", day)
        put("startTime", startTime)
        put("endTime", endTime)
        put("seatNum", seatNum)
        put("captcha", captcha)
        put("wyToken", wyToken)
        if (kind == ChaoxingSeatKind.SEATENGINE) put("wfwEngineEnc", wfwEngineEnc)
    }

    fun parseSubmitResponse(body: String, seatNum: String, day: String): ChaoxingReservationResult {
        val root = parseObject(body) ?: return unknownChaoxingSubmission()
        val flag = root["success"] as? JsonPrimitive ?: return unknownChaoxingSubmission()
        if (flag.isString) return unknownChaoxingSubmission()
        val success = flag.booleanOrNull ?: return unknownChaoxingSubmission()
        val message = if ("msg" in root) {
            val raw = root["msg"] as? JsonPrimitive ?: return unknownChaoxingSubmission()
            if (!raw.isString) return unknownChaoxingSubmission()
            raw.contentOrNull?.trim() ?: return unknownChaoxingSubmission()
        } else null
        val data = root["data"]
        if (data != null && data != JsonNull && data !is JsonObject) return unknownChaoxingSubmission()
        if (data is JsonObject && "seatReserve" in data) {
            val reserve = data["seatReserve"] as? JsonObject ?: return unknownChaoxingSubmission()
            for ((key, expected) in listOf("seatNum" to seatNum, "day" to day)) {
                if (key in reserve) {
                    val value = reserve[key] as? JsonPrimitive ?: return unknownChaoxingSubmission()
                    if (!value.isString || value.contentOrNull != expected) return unknownChaoxingSubmission()
                }
            }
        }
        if (success) return ChaoxingReservationResult.Success(seatNum, day)
        if (message.isNullOrBlank()) return unknownChaoxingSubmission()
        return if (message.contains("验证码") ||
            message.contains("安全验证") ||
            message.contains("captcha", ignoreCase = true)
        ) {
            ChaoxingReservationResult.InteractionRequired("学校要求安全验证；请在官方页完成本次预约。")
        } else {
            ChaoxingReservationResult.Rejected(message)
        }
    }

    internal fun unknownChaoxingSubmission() = ChaoxingReservationResult.Unknown(
        "提交结果未知；请到学校记录核对，勿重复提交。",
    )
}
