package me.chius.upcpocket.feature.shiguang

import java.io.IOException
import java.math.BigDecimal
import me.chius.upcpocket.core.auth.UpcShiguangAuthEndpoints
import me.chius.upcpocket.core.webview.ControlledWebViewContract

enum class ShiguangKind {
    ACTIVITY,
    SOCIAL_PRACTICE,
}

data class ShiguangActivity(
    val id: String,
    val kind: ShiguangKind,
    val title: String,
    val fields: Map<String, String> = emptyMap(),
)

data class ShiguangPage(
    val page: Int,
    val totalPages: Int?,
    val items: List<ShiguangActivity>,
) {
    val nextPage: Int? get() = totalPages?.takeIf { page < it }?.let { page + 1 }
}

data class ShiguangDetail(
    val title: String,
    val description: String?,
    val fields: Map<String, String>,
)

data class ShiguangRank(
    val position: Int,
    val total: Int,
)

data class ShiguangModule(
    val id: Int,
    val label: String,
    val hours: BigDecimal?,
    val credits: BigDecimal?,
    val rank: ShiguangRank?,
)

data class ShiguangSummary(
    val modules: List<ShiguangModule>,
) {
    val totalHours: BigDecimal?
        get() = if (modules.size == 5 && modules.all { it.hours != null }) {
            modules.fold(BigDecimal.ZERO) { acc, m -> acc + m.hours!! }
        } else {
            null
        }

    val totalCredits: BigDecimal?
        get() = if (modules.size == 5 && modules.all { it.credits != null }) {
            modules.fold(BigDecimal.ZERO) { acc, m -> acc + m.credits!! }
        } else {
            null
        }
}

enum class ShiguangTab(val label: String) {
    ACTIVITIES("活动广场"),
    MINE("我的成绩单"),
}

enum class ShiguangConnectionStatus {
    UNCONFIGURED,
    RECOVERY_REQUIRED,
    IDLE,
    AUTHENTICATING,
    READY,
    INTERACTION_REQUIRED,
    INVALID_CREDENTIALS,
    ERROR,
}

data class ShiguangInteractionRequest(
    val sessionContext: ControlledWebViewContract.SessionContext,
    val scopeHosts: Set<String> = ControlledWebViewContract.shiguangScopeHosts,
    val targetHost: String = UpcShiguangAuthEndpoints.HOST,
    val completionPathPrefixes: Set<String> = setOf("/actvMng/", "/reportCardMng/", "/sysmng/"),
    val reason: String,
)

data class ShiguangUiState(
    val connectionStatus: ShiguangConnectionStatus = ShiguangConnectionStatus.UNCONFIGURED,
    val activeTab: ShiguangTab = ShiguangTab.ACTIVITIES,
    val keyword: String = "",
    val isLoading: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val identityVerified: Boolean = false,
    val activitiesPage: ShiguangPage? = null,
    val participationsPage: ShiguangPage? = null,
    val summary: ShiguangSummary? = null,
    val selectedActivity: ShiguangActivity? = null,
    val selectedDetail: ShiguangDetail? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val interactionRequest: ShiguangInteractionRequest? = null,
)

class ShiguangException(
    message: String,
    val authRequired: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)
