package me.chius.upcpocket.feature.card

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class CardWalletException(message: String, val code: String = "card_wallet") : IOException(message)

internal class CardRechargeSigner(
    val appId: String,
    private val material: String,
) {
    override fun toString(): String = "CardRechargeSigner([REDACTED])"

    fun sign(fields: Map<String, String>, timestamp: String, nonce: String): Map<String, String> {
        require(timestamp.matches(Regex("[0-9]{14}")) && nonce.matches(Regex("[a-z0-9]{8,64}")))
        val all = fields + mapOf(
            "APP_ID" to appId,
            "TIMESTAMP" to timestamp,
            "SIGN_TYPE" to "SHA256",
            "NONCE" to nonce,
        )
        val raw = all.filterKeys { it != "SIGN" && it != "SECRET_KEY" }
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .entries
            .joinToString("&", postfix = "&SECRET_KEY=$material") { "${it.key}=${it.value}" }
        val sign = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        return all + ("SIGN" to sign)
    }
}

internal data class CardRechargePrepared(
    val feeItem: String,
    val yktAccount: String,
    val signer: CardRechargeSigner,
    val rules: RechargeRules,
) {
    override fun toString(): String = "CardRechargePrepared([REDACTED])"
}

object CardWalletProtocol {
    const val BASE = "https://mb-xyk.upc.edu.cn"
    const val SIGNER_SOURCE = "$BASE/campus-card/js/chunk-1186b329.e153ac09.js"
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun fen(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val text = primitive.content.trim()
        val whole = text.substringBefore('.')
        val fraction = text.substringAfter('.', "")
        if (whole.isEmpty() || fraction.any { it != '0' }) return null
        return whole.toLongOrNull()
    }

    fun parseEnvelopeData(body: String): JsonObject {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw CardWalletException("校园卡接口返回非 JSON 数据", "N-FORMAT")
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
        if (code in setOf(401, 403)) throw CardWalletException("校园卡登录已过期", "P-LOGIN")
        if (code != 200) throw CardWalletException("校园卡接口未返回成功状态（$code）", "N-RESULT")
        return root["data"] as? JsonObject ?: throw CardWalletException("校园卡接口缺少 data 字段", "N-DATA")
    }

