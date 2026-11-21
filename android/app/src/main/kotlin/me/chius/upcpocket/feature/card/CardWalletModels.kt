package me.chius.upcpocket.feature.card

import java.math.BigDecimal

data class CardHistoryRow(
    val id: String,
    val title: String,
    val time: String,
    val amountCents: Long,
) {
    val isIncome: Boolean get() = amountCents > 0L
    val formattedSignedAmount: String
        get() {
            val prefix = if (amountCents >= 0L) "+" else "-"
            val absVal = kotlin.math.abs(amountCents)
            return "%s¥%d.%02d".format(prefix, absVal / 100L, absVal % 100L)
        }
}

data class CardHistoryPage(
    val rows: List<CardHistoryRow>,
    val total: Int,
    val pages: Int,
)

data class CardPeriodStats(
    val incomeCents: Long,
    val expenseCents: Long,
)

data class DailySpendPoint(
    val dayLabel: String,
    val expenseCents: Long,
)

data class RechargeRules(
    val minimum: BigDecimal,
    val maximum: BigDecimal,
    val presets: List<BigDecimal>,
    val presetOnly: Boolean,
    val integerOnly: Boolean,
)

enum class RechargeOrderStatus {
    SUCCEEDED,
    FAILED,
    EXPIRED,
    UNKNOWN,
}

data class RechargePendingRecord(
    val accountId: String,
    val studentId: String,
    val generation: Long,
    val attemptId: String,
    val yuan: String,
    val orderId: String? = null,
    val payId: String? = null,
)

data class CardWalletUiState(
    val rows: List<CardHistoryRow> = emptyList(),
    val loadedPage: Int = 0,
    val totalPages: Int = 0,
    val totalRows: Int = 0,
    val selectedMonth: String? = null, // yyyy-MM or null for recent/all
    val periodStats: CardPeriodStats? = null,
    val dailySpend: List<DailySpendPoint> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val historyError: String? = null,
    val interactionUrl: String? = null,
    val interactionReason: String? = null,
    val rechargeRules: RechargeRules? = null,
    val isPreparingRecharge: Boolean = false,
    val isSubmittingRecharge: Boolean = false,
    val pendingOrder: RechargePendingRecord? = null,
    val lastRechargeStatus: RechargeOrderStatus? = null,
    val rechargeMessage: String? = null,
    val isWeChatAvailable: Boolean = false,
) {
    val canLoadMore: Boolean get() = loadedPage in 1 until totalPages
}
