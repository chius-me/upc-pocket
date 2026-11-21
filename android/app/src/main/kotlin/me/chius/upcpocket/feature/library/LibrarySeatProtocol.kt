package me.chius.upcpocket.feature.library

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.chius.upcpocket.core.auth.UpcLibraryAuthenticator
import me.chius.upcpocket.core.auth.UpcLibrarySigning

/**
 * Strict read-only protocol parsers for `libspace.upc.edu.cn` (`libseat`).
 * Booking, cancelling, and check-in endpoints are intentionally absent.
 */
object LibrarySeatProtocol {
    const val BASE_URL = "https://libspace.upc.edu.cn"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun signedHeaders(
        token: String?,
        method: String,
        uuid: String,
        timestamp: Long,
        hmacKey: ByteArray = UpcLibrarySigning.configKey(),
    ): Map<String, String> = UpcLibrarySigning.signedHeaders(token, method, uuid, timestamp, hmacKey)

    fun formEncode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())}"
    }

    fun queryEncode(fields: Map<String, String>): String = formEncode(fields)

    fun extractTokenFromUrl(rawUrl: String): String? = UpcLibraryAuthenticator.tokenFromUrl(rawUrl)

    fun parseToken(body: String): String {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("登录响应缺少 data")
        return string(data, "token").takeIf { it.isNotBlank() } ?: fail("登录响应缺少 token")
    }

    fun parseUser(body: String): LibraryUser {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("用户信息缺少 data")
        return LibraryUser(
            id = string(data, "id"),
            username = string(data, "username"),
            name = string(data, "name").ifBlank { string(data, "username") },
        )
    }

    fun parseFilters(body: String): LibraryFilters {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("筛选条件缺少 data")
        val dates = (data["dates"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: fail("学校未返回可预约日期")
        val buildings = (data["buildings"] as? JsonArray)?.map { item ->
            when (item) {
                is JsonArray -> {
                    val id = (item.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val name = (item.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (id.isBlank() || name.isBlank()) fail("馆区字段不完整")
                    LibraryBuilding(id, name)
                }
                is JsonObject -> LibraryBuilding(string(item, "id"), string(item, "name"))
                else -> fail("馆区字段格式异常")
            }
        } ?: fail("学校未返回馆区")
        if (dates.isEmpty()) fail("学校未返回可预约日期")
        if (buildings.isEmpty()) fail("学校未返回馆区")
        return LibraryFilters(dates, buildings)
    }

    fun parseFloors(body: String): List<LibraryFloor> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonArray ?: fail("楼层响应缺少 data")
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("楼层字段格式异常")
            LibraryFloor(int(obj, "floorNum"), string(obj, "floorName"))
        }
    }

    fun parseRooms(body: String, building: LibraryBuilding, floorNamesByNum: Map<Int, String> = emptyMap()): List<LibraryRoom> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonArray ?: fail("房间响应缺少 data")
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("房间字段格式异常")
            val floor = int(obj, "floor")
            LibraryRoom(
                roomId = string(obj, "roomId", "id"),
                name = string(obj, "room", "roomName", "name"),
                floor = floor,
                floorName = string(obj, "floorName").ifBlank { floorNamesByNum[floor] },
                totalSeats = intOrNull(obj, "totalSeats"),
                freeSeats = intOrNull(obj, "free"),
                reserved = intOrNull(obj, "reserved"),
                inUse = intOrNull(obj, "inUse"),
                away = intOrNull(obj, "away"),
                disabled = intOrNull(obj, "disableSeat"),
                maxHour = intOrNull(obj, "maxHour"),
            )
        }.filter { it.roomId.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.roomId }
    }

    fun parseLayout(body: String): LibrarySeatLayout {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("座位布局缺少 data")
        val rows = int(data, "rows")
        val cols = int(data, "cols")
        if (rows <= 0 || cols <= 0 || rows > 200 || cols > 200) fail("座位布局尺寸异常")
        val layout = data["layout"] as? JsonObject ?: fail("座位布局缺少 layout")
        val seats = layout.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val numericKey = key.toIntOrNull()
            val row = obj["row"]?.let { intFromPrimitive(it) }
                ?: numericKey?.div(1000)
                ?: key.substringBefore('_').toIntOrNull()
                ?: return@mapNotNull null
            val col = obj["col"]?.let { intFromPrimitive(it) }
                ?: numericKey?.rem(1000)
                ?: key.substringAfter('_', "").toIntOrNull()
                ?: return@mapNotNull null
            LibrarySeat(
                id = string(obj, "id"),
                name = string(obj, "name"),
                row = row,
                col = col,
                type = string(obj, "type").ifBlank { "seat" },
                enabled = boolean(obj, "enabled"),
                status = string(obj, "status").ifBlank { "UNKNOWN" },
                localOnly = boolean(obj, "local"),
            )
        }.filter { it.id.isNotBlank() }
        if (seats.isEmpty()) fail("学校未返回座位布局")
        return LibrarySeatLayout(rows, cols, seats)
    }

    fun parseCurrentReservations(body: String): List<LibraryReservation> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        return when (val data = root["data"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> parseReservationArray(data)
            else -> fail("当前预约格式异常：data 既不是数组也不是 null")
        }
    }

    fun parseHistoryReservations(body: String): List<LibraryReservation> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("历史预约缺少 data 对象")
        val arr = data["reservations"] as? JsonArray ?: fail("历史预约缺少 reservations 数组")
        return parseReservationArray(arr)
    }

    private fun parseReservationArray(arr: JsonArray): List<LibraryReservation> = arr.map { item ->
        val obj = item as? JsonObject ?: fail("预约记录不是对象")
        val id = string(obj, "id").takeIf { it.isNotBlank() } ?: fail("预约记录缺少 id")
        val date = string(obj, "date", "onDate").takeIf { it.isNotBlank() } ?: fail("预约记录 $id 缺少日期")
        val begin = string(obj, "begin").takeIf { it.isNotBlank() } ?: fail("预约记录 $id 缺少开始时间")
        val end = string(obj, "end").takeIf { it.isNotBlank() } ?: fail("预约记录 $id 缺少结束时间")
        val location = string(obj, "location", "loc").takeIf { it.isNotBlank() } ?: fail("预约记录 $id 缺少座位标识")
        LibraryReservation(
            id = id,
            date = date,
            begin = begin,
            end = end,
            location = location,
            status = string(obj, "stat", "status"),
        )
    }

    internal fun objectOf(body: String): JsonObject {
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: fail("学校返回格式已变化，请稍后重试")
    }

    internal fun ensureBusinessSuccess(root: JsonObject) {
        val code = (root["code"] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        }
        if (code == 12) throw LibrarySessionExpiredException()
        if (code == 20) {
            val raw = (root["message"] as? JsonPrimitive)?.contentOrNull
            throw LibrarySeatException(
                (raw ?: "学校拒绝了这次请求") +
                    "（本地判断：这是请求签名或手机时间不匹配，请先校准设备时间后重试）",
            )
        }
        val statusPrimitive = root["status"] as? JsonPrimitive
        val statusBoolean = statusPrimitive?.booleanOrNull
        val statusText = statusPrimitive?.contentOrNull?.lowercase(Locale.ROOT)
        if (statusBoolean == false || statusText == "fail" || statusText == "error") {
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "学校返回操作失败"
            throw LibrarySeatException(message)
        }
        if (statusBoolean == null && statusText == null) {
            if (code != null && code != 0 && code != 200) {
                val message = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "学校返回错误（code=$code）"
                throw LibrarySeatException(message)
            }
            if (root["success"] == null && root["data"] == null && root["status"] == null) {
                fail("学校返回格式已变化，请稍后重试")
            }
        }
    }

    private fun string(obj: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> primitiveText(obj[key]) }.orEmpty()

    private fun primitiveText(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> if (value is JsonNull) null else value.contentOrNull
        else -> null
    }

    private fun int(obj: JsonObject, vararg keys: String): Int =
        intOrNull(obj, *keys) ?: fail("缺少数字字段 ${keys.first()}")

    private fun intOrNull(obj: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> intFromPrimitive(obj[key]) }

    private fun intFromPrimitive(el: JsonElement?): Int? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.booleanOrNull != null) return null
        return prim.intOrNull
    }

    private fun boolean(obj: JsonObject, key: String): Boolean {
        val raw = (obj[key] as? JsonPrimitive)?.contentOrNull ?: return false
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    private fun fail(message: String): Nothing = throw LibrarySeatException(message)
}
