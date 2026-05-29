package pl.put.observationcompanion.data.remote.api

import pl.put.observationcompanion.data.remote.dto.*
import okhttp3.ResponseBody
import retrofit2.http.*

const val HEADER_SATNOGS_HOST = "X-Satnogs-Host"
const val HOST_DB = "DB"
const val HOST_NETWORK = "NETWORK"

interface SatnogsDbApi {
    @GET("satellites/")
    @Headers("$HEADER_SATNOGS_HOST: $HOST_DB")
    suspend fun getSatellites(@Query("status") status: String? = "alive"): List<SatelliteDto>

    @GET("transmitters/")
    @Headers("$HEADER_SATNOGS_HOST: $HOST_DB")
    suspend fun getTransmitters(): List<TransmitterDto>

    @GET("tle/")
    @Headers("$HEADER_SATNOGS_HOST: $HOST_DB")
    suspend fun getTles(): List<TleDto>
}

interface SatnogsNetworkApi {
    @GET("observations/")
    @Headers("$HEADER_SATNOGS_HOST: $HOST_NETWORK")
    suspend fun getObservations(
        @Query("norad_cat_id") noradCatId: String? = null,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int? = 50
    ): List<ObservationDto>
}

// Celestrak serves plain-text TLE. @Url takes an absolute URL so it bypasses
// the SatNOGS base URL and the dynamic-host interceptor (no host header here).
interface CelestrakApi {
    @GET
    suspend fun getTle(@Url url: String): ResponseBody
}
