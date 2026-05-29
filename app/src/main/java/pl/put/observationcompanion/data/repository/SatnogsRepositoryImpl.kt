package pl.put.observationcompanion.data.repository

import android.util.Log
import pl.put.observationcompanion.data.local.dao.*
import pl.put.observationcompanion.data.local.entity.SatelliteFtsEntity
import pl.put.observationcompanion.data.local.entity.TleEntity
import pl.put.observationcompanion.data.mapper.*
import pl.put.observationcompanion.data.remote.api.CelestrakApi
import pl.put.observationcompanion.data.remote.api.SatnogsDbApi
import pl.put.observationcompanion.data.remote.api.SatnogsNetworkApi
import pl.put.observationcompanion.domain.model.Observation
import pl.put.observationcompanion.domain.model.Satellite
import pl.put.observationcompanion.domain.model.Tle
import pl.put.observationcompanion.domain.model.Transmitter
import pl.put.observationcompanion.domain.repository.SatnogsRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SatnogsRepositoryImpl @Inject constructor(
    private val dbApi: SatnogsDbApi,
    private val networkApi: SatnogsNetworkApi,
    private val celestrakApi: CelestrakApi,
    private val satelliteDao: SatelliteDao,
    private val tleDao: TleDao,
    private val transmitterDao: TransmitterDao,
    private val observationDao: ObservationDao
) : SatnogsRepository {

    private val TAG = "SatnogsRepository"

    override fun getSatellitesFlow(): Flow<List<Satellite>> {
        return satelliteDao.getAllSatellitesFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getTransmittersFlow(): Flow<List<Transmitter>> {
        return transmitterDao.getAllTransmittersFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getObservationsFlow(satelliteId: String): Flow<List<Observation>> {
        return observationDao.getObservationsFlow(satelliteId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getTleFlow(noradId: String): Flow<Tle?> {
        return tleDao.getTleFlow(noradId).map { it?.toDomain() }
    }

    override suspend fun getSatellites(): List<Satellite> {
        return satelliteDao.getAllSatellites().map { it.toDomain() }
    }

    override suspend fun getSatellite(id: String): Satellite? {
        return satelliteDao.getSatelliteById(id)?.toDomain()
    }

    override suspend fun getTransmitters(): List<Transmitter> {
        return transmitterDao.getAllTransmitters().map { it.toDomain() }
    }

    override suspend fun getTransmittersForSatellite(satelliteId: String): List<Transmitter> {
        return transmitterDao.getTransmittersForSatellite(satelliteId).map { it.toDomain() }
    }

    override suspend fun getObservations(satelliteId: String, forceRemote: Boolean): List<Observation> {
        if (forceRemote) {
            // The default /observations/ feed returns scheduled-future obs (status="future",
            // vetted_status="unknown") which are useless. Query good+failed separately and
            // union - that's how we actually get historical successful/failed observations.
            //
            // Per-sat results are cached for OBS_TTL_MS so repeated refreshes within a few
            // hours don't re-fetch. We only mark a satellite as "fetched" when AT LEAST
            // ONE of the two endpoints actually responded successfully - so a 429 doesn't
            // poison the cache and the next refresh will retry that satellite naturally.
            try {
                val sat = satelliteDao.getSatelliteById(satelliteId)
                val noradId = sat?.noradId
                val freshEnough = sat != null && (System.currentTimeMillis() - sat.observationsFetchedAt) < OBS_TTL_MS
                if (!freshEnough && !noradId.isNullOrBlank()) {
                    val (goodResult, failedResult) = coroutineScope {
                        val g = async(Dispatchers.IO) { fetchObsSafely(noradId, "good") }
                        val f = async(Dispatchers.IO) { fetchObsSafely(noradId, "failed") }
                        awaitAll(g, f).let { it[0] to it[1] }
                    }
                    val anySuccess = goodResult.success || failedResult.success
                    val combined = goodResult.obs + failedResult.obs
                    val entities = combined.map { it.toEntity() }
                        .filter { it.id.isNotEmpty() && it.satelliteId.isNotEmpty() }
                    if (entities.isNotEmpty()) observationDao.insertObservations(entities)
                    if (anySuccess) {
                        satelliteDao.touchObservationsFetched(satelliteId, System.currentTimeMillis())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed live fetching observations for $satelliteId, using local cache", e)
            }
        }
        return observationDao.getObservations(satelliteId).map { it.toDomain() }
    }

    private data class ObsFetchResult(
        val obs: List<pl.put.observationcompanion.data.remote.dto.ObservationDto>,
        val success: Boolean
    )

    private suspend fun fetchObsSafely(noradId: String, status: String): ObsFetchResult {
        // SatNOGS Network throttles bursts but successful requests still come through
        // within a few seconds. Empirically: when the API responds 429 quickly (<200ms),
        // a short jittered retry usually succeeds. Without this retry, ISS-tier popular
        // satellites end up with both their good+failed calls 429'd and we falsely show
        // "no hist." Tries: original + up to 2 retries with growing backoff.
        val backoffsMs = longArrayOf(0L, 700L, 2200L)
        var lastException: Throwable? = null
        for ((attempt, baseDelay) in backoffsMs.withIndex()) {
            if (baseDelay > 0L) {
                val jitter = (Math.random() * 400).toLong()
                kotlinx.coroutines.delay(baseDelay + jitter)
            }
            try {
                val list = networkApi.getObservations(noradCatId = noradId, status = status, limit = 30)
                if (attempt > 0) {
                    Log.d(TAG, "SatNOGS recovered for norad=$noradId status=$status after $attempt retries")
                }
                return ObsFetchResult(list, success = true)
            } catch (e: retrofit2.HttpException) {
                lastException = e
                if (e.code() != 429) {
                    Log.w(TAG, "SatNOGS HTTP ${e.code()} for norad=$noradId status=$status - not retrying")
                    return ObsFetchResult(emptyList(), success = false)
                }
                // 429 -> continue loop with backoff
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Network error norad=$noradId status=$status - not retrying", e)
                return ObsFetchResult(emptyList(), success = false)
            }
        }
        Log.w(TAG, "SatNOGS gave up after retries for norad=$noradId status=$status (last: ${lastException?.message})")
        return ObsFetchResult(emptyList(), success = false)
    }

    companion object {
        // 6-hour TTL - historical success rates change slowly enough that this is safe,
        // and short enough that yesterday's broken sat will get re-checked today.
        private const val OBS_TTL_MS: Long = 6L * 60L * 60L * 1000L
    }

    override suspend fun getTle(noradId: String): Tle? {
        return tleDao.getTle(noradId)?.toDomain()
    }

    override suspend fun fetchTleFromCelestrak(noradId: String): Tle? {
        if (noradId.isBlank()) return null
        val url = "https://celestrak.org/NORAD/elements/gp.php?CATNR=$noradId&FORMAT=TLE"
        return try {
            val body = celestrakApi.getTle(url).string()
            // Celestrak answers HTTP 200 with the literal text "No GP data found"
            // for unknown ids - parse defensively rather than trusting the status.
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val l1 = lines.getOrNull(lines.size - 2)
            val l2 = lines.getOrNull(lines.size - 1)
            if (l1 == null || l2 == null || !l1.startsWith("1 ") || !l2.startsWith("2 ")) {
                Log.w(TAG, "Celestrak returned no usable TLE for norad=$noradId")
                return null
            }
            val entity = TleEntity(
                noradId = noradId,
                line1 = l1,
                line2 = l2,
                lastUpdated = Instant.now().toEpochMilli(),
                epochMillis = parseTleEpoch(l1)?.toEpochMilli()
            )
            tleDao.insertTle(entity)
            entity.toDomain()
        } catch (e: Exception) {
            Log.w(TAG, "Celestrak fetch failed for norad=$noradId", e)
            null
        }
    }

    override suspend fun saveSatellites(satellites: List<Satellite>) {
        // Simple conversion is not needed since Room manages inserts, but implemented to obey interface
    }

    override suspend fun saveTransmitters(transmitters: List<Transmitter>) {
        // Implemented to satisfy interface
    }

    override suspend fun saveObservations(observations: List<Observation>) {
        // Implemented to satisfy interface
    }

    override suspend fun saveTle(tle: Tle) {
        // Implemented to satisfy interface
    }

    override suspend fun syncFromRemote(onProgress: ((String) -> Unit)?) {
        try {
            onProgress?.invoke("Downloading satellite catalog from SatNOGS DB…")
            Log.d(TAG, "Starting sync satellites from DB API...")
            val rawSats = dbApi.getSatellites()
            if (rawSats.isNotEmpty()) {
                val satEntities = rawSats.map { it.toEntity() }.filter { it.id.isNotEmpty() }
                if (satEntities.isNotEmpty()) {
                    onProgress?.invoke("Saving ${satEntities.size} satellites to local DB…")
                    satelliteDao.insertSatellites(satEntities)
                }
            }

            // Generate lookup sets of valid satellite IDs and foreign reference columns
            val allSats = satelliteDao.getAllSatellites()
            val validSatIds = allSats.map { it.id }.toSet()
            val validNoradIds = allSats.map { it.noradId }.filter { it.isNotEmpty() }.toSet()

            onProgress?.invoke("Downloading transmitter catalog…")
            Log.d(TAG, "Starting sync transmitters...")
            val rawTransmitters = dbApi.getTransmitters()
            if (rawTransmitters.isNotEmpty()) {
                val txEntities = rawTransmitters.map { it.toEntity() }
                    .filter { it.id.isNotEmpty() && it.frequency > 0 && it.satelliteId in validSatIds }
                if (txEntities.isNotEmpty()) {
                    onProgress?.invoke("Saving ${txEntities.size} transmitters…")
                    transmitterDao.insertTransmitters(txEntities)
                }
            }

            onProgress?.invoke("Downloading TLEs (orbital elements)…")
            Log.d(TAG, "Starting sync TLEs...")
            val rawTles = dbApi.getTles()
            if (rawTles.isNotEmpty()) {
                val tleEntities = rawTles.map { it.toEntity() }
                    .filter { it.noradId.isNotEmpty() && it.noradId in validNoradIds }
                if (tleEntities.isNotEmpty()) {
                    onProgress?.invoke("Saving ${tleEntities.size} TLEs…")
                    tleDao.insertTles(tleEntities)
                }
            }

            onProgress?.invoke("Downloading recent observations…")
            Log.d(TAG, "Syncing global recent observations for initialization...")
            val rawObs = networkApi.getObservations(limit = 100)
            if (rawObs.isNotEmpty()) {
                val obsEntities = rawObs.map { it.toEntity() }
                    .filter { it.id.isNotEmpty() && it.satelliteId in validSatIds }
                if (obsEntities.isNotEmpty()) {
                    observationDao.insertObservations(obsEntities)
                }
            }

            onProgress?.invoke("Sync complete.")
            Log.d(TAG, "Sync completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Sync operation failed", e)
            throw e
        }
    }

    override suspend fun pruneOldObservations(olderThanDays: Int) {
        val cutoff = System.currentTimeMillis() - (olderThanDays * 24L * 3600 * 1000)
        observationDao.pruneObservations(cutoff)
    }
}
