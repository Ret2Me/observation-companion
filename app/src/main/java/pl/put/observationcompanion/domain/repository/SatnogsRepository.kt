package pl.put.observationcompanion.domain.repository

import pl.put.observationcompanion.domain.model.Observation
import pl.put.observationcompanion.domain.model.Satellite
import pl.put.observationcompanion.domain.model.Tle
import pl.put.observationcompanion.domain.model.Transmitter
import kotlinx.coroutines.flow.Flow

interface SatnogsRepository {
    fun getSatellitesFlow(): Flow<List<Satellite>>
    fun getTransmittersFlow(): Flow<List<Transmitter>>
    fun getObservationsFlow(satelliteId: String): Flow<List<Observation>>
    fun getTleFlow(noradId: String): Flow<Tle?>

    suspend fun getSatellites(): List<Satellite>
    suspend fun getSatellite(id: String): Satellite?
    suspend fun getTransmitters(): List<Transmitter>
    suspend fun getTransmittersForSatellite(satelliteId: String): List<Transmitter>
    suspend fun getObservations(satelliteId: String, forceRemote: Boolean = false): List<Observation>
    suspend fun getTle(noradId: String): Tle?

    /** Fetches a fresh TLE from Celestrak by NORAD id, saves it locally, returns it (null if not found). */
    suspend fun fetchTleFromCelestrak(noradId: String): Tle?

    suspend fun saveSatellites(satellites: List<Satellite>)
    suspend fun saveTransmitters(transmitters: List<Transmitter>)
    suspend fun saveObservations(observations: List<Observation>)
    suspend fun saveTle(tle: Tle)

    suspend fun syncFromRemote(onProgress: ((String) -> Unit)? = null)
    suspend fun pruneOldObservations(olderThanDays: Int)
}
