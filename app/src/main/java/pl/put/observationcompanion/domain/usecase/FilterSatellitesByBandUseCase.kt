package pl.put.observationcompanion.domain.usecase

import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.Satellite
import pl.put.observationcompanion.domain.model.Transmitter

class FilterSatellitesByBandUseCase {

    data class MatchedSatellite(
        val satellite: Satellite,
        val transmitter: Transmitter
    )

    fun execute(
        satellites: List<Satellite>,
        transmitters: List<Transmitter>,
        bands: Set<AntennaBand>
    ): List<MatchedSatellite> {
        val activeTransmitters = transmitters.filter { it.isActive }
        return satellites.mapNotNull { sat ->
            // Find an active transmitter for this satellite that lies inside any of the active antenna band ranges
            val matchedTransmitter = activeTransmitters
                .filter { it.satelliteId == sat.id }
                .firstOrNull { tx -> bands.any { band -> tx.frequency in band.frequencyRange } }
            
            if (matchedTransmitter != null) {
                MatchedSatellite(sat, matchedTransmitter)
            } else {
                null
            }
        }
    }
}
