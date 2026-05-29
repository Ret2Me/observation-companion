package pl.put.observationcompanion.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "satellites",
    indices = [Index(value = ["noradId"], unique = false)]
)
data class SatelliteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val noradId: String,
    val isActive: Boolean,
    val description: String?,
    val hasDecoder: Boolean = false,
    val observationsFetchedAt: Long = 0L
)

@Entity(
    tableName = "tles",
    indices = [Index(value = ["noradId"])]
)
data class TleEntity(
    @PrimaryKey val noradId: String,
    val line1: String,
    val line2: String,
    val lastUpdated: Long,
    val epochMillis: Long? = null
)

@Fts4(contentEntity = SatelliteEntity::class)
@Entity(tableName = "satellites_fts")
data class SatelliteFtsEntity(
    val name: String
)

@Entity(
    tableName = "transmitters",
    foreignKeys = [
        ForeignKey(
            entity = SatelliteEntity::class,
            parentColumns = ["id"],
            childColumns = ["satelliteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["satelliteId"])]
)
data class TransmitterEntity(
    @PrimaryKey val id: String,
    val satelliteId: String,
    val frequency: Long,
    val modulation: String?,
    val mode: String?,
    val description: String?,
    val isActive: Boolean,
    val status: String? = null
)

// Cache of propagation results - the app starts from this list while a fresh
// propagation runs in the background.
@Entity(tableName = "pass_cache")
data class PassEntity(
    @PrimaryKey val id: String,
    val satelliteId: String,
    val noradId: String,
    val satelliteName: String,
    val statusOrdinal: Int,
    val aosMillis: Long,
    val tcaMillis: Long,
    val losMillis: Long,
    val maxElevation: Double,
    val startAzimuth: Double,
    val tcaAzimuth: Double,
    val endAzimuth: Double,
    val transmitterId: String?,
    val tleEpochMillis: Long?,
    val satelliteHasDecoder: Boolean,
    val computedAt: Long
)

@Entity(
    tableName = "observations",
    foreignKeys = [
        ForeignKey(
            entity = SatelliteEntity::class,
            parentColumns = ["id"],
            childColumns = ["satelliteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["satelliteId"])]
)
data class ObservationEntity(
    @PrimaryKey val id: String,
    val satelliteId: String,
    val status: String,
    val timestamp: Long,
    val stationName: String? = null
)
