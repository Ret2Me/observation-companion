package pl.put.observationcompanion.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SatelliteDto(
    @Json(name = "sat_id") val satId: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "norad_cat_id") val noradCatId: Long?,
    @Json(name = "status") val status: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "telemetries") val telemetries: List<TelemetryDto>? = null
)

@JsonClass(generateAdapter = true)
data class TelemetryDto(
    @Json(name = "decoder") val decoder: String?
)

@JsonClass(generateAdapter = true)
data class TransmitterDto(
    @Json(name = "uuid") val uuid: String?,
    @Json(name = "sat_id") val satId: String?,
    @Json(name = "downlink_low") val downlinkLow: Long?, // Hz
    @Json(name = "type") val type: String?,
    @Json(name = "mode") val mode: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "status") val status: String?, // "active" / "inactive" / "invalid"
    @Json(name = "alive") val alive: Boolean?
)

@JsonClass(generateAdapter = true)
data class TleDto(
    @Json(name = "norad_cat_id") val noradCatId: Long?,
    @Json(name = "tle0") val tle0: String?,
    @Json(name = "tle1") val tle1: String?,
    @Json(name = "tle2") val tle2: String?,
    @Json(name = "updated") val updated: String?
)

@JsonClass(generateAdapter = true)
data class ObservationDto(
    @Json(name = "id") val id: Long?,
    @Json(name = "sat_id") val satId: String?,
    @Json(name = "vetted_status") val vettedStatus: String?, // "good", "failed", "unknown"
    @Json(name = "status") val status: String?, // "good", "bad", "failed", "future", "unknown"
    @Json(name = "start") val start: String?, // timestamp ISO8601
    @Json(name = "station_name") val stationName: String?,
    @Json(name = "ground_station") val groundStation: Long?
)
