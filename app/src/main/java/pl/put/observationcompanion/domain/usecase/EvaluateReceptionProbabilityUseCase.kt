package pl.put.observationcompanion.domain.usecase

import pl.put.observationcompanion.domain.model.Observation
import pl.put.observationcompanion.domain.model.SatelliteStatus
import kotlin.math.PI
import kotlin.math.sin

// Probability of a successful reception in [0,1] = 0.55 * sin(maxEl) + 0.45 * (good/total).
// Fewer than 3 observations -> historyScore = 0.5 (fallback).
class EvaluateReceptionProbabilityUseCase {

    data class Result(
        val probability: Double,
        val goodCount: Int,
        val totalCount: Int,
        val status: SatelliteStatus
    )

    fun execute(maxElevationDeg: Double, observations: List<Observation>): Result {
        val good = observations.count { it.status.equals("good", ignoreCase = true) }
        val failed = observations.count { it.status.equals("failed", ignoreCase = true) }
        val total = good + failed

        val elevationScore = sin(maxElevationDeg.coerceIn(0.0, 90.0) * PI / 180.0)
        val historyScore = if (total >= MIN_HISTORY) good.toDouble() / total else NEUTRAL_FALLBACK

        val combined = (ELEVATION_WEIGHT * elevationScore + HISTORY_WEIGHT * historyScore)
            .coerceIn(0.0, 1.0)

        return Result(
            probability = combined,
            goodCount = good,
            totalCount = total,
            status = classify(combined, total)
        )
    }

    companion object {
        private const val MIN_HISTORY = 3
        private const val NEUTRAL_FALLBACK = 0.5
        private const val ELEVATION_WEIGHT = 0.55
        private const val HISTORY_WEIGHT = 0.45

        private const val PROMISING_THRESHOLD = 0.60
        private const val UNLIKELY_THRESHOLD = 0.30

        fun classify(probability: Double, totalCount: Int): SatelliteStatus {
            if (totalCount < MIN_HISTORY) return SatelliteStatus.NO_DATA
            return when {
                probability >= PROMISING_THRESHOLD -> SatelliteStatus.PROMISING
                probability < UNLIKELY_THRESHOLD -> SatelliteStatus.UNLIKELY
                else -> SatelliteStatus.NEUTRAL
            }
        }
    }
}
