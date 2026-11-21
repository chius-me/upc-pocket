package me.chius.upcpocket.feature.card

import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.chius.upcpocket.core.model.StudentIdValidator

class CardBalanceException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object CardBalanceProtocol {
    const val ENDPOINT = "http://jhzf.upc.edu.cn:20081/wechat/callinterface/getCardInfo.html"
    const val MAX_RESPONSE_BYTES = 256 * 1024

    fun formBody(studentId: String): String {
        require(StudentIdValidator.isValid(studentId)) { "学号格式无效" }
        val encoded = URLEncoder.encode(studentId, "UTF-8")
        return "sno=$encoded&xxbh=synjones&idtype=sno&id=$encoded"
    }

    fun parse(
        body: String,
        expectedStudentId: String,
        accountId: String,
        credentialGeneration: Long,
        fetchedAtEpochMillis: Long,
    ): CardBalanceSnapshot {
        require(StudentIdValidator.isValid(expectedStudentId)) { "学号格式无效" }
        try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: error("root")
            val retcode = (root["retcode"] as? JsonPrimitive)?.content
            require(retcode == "0") { "retcode" }
            val cards = root["card"] as? JsonArray ?: error("card")
            require(cards.size == 1) { "card_size" }
            val card = cards.single() as? JsonObject ?: error("card_item")
            val returnedSno = (card["sno"] as? JsonPrimitive)?.content
            require(returnedSno == expectedStudentId) { "sno_mismatch" }

            fun parseCents(key: String): Long {
                val raw = (card[key] as? JsonPrimitive)?.content ?: error("missing_$key")
                require(raw.matches(Regex("-?[0-9]+"))) { "bad_$key" }
                return raw.toLong()
            }

            return CardBalanceSnapshot(
                accountId = accountId,
                studentId = expectedStudentId,
                credentialGeneration = credentialGeneration,
                balanceCents = parseCents("db_balance"),
                transitionCents = parseCents("unsettle_amount"),
                fetchedAtEpochMillis = fetchedAtEpochMillis,
            )
        } catch (_: Exception) {
            throw CardBalanceException("学校未返回有效的本人校园卡余额（状态、学号或金额校验失败）")
        }
    }
}
