package me.chius.upcpocket.feature.classroom

import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object FreeClassroomProtocol {
    const val ENDPOINT = "https://app.upc.edu.cn/freeclass/wap/default/search-all"
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    fun formBody(query: FreeClassroomQuery): String {
        query.validate()
        val fields = listOf(
            "xq" to query.campus.apiLabel,
            "date" to query.date.toString(),
            "lh" to query.building,
        ) + query.periods.sorted().map { "jc[]" to it.toString() }
        return fields.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    fun parse(
        body: String,
        query: FreeClassroomQuery,
        fetchedAtEpochMillis: Long,
    ): FreeClassroomResult {
        try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: invalid()
            if ((root["e"] as? JsonPrimitive)?.intOrNull != 0) {
                throw IOException("学校未返回有效查询结果，请稍后重试")
            }
            val data = root["d"] as? JsonObject ?: invalid()
            val all = groupedRooms(data["all"])
            // `d.js` is the intersection of classrooms free across all selected periods.
            val free = groupedRooms(data["js"])
            if (free.keys.any { it !in all.keys }) invalid()

            val choices = data["listLh"] as? JsonArray ?: invalid()
            val buildings = choices.map { choice ->
                val item = choice as? JsonObject ?: invalid()
                requiredText(item, "id")
            }.distinct().let { if ("全部" in it) it else listOf("全部") + it }

            val freeCodes = free.values.flatten().map { requiredText(it, "code") }.toSet()
            val rooms = all.flatMap { (groupName, groupRooms) ->
                groupRooms.map { item ->
                    val code = requiredText(item, "code")
                    val campus = requiredText(item, "xqm")
                    val building = requiredText(item, "jxlm")
                    if (campus != query.campus.apiLabel ||
                        building != groupName ||
                        (query.building != "全部" && building != query.building)
                    ) {
                        invalid()
                    }
                    FreeClassroomRoom(
                        code = code,
                        name = requiredText(item, "name"),
                        building = building,
                        campus = campus,
                        capacity = (item["num"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                            ?.takeIf { it > 0 },
                        available = code in freeCodes,
                    )
                }
            }
            val codes = rooms.map { it.code }.toSet()
            if (rooms.size != codes.size || !codes.containsAll(freeCodes)) invalid()

            return FreeClassroomResult(
                query = query,
                buildings = buildings,
                rooms = rooms,
                fetchedAtEpochMillis = fetchedAtEpochMillis,
            )
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            throw IOException("学校返回格式已变化，请稍后重试")
        }
    }

    private fun groupedRooms(value: JsonElement?): Map<String, List<JsonObject>> = when (value) {
        is JsonObject -> value.mapValues { (_, group) ->
            val entries = group as? JsonArray ?: invalid()
            entries.map { it as? JsonObject ?: invalid() }
        }
        // PHP empty associative array may serialize as [].
        is JsonArray -> if (value.isEmpty()) emptyMap() else invalid()
        else -> invalid()
    }

    private fun requiredText(item: JsonObject, key: String): String =
        (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: invalid()

    private fun invalid(): Nothing = throw IOException("学校返回格式已变化，请稍后重试")
}
