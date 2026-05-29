package pl.put.observationcompanion.data.mapper

import pl.put.observationcompanion.data.local.entity.*
import pl.put.observationcompanion.data.remote.dto.*
import pl.put.observationcompanion.domain.model.*
import java.time.Instant
import java.time.format.DateTimeFormatter

fun SatelliteDto.toEntity(): SatelliteEntity {
    return SatelliteEntity(
        id = this.satId ?: "",
        name = this.name ?: "Unknown Satellite",
        noradId = this.noradCatId?.toString() ?: "",
        isActive = this.status == null || (this.status.lowercase() != "dead" && this.status.lowercase() != "re-entered" && this.status.lowercase() != "future"),
        description = this.description,
        hasDecoder = this.telemetries?.any { !it.decoder.isNullOrBlank() } == true
    )
}

fun SatelliteEntity.toDomain(): Satellite {
    return Satellite(
        id = this.id,
        name = this.name,
        noradId = this.noradId,
        isActive = this.isActive,
        description = this.description,
        hasDecoder = this.hasDecoder,
        observationsFetchedAt = this.observationsFetchedAt
    )
}

fun TleDto.toEntity(): TleEntity {
    val l1 = this.tle1 ?: ""
    return TleEntity(
        noradId = this.noradCatId?.toString() ?: "",
        line1 = l1,
        line2 = this.tle2 ?: "",
        lastUpdated = Instant.now().toEpochMilli(),
        epochMillis = parseTleEpoch(l1)?.toEpochMilli()
    )
}

fun TleEntity.toDomain(): Tle {
    return Tle(
        noradId = this.noradId,
        line1 = this.line1,
        line2 = this.line2,
        lastUpdated = Instant.ofEpochMilli(this.lastUpdated),
        epoch = this.epochMillis?.let(Instant::ofEpochMilli) ?: parseTleEpoch(this.line1)
    )
}

// TLE line 1: YYDDD.DDDDDDDD @ chars 18..32.
internal fun parseTleEpoch(line1: String): Instant? {
    if (line1.length < 32) return null
    return try {
        val yy = line1.substring(18, 20).trim().toInt()
        val year = if (yy < 57) 2000 + yy else 1900 + yy
        val dayOfYear = line1.substring(20, 32).trim().toDouble()
        val jan1Millis = Instant.parse("$year-01-01T00:00:00Z").toEpochMilli()
        Instant.ofEpochMilli(jan1Millis + ((dayOfYear - 1.0) * 86_400_000.0).toLong())
    } catch (_: Exception) {
        null
    }
}

fun TransmitterDto.toEntity(): TransmitterEntity {
    val statusLower = this.status?.lowercase()
    val active = when {
        this.alive == false -> false
        statusLower == "active" -> true
        statusLower == "inactive" || statusLower == "invalid" -> false
        this.alive == true -> true
        else -> statusLower == null
    }
    return TransmitterEntity(
        id = this.uuid ?: "",
        satelliteId = this.satId ?: "",
        frequency = this.downlinkLow ?: 0L,
        modulation = this.type,
        mode = this.mode,
        description = this.description,
        isActive = active,
        status = this.status
    )
}

fun TransmitterEntity.toDomain(): Transmitter {
    return Transmitter(
        id = this.id,
        satelliteId = this.satelliteId,
        frequency = this.frequency,
        modulation = this.modulation,
        mode = this.mode,
        description = this.description,
        isActive = this.isActive,
        status = this.status
    )
}

fun ObservationDto.toEntity(): ObservationEntity {
    val epochMillis = try {
        this.start?.let { Instant.parse(it).toEpochMilli() } ?: Instant.now().toEpochMilli()
    } catch (e: Exception) {
        Instant.now().toEpochMilli()
    }
    // vetted_status is often "unknown" in newer SatNOGS, so we fall back to `status`.
    val effectiveStatus = (this.vettedStatus?.lowercase()?.takeIf { it != "unknown" && it != "unvetted" }
        ?: this.status?.lowercase()
        ?: "unknown")
    return ObservationEntity(
        id = this.id?.toString() ?: "",
        satelliteId = this.satId ?: "",
        status = when (effectiveStatus) {
            "good", "vetted" -> "good"
            "failed", "bad" -> "failed"
            else -> "unknown"
        },
        timestamp = epochMillis,
        stationName = this.stationName?.takeIf { it.isNotBlank() }
            ?: this.groundStation?.let { "Station #$it" }
    )
}

fun ObservationEntity.toDomain(): Observation {
    return Observation(
        id = this.id,
        satelliteId = this.satelliteId,
        status = this.status,
        timestamp = Instant.ofEpochMilli(this.timestamp),
        stationName = this.stationName
    )
}

fun Pass.toEntity(computedAt: Long = Instant.now().toEpochMilli()): PassEntity {
    return PassEntity(
        id = "$satelliteId-${aos.epochSecond}",
        satelliteId = satelliteId,
        noradId = noradId,
        satelliteName = satelliteName,
        statusOrdinal = status.ordinal,
        aosMillis = aos.toEpochMilli(),
        tcaMillis = tca.toEpochMilli(),
        losMillis = los.toEpochMilli(),
        maxElevation = maxElevation,
        startAzimuth = startAzimuth,
        tcaAzimuth = tcaAzimuth,
        endAzimuth = endAzimuth,
        transmitterId = matchedTransmitter?.id,
        tleEpochMillis = tleEpoch?.toEpochMilli(),
        satelliteHasDecoder = satelliteHasDecoder,
        computedAt = computedAt
    )
}

fun PassEntity.toDomain(transmitter: Transmitter?): Pass {
    return Pass(
        satelliteId = satelliteId,
        noradId = noradId,
        satelliteName = satelliteName,
        status = SatelliteStatus.values().getOrElse(statusOrdinal) { SatelliteStatus.NEUTRAL },
        aos = Instant.ofEpochMilli(aosMillis),
        tca = Instant.ofEpochMilli(tcaMillis),
        los = Instant.ofEpochMilli(losMillis),
        maxElevation = maxElevation,
        startAzimuth = startAzimuth,
        tcaAzimuth = tcaAzimuth,
        endAzimuth = endAzimuth,
        matchedTransmitter = transmitter,
        dopplerPoints = emptyList(),
        receptionProbability = 0.0,
        observationGoodCount = 0,
        observationTotalCount = 0,
        satelliteHasDecoder = satelliteHasDecoder,
        tleEpoch = tleEpochMillis?.let(Instant::ofEpochMilli)
    )
}
