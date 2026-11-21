package me.chius.upcpocket.feature.grades

import kotlinx.serialization.Serializable

@Serializable
data class CourseGradeRecord(
    val id: String,
    val term: String,
    val courseCode: String,
    val courseName: String,
    val scoreText: String,
    val numericScore: Double?,
    val credit: Double,
    val totalHours: String = "",
    val gpaPoint: Double,
    val retakeTerm: String = "",
    val assessmentMode: String = "",
    val examNature: String = "",
    val courseAttribute: String = "",
    val courseNature: String = "",
)

@Serializable
data class GradesSnapshot(
    val accountId: String,
    val credentialGeneration: Long,
    val records: List<CourseGradeRecord>,
    val fetchedAtEpochMillis: Long,
) {
    val terms: List<String>
        get() = records.map { it.term }.filter { it.isNotBlank() }.distinct().sortedDescending()
}

enum class GradeFilterPreset(val label: String) {
    ALL("全部课程"),
    REQUIRED_AND_RESTRICTED("仅必修 + 限选"),
    ;
}

data class GpaSummary(
    val weightedAverage: Double?,
    val officialGpa: Double?,
    val totalCredits: Double,
    val courseCount: Int,
    val passedCredits: Double,
    val baselineWeightedAverage: Double?,
    val baselineGpa: Double?,
) {
    val weightedDelta: Double?
        get() = if (weightedAverage != null && baselineWeightedAverage != null) {
            weightedAverage - baselineWeightedAverage
        } else {
            null
        }

    val gpaDelta: Double?
        get() = if (officialGpa != null && baselineGpa != null) {
            officialGpa - baselineGpa
        } else {
            null
        }
}

enum class GradesConnectionStatus {
    UNCONFIGURED,
    RECOVERY_REQUIRED,
    IDLE,
    LOADING,
    ONLINE,
    OFFLINE_CACHE,
    INTERACTION_REQUIRED,
    INVALID_CREDENTIALS,
    ERROR,
}

data class GradesUiState(
    val connectionStatus: GradesConnectionStatus = GradesConnectionStatus.UNCONFIGURED,
    val snapshot: GradesSnapshot? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val interactionUrl: String? = null,
    val interactionReason: String? = null,
)
