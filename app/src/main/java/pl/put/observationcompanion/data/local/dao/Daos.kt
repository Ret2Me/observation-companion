package pl.put.observationcompanion.data.local.dao

import androidx.room.*
import pl.put.observationcompanion.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SatelliteDao {
    @Query("SELECT * FROM satellites ORDER BY name ASC")
    fun getAllSatellitesFlow(): Flow<List<SatelliteEntity>>

    @Query("SELECT * FROM satellites ORDER BY name ASC")
    suspend fun getAllSatellites(): List<SatelliteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSatellites(satellites: List<SatelliteEntity>)

    @Query("SELECT * FROM satellites WHERE id = :id LIMIT 1")
    suspend fun getSatelliteById(id: String): SatelliteEntity?

    @Query("UPDATE satellites SET observationsFetchedAt = :ts WHERE id = :id")
    suspend fun touchObservationsFetched(id: String, ts: Long)

    @Query("SELECT * FROM satellites WHERE id IN (SELECT docid FROM satellites_fts WHERE name MATCH :query)")
    fun searchSatellitesFlow(query: String): Flow<List<SatelliteEntity>>

    @Query("DELETE FROM satellites")
    suspend fun clearAll()
}

@Dao
interface TleDao {
    @Query("SELECT * FROM tles WHERE noradId = :noradId LIMIT 1")
    fun getTleFlow(noradId: String): Flow<TleEntity?>

    @Query("SELECT * FROM tles WHERE noradId = :noradId LIMIT 1")
    suspend fun getTle(noradId: String): TleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTle(tle: TleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTles(tles: List<TleEntity>)

    @Query("SELECT COUNT(*) FROM tles")
    suspend fun countTles(): Int

    @Query("SELECT MAX(lastUpdated) FROM tles")
    suspend fun lastSyncMillis(): Long?

    @Query("SELECT MAX(epochMillis) FROM tles")
    suspend fun newestEpochMillis(): Long?
}

@Dao
interface TransmitterDao {
    @Query("SELECT * FROM transmitters ORDER BY frequency ASC")
    fun getAllTransmittersFlow(): Flow<List<TransmitterEntity>>

    @Query("SELECT * FROM transmitters ORDER BY frequency ASC")
    suspend fun getAllTransmitters(): List<TransmitterEntity>

    @Query("SELECT * FROM transmitters WHERE satelliteId = :satelliteId ORDER BY frequency ASC")
    suspend fun getTransmittersForSatellite(satelliteId: String): List<TransmitterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransmitters(transmitters: List<TransmitterEntity>)
}

@Dao
interface PassCacheDao {
    @Query("SELECT * FROM pass_cache ORDER BY aosMillis ASC")
    suspend fun getAll(): List<PassEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(passes: List<PassEntity>)

    @Query("DELETE FROM pass_cache")
    suspend fun clearAll()

    @Query("SELECT MAX(computedAt) FROM pass_cache")
    suspend fun lastComputedAt(): Long?
}

@Dao
interface ObservationDao {
    @Query("SELECT * FROM observations WHERE satelliteId = :satelliteId ORDER BY timestamp DESC")
    fun getObservationsFlow(satelliteId: String): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM observations WHERE satelliteId = :satelliteId ORDER BY timestamp DESC")
    suspend fun getObservations(satelliteId: String): List<ObservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObservations(observations: List<ObservationEntity>)

    @Query("DELETE FROM observations WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneObservations(cutoffTimestamp: Long)

    @Query("SELECT COUNT(*) FROM observations")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM observations WHERE status = 'good'")
    suspend fun countGood(): Int

    @Query("SELECT COUNT(*) FROM observations WHERE status = 'failed'")
    suspend fun countFailed(): Int
}