    fun parseTurnoverPage(body: String): CardHistoryPage {
        val info = parseEnvelopeData(body)
        val records = info["records"] as? JsonArray ?: throw CardWalletException("缺少流水记录列表", "N-ROWS")
        val total = (info["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: throw CardWalletException("流水分页总数异常", "N-PAGE")
        val pages = (info["pages"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: throw CardWalletException("流水分页数异常", "N-PAGE")

        val rows = records.map { item ->
            val obj = item as? JsonObject ?: throw CardWalletException("流水条目格式异常", "N-ROW")
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val amount = fen(obj["tranamt"])?.takeIf { it >= 0 }
                ?: throw CardWalletException("交易金额格式异常", "N-MONEY")
            val signed = when (str("typeFrom")) {
                "1" -> amount
                "2" -> -amount
                else -> throw CardWalletException("交易收支方向未知", "N-TYPE")
            }
            val id = str("orderId").takeIf { it.isNotBlank() }
                ?: throw CardWalletException("交易编号缺失", "N-ORDER")
            val time = str("jndatetimeStr").ifBlank { str("effectdateStr") }
            if (time.isBlank()) throw CardWalletException("交易时间缺失", "N-TIME")
            CardHistoryRow(
                id = id,
                title = str("resume").ifBlank { "校园卡交易" },
                time = time,
                amountCents = signed,
            )
        }
        return CardHistoryPage(rows = rows, total = total, pages = pages)
    }

    fun parsePeriodStats(body: String): CardPeriodStats {
        val data = parseEnvelopeData(body)
        val income = fen(data["income"]) ?: 0L
        val expenses = fen(data["expenses"]) ?: 0L
        return CardPeriodStats(incomeCents = income, expenseCents = expenses)
    }

    fun buildDailySpendPoints(rows: List<CardHistoryRow>, maxDays: Int = 7): List<DailySpendPoint> {
        val byDay = linkedMapOf<String, Long>()
        rows.forEach { row ->
            if (row.amountCents >= 0L) return@forEach
            val datePart = row.time.trim().take(10)
            if (datePart.length == 10 && datePart[4] == '-' && datePart[7] == '-') {
                val shortLabel = datePart.substring(5) // MM-dd
                byDay[shortLabel] = (byDay[shortLabel] ?: 0L) + (-row.amountCents)
            }
        }
        return byDay.entries.toList()
            .take(maxDays)
            .reversed()
            .map { (day, cents) -> DailySpendPoint(dayLabel = day, expenseCents = cents) }
    }

    fun validateAmount(value: String, rules: RechargeRules): String {
        val normalized = normalizeYuan(value)
        val decimal = BigDecimal(normalized)
        if (decimal < rules.minimum || decimal > rules.maximum ||
            (rules.integerOnly && decimal.stripTrailingZeros().scale() > 0) ||
            (rules.presetOnly && rules.presets.none { it.compareTo(decimal) == 0 })
        ) {
            throw CardWalletException("充值金额不符合学校规则", "P-AMOUNT")
        }
        return normalized
    }

    fun normalizeYuan(value: String): String {
        if (!value.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,2})?"))) {
            throw CardWalletException("请输入合法人民币金额（最多两位小数）", "P-AMOUNT")
        }
        val decimal = BigDecimal(value)
        if (decimal <= BigDecimal.ZERO) {
            throw CardWalletException("充值金额必须大于 0", "P-AMOUNT")
        }
        return decimal.stripTrailingZeros().toPlainString()
    }

    fun parseRechargeRules(item: JsonObject, front: JsonObject = JsonObject(emptyMap())): RechargeRules {
        fun configured(value: JsonElement?): BigDecimal? {
            if (value == null || value === JsonNull) return null
            val primitive = value as? JsonPrimitive ?: throw CardWalletException("充值规则配置格式异常", "P-FEE-CONFIG")
            val raw = primitive.content
            if (primitive.isString && raw.isEmpty()) return null
            if (!raw.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,32})?"))) {
                throw CardWalletException("充值规则限额格式异常", "P-FEE-CONFIG")
            }
            val number = BigDecimal(raw).stripTrailingZeros()
            if (number.scale() > 2) throw CardWalletException("充值规则精度异常", "P-FEE-CONFIG")
            return number
        }
        val minimum = configured(item["retain_money"])?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("0.01")
        val frontMaximum = configured(front["rechargeMaxAmount"])?.setScale(0, RoundingMode.DOWN)
        val feeValue = item["maxmoney"]
        val feeMaximum = configured(feeValue)
        val configuredMaximum = if (feeMaximum == null ||
            feeMaximum.compareTo(BigDecimal.ZERO) == 0 && (feeValue as? JsonPrimitive)?.isString != true
        ) frontMaximum else feeMaximum
        val maximum = configuredMaximum?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("9999")
        if (minimum > maximum) throw CardWalletException("充值限额配置矛盾", "P-FEE-CONFIG")

        val flag = field(item, "flag")
        if (flag.length < 8 || flag[7] !in "012") throw CardWalletException("充值输入限制标识异常", "P-FEE-CONFIG")
        val presetOnly = flag[7] == '1'
        val integerOnly = flag[7] == '2'
        val presets = field(item, "layout").split(',')
            .filter { it.isNotBlank() }
            .map { raw ->
                runCatching { BigDecimal(normalizeYuan(raw.trim())) }
                    .getOrElse { throw CardWalletException("预设金额配置异常", "P-FEE-CONFIG") }
            }
            .filter { it >= minimum && it <= maximum && (!integerOnly || it.stripTrailingZeros().scale() <= 0) }
            .distinctBy { it.stripTrailingZeros() }
        if (presets.size > 30 || (presetOnly && presets.isEmpty())) {
            throw CardWalletException("预设金额列表异常", "P-FEE-CONFIG")
        }
        return RechargeRules(
            minimum = minimum,
            maximum = maximum,
            presets = presets,
            presetOnly = presetOnly,
            integerOnly = integerOnly,
        )
    }

    internal fun parseSigner(source: String): CardRechargeSigner {
        val start = source.indexOf("confirm(){")
        val end = source.indexOf("/charge/order/thirdOrder", start.coerceAtLeast(0))
        if (start < 0 || end < start || end - start > 25_000) {
            throw CardWalletException("学校充值签名脚本格式已变化", "P-SIGN-FORMAT")
        }
        val scope = source.substring(start, end)
        val matches = Regex("""Object\(o\["a"\]\)\(e,"([0-9]{3,10})","([A-Za-z0-9]{32})","SHA256"\)""")
            .findAll(scope)
            .toList()
        if (matches.size != 1) throw CardWalletException("未识别到唯一充值签名参数", "P-SIGN-FORMAT")
        val groups = matches.single().groupValues
        if (!scope.contains("appid:\"${groups[1]}\"")) {
            throw CardWalletException("充值签名参数校验不通过", "P-SIGN-FORMAT")
        }
        return CardRechargeSigner(groups[1], groups[2])
    }

