package me.chius.upcpocket.feature.card

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
data class CardBalanceSnapshot(
    val accountId: String,
    val studentId: String,
    val credentialGeneration: Long,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAtEpochMillis: Long,
) {
    val balanceText: String
        get() = BigDecimal.valueOf(balanceCents, 2).toPlainString()

    val transitionText: String
        get() = BigDecimal.valueOf(transitionCents, 2).toPlainString()

    val totalBalanceText: String
        get() = BigDecimal.valueOf(balanceCents, 2)
            .add(BigDecimal.valueOf(transitionCents, 2))
            .toPlainString()

    fun sameOwner(accountId: String, studentId: String): Boolean =
        this.accountId == accountId && this.studentId == studentId
}

data class CardBalanceUiState(
    val snapshot: CardBalanceSnapshot? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val isHistoricalGeneration: Boolean = false,
)
