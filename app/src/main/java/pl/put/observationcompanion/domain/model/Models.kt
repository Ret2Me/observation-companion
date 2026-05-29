package pl.put.observationcompanion.domain.model

import java.time.Instant

data class Satellite(
    val id: String,
    val name: String,
    val noradId: String,
    val isActive: Boolean = true,
    val description: String? = null,
    val hasDecoder: Boolean = false,
    val observationsFetchedAt: Long = 0L
)

data class Tle(
    val noradId: String,
    val line1: String,
    val line2: String,
    val lastUpdated: Instant = Instant.now(),
    val epoch: Instant? = null
)

data class Transmitter(
    val id: String,
    val satelliteId: String,
    val frequency: Long, // Hz
    val modulation: String?,
    val mode: String?,
    val description: String?,
    val isActive: Boolean = true,
    val status: String? = null // raw SatNOGS status: "active" / "inactive" / "invalid"
)

data class Observation(
    val id: String,
    val satelliteId: String,
    val status: String, // good / failed / unknown
    val timestamp: Instant,
    val stationName: String? = null
)

enum class SatelliteStatus {
    UNLIKELY,
    NEUTRAL,
    PROMISING,
    NO_DATA
}

data class Preset(
    val name: String,
    val groundLat: Double,
    val groundLon: Double,
    val groundAlt: Double,
    val antennaBands: Set<AntennaBand>
)

enum class AntennaBand(val displayName: String, val frequencyRange: LongRange) {
    VHF("VHF", 140_000_000L..150_000_000L),
    UHF("UHF", 420_000_000L..450_000_000L),
    L_BAND("L-Band", 1_000_000_000L..2_000_000_000L),
    S_BAND("S-Band", 2_000_000_000L..4_000_000_000L),
    C_BAND("C-Band", 4_000_000_000L..8_000_000_000L),
    X_BAND("X-Band", 8_000_000_000L..12_000_000_000L)
}

data class DopplerPoint(
    val timestamp: Instant,
    val frequencyOffset: Double // Hz
)

data class SkyPoint(
    val azimuth: Double,   // deg, 0=N
    val elevation: Double, // deg, 0=horizon, 90=zenith
    val time: Instant
)

data class GroundTrackPoint(
    val latitude: Double,  // deg, -90..90
    val longitude: Double, // deg, -180..180
    val time: Instant
)

data class Pass(
    val satelliteId: String,
    val noradId: String,
    val satelliteName: String,
    val status: SatelliteStatus,
    val aos: Instant,
    val tca: Instant,
    val los: Instant,
    val maxElevation: Double,
    val startAzimuth: Double,
    val tcaAzimuth: Double,
    val endAzimuth: Double,
    val matchedTransmitter: Transmitter?,
    val dopplerPoints: List<DopplerPoint> = emptyList(),
    val receptionProbability: Double = 0.0,
    val observationGoodCount: Int = 0,
    val observationTotalCount: Int = 0,
    val satelliteHasDecoder: Boolean = false,
    val tleEpoch: Instant? = null
)
