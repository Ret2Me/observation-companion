package pl.put.observationcompanion.domain.usecase

class BuildDopplerCurveUseCase {

    private val C_KM_S = 299792.458

    // Delta f = f_nom - f_obs = f_nom * rangeRate / c. Positive when the sat is receding.
    fun calculateOffset(nominalFrequencyHz: Long, rangeRateKmS: Double): Double {
        return nominalFrequencyHz * (rangeRateKmS / C_KM_S)
    }

    fun calculateObservedFrequency(nominalFrequencyHz: Long, rangeRateKmS: Double): Double {
        return nominalFrequencyHz * (1.0 - rangeRateKmS / C_KM_S)
    }
}
