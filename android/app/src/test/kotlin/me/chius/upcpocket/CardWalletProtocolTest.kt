package me.chius.upcpocket

import me.chius.upcpocket.core.update.AppUpdateChecker
import me.chius.upcpocket.core.update.AppUpdateState
import me.chius.upcpocket.feature.card.CardHistoryRow
import me.chius.upcpocket.feature.card.CardRechargeSigner
import me.chius.upcpocket.feature.card.CardWalletException
import me.chius.upcpocket.feature.card.CardWalletProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWalletProtocolTest {
    @Test
    fun `parses turnover history, period stats and daily chart points`() {
        val pageJson = """
            {
              "code": 200,
              "data": {
                "total": 3,
                "pages": 1,
                "records": [
                  {"orderId": "o1", "typeFrom": "2", "tranamt": 1250, "resume": "荟萃餐厅", "jndatetimeStr": "2026-11-21 12:10:00"},
                  {"orderId": "o2", "typeFrom": "2", "tranamt": 350, "resume": "超市", "jndatetimeStr": "2026-11-21 14:00:00"},
                  {"orderId": "o3", "typeFrom": "1", "tranamt": 5000, "resume": "微信充值", "jndatetimeStr": "2026-11-20 09:00:00"}
                ]
              }
            }
        """.trimIndent()

        val page = CardWalletProtocol.parseTurnoverPage(pageJson)
        assertEquals(3, page.total)
        assertEquals(-1250L, page.rows[0].amountCents)
        assertEquals(5000L, page.rows[2].amountCents)
        assertEquals("-¥12.50", page.rows[0].formattedSignedAmount)

        val stats = CardWalletProtocol.parsePeriodStats("""{"code":200,"data":{"income":5000,"expenses":1600}}""")
        assertEquals(5000L, stats.incomeCents)
        assertEquals(1600L, stats.expenseCents)

        val daily = CardWalletProtocol.buildDailySpendPoints(page.rows)
        assertEquals(1, daily.size)
        assertEquals("11-21", daily.single().dayLabel)
        assertEquals(1600L, daily.single().expenseCents)
    }

    @Test
    fun `validates recharge rules, signer extraction, deep link checks and app update semver`() {
        val signer = CardWalletProtocol.parseSigner(
            """confirm(){var x={appid:"10086"};Object(o["a"])(e,"10086","0123456789ABCDEF0123456789ABCDEF","SHA256");/charge/order/thirdOrder}""",
        )
        val signed = signer.sign(mapOf("tranamt" to "5000"), timestamp = "20261121120000", nonce = "abc12345")
        assertEquals(64, signed["SIGN"]?.length)

        val rulesJson = CardWalletProtocol.parseJsonObject(
            """{"retain_money":"10","maxmoney":"500","flag":"00000002","layout":"10,20,50,100"}""",
        )
        val rules = CardWalletProtocol.parseRechargeRules(rulesJson)
        assertEquals("50", CardWalletProtocol.validateAmount("50.00", rules))
        val rejected = runCatching { CardWalletProtocol.validateAmount("50.5", rules) }.exceptionOrNull()
        assertTrue(rejected is CardWalletException)

        assertEquals(
            "weixin://wap/pay?prepayid=123",
            CardWalletProtocol.validateWeChatUri("weixin://wap/pay?prepayid=123"),
        )
        assertTrue(AppUpdateChecker.isNewerVersion("0.3.1", "0.3.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("0.3.0", "0.3.0"))

        val checker = AppUpdateChecker(currentVersion = "0.3.0")
        val releaseState = checker.parseLatestRelease(
            """[{"tag_name":"v0.4.0","draft":false,"html_url":"https://github.com/chius-me/upc-pocket/releases/tag/v0.4.0","body":"V2 fix","assets":[{"name":"app-release.apk","browser_download_url":"https://github.com/chius-me/upc-pocket/releases/download/v0.4.0/app-release.apk"}]}]""",
            installedVersion = "0.3.0",
        )
        assertTrue(releaseState is AppUpdateState.UpdateAvailable)
    }
}
