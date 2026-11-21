package me.chius.upcpocket.feature.grades

import kotlin.math.roundToInt

/**
 * UPC weighted-average and 5.0-scale GPA calculator (`gpa = (score - 50) / 10` for >=60, else `0.0`).
 * Supports preset filtering, per-course toggle exclusion, and what-if score simulation.
 */
object GpaCalculator {
    private val REQUIRED_AND_RESTRICTED_ATTRS = setOf("必修", "限选")

    fun scoreToUpcGpa(score: Double): Double {
        if (score < 60.0) return 0.0
        val raw = (score.coerceAtMost(100.0) - 50.0) / 10.0
        return (raw * 100.0).roundToInt() / 100.0
    }

    fun filterByPreset(
        records: List<CourseGradeRecord>,
        preset: GradeFilterPreset,
        selectedTerm: String?,
    ): List<CourseGradeRecord> = records.filter { record ->
        val matchesTerm = selectedTerm.isNullOrBlank() || record.term == selectedTerm
        val matchesPreset = when (preset) {
            GradeFilterPreset.ALL -> true
            GradeFilterPreset.REQUIRED_AND_RESTRICTED -> record.courseAttribute in REQUIRED_AND_RESTRICTED_ATTRS
        }
        matchesTerm && matchesPreset
    }

    fun calculate(
        filteredRecords: List<CourseGradeRecord>,
        excludedIds: Set<String> = emptySet(),
        simulatedScores: Map<String, Double> = emptyMap(),
    ): GpaSummary {
        val baseline = aggregate(filteredRecords, simulatedScores = emptyMap())
        val active = filteredRecords.filterNot { it.id in excludedIds }
        val current = aggregate(active, simulatedScores = simulatedScores)

        return GpaSummary(
            weightedAverage = current.weightedAverage,
            officialGpa = current.officialGpa,
            totalCredits = current.totalCredits,
            courseCount = active.size,
            passedCredits = current.passedCredits,
            baselineWeightedAverage = if (excludedIds.isNotEmpty() || simulatedScores.isNotEmpty()) {
                baseline.weightedAverage
            } else {
                null
            },
            baselineGpa = if (excludedIds.isNotEmpty() || simulatedScores.isNotEmpty()) {
                baseline.officialGpa
            } else {
                null
            },
        )
    }

    private data class Aggregate(
        val weightedAverage: Double?,
        val officialGpa: Double?,
        val totalCredits: Double,
        val passedCredits: Double,
    )

    private fun aggregate(
        records: List<CourseGradeRecord>,
        simulatedScores: Map<String, Double>,
    ): Aggregate {
        var weightedCredits = 0.0
        var weightedSum = 0.0
        var gpaCredits = 0.0
        var gpaSum = 0.0
        var totalCredits = 0.0
        var passedCredits = 0.0

        for (record in records) {
            if (record.credit <= 0.0) continue
            totalCredits += record.credit
            val sim = simulatedScores[record.id]
            val effectiveScore = sim ?: record.numericScore
            val effectiveGpa = if (sim != null) scoreToUpcGpa(sim) else record.gpaPoint

            if (effectiveScore != null) {
                weightedCredits += record.credit
                weightedSum += effectiveScore * record.credit
                if (effectiveScore >= 60.0) {
                    passedCredits += record.credit
                }
            }
            gpaCredits += record.credit
            gpaSum += effectiveGpa * record.credit
        }

        return Aggregate(
            weightedAverage = weightedCredits.takeIf { it > 0.0 }?.let { weightedSum / it },
            officialGpa = gpaCredits.takeIf { it > 0.0 }?.let { gpaSum / it },
            totalCredits = totalCredits,
            passedCredits = passedCredits,
        )
    }
}