    fun field(obj: JsonObject, name: String): String =
        (obj[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun parseJsonObject(body: String): JsonObject =
        runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw CardWalletException("响应不是有效 JSON 对象", "P-JSON") }

    fun upgradeSchoolUrl(from: String, value: String): String {
        val resolved = URI(from).resolve(value)
        val normalized = if (resolved.scheme.equals("http", ignoreCase = true) &&
            resolved.host == "mb-xyk.upc.edu.cn" &&
            resolved.port in setOf(-1, 80) &&
            resolved.rawUserInfo == null
        ) {
            "https://mb-xyk.upc.edu.cn${resolved.rawPath.orEmpty()}" +
                (resolved.rawQuery?.let { "?$it" } ?: "")
        } else {
            resolved.toString()
        }
        val uri = URI(normalized)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host != "mb-xyk.upc.edu.cn" ||
            uri.port !in setOf(-1, 443) ||
            uri.rawUserInfo != null
        ) {
            throw CardWalletException("充值回调地址超出了学校域范围", "P-ORIGIN")
        }
        return normalized
    }

    fun orderFromPaymentUrl(url: String): String? {
        val uri = URI(url)
        if (uri.path !in setOf("/payment/", "/payment", "/payment/index")) return null
        return queryMap(url)["orderid"]?.takeIf { it.matches(Regex("[0-9]{8,40}")) }
    }

    fun literalRedirect(html: String): String? {
        val script = Regex("(?is)<script\\b[^>]*>(.*?)</script>").findAll(html).joinToString("\n") { it.groupValues[1] }
        return Regex("""(?:window\.)?location(?:\.href)?\s*=\s*(["'])([^'"\r\n]+)\1""").find(script)?.groupValues?.get(2)
            ?: Regex("""(?:window\.)?location\.(?:replace|assign)\(\s*(["'])([^'"\r\n]+)\1\s*\)""").find(script)?.groupValues?.get(2)
    }

    fun parseCashierForm(html: String): String {
        val forms = Regex("(?is)<form\\b[^>]*>.*?</form>").findAll(html).toList()
        if (forms.size != 1) throw CardWalletException("微信收银台表单数量异常", "P-WX-FORM")
        fun attr(tag: String, key: String) =
            Regex("(?i)\\b$key\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)?.replace("&amp;", "&")
        val form = forms.single().value
        if (!attr(form.substringBefore('>'), "method").orEmpty().equals("POST", true)) {
            throw CardWalletException("微信收银台方法非 POST", "P-WX-FORM")
        }
        val action = attr(form.substringBefore('>'), "action") ?: throw CardWalletException("微信收银台缺少 action", "P-WX-FORM")
        val uri = URI(action)
        if (uri.scheme != "https" ||
            uri.host != "wx.tenpay.com" ||
            uri.path != "/cgi-bin/mmpayweb-bin/checkmweb" ||
            uri.port !in setOf(-1, 443) ||
            uri.rawUserInfo != null ||
            uri.rawFragment != null
        ) {
            throw CardWalletException("微信支付域名校验未通过", "P-WX-ORIGIN")
        }
        val keys = queryMap(action)
        if (!keys.keys.containsAll(setOf("prepay_id", "sign", "package", "redirect_url")) ||
            keys.values.any { it.length > 8192 }
        ) {
            throw CardWalletException("微信收银台参数不完整", "P-WX-FORM")
        }
        val namedInputs = Regex("(?is)<input\\b[^>]*>").findAll(form).mapNotNull {
            if (!attr(it.value, "type").orEmpty().equals("hidden", true)) {
                throw CardWalletException("收银台包含非隐藏输入字段", "P-WX-FORM")
            }
            attr(it.value, "name")?.takeIf { n -> n.isNotEmpty() }
        }.toList()
        if (namedInputs.isNotEmpty()) throw CardWalletException("收银台包含未知携带字段", "P-WX-FORM")
        return action
    }

    fun extractWeChatDeepLink(body: String): String {
        val match = Regex("""weixin://wap/pay\?[^"'\s<>\\]+""").find(body)?.value
            ?: throw CardWalletException("微信收银台未返回拉起链接", "P-WX-URI")
        return validateWeChatUri(match.replace("&amp;", "&"))
    }

    fun validateWeChatUri(value: String): String {
        val uri = URI(value)
        if (value.length > 16_384 ||
            value.any { it <= ' ' || it == '\u007f' } ||
            uri.scheme != "weixin" ||
            uri.host != "wap" ||
            uri.path != "/pay" ||
            uri.port != -1 ||
            uri.rawUserInfo != null ||
            uri.rawFragment != null ||
            uri.rawQuery.isNullOrBlank()
        ) {
            throw CardWalletException("微信支付链接格式未通过校验", "P-WX-URI")
        }
        return value
    }

    fun queryMap(url: String): Map<String, String> {
        val fields = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        return fields.toMap()
    }
}
