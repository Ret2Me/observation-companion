package pl.put.observationcompanion.location

/** A single observer position fix, in degrees + metres above sea level. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double
)

/**
 * One-shot location source. Implemented per distribution flavor:
 *  - gms  : Google Play Services FusedLocation
 *  - foss : AOSP LocationManager (no proprietary deps, for F-Droid)
 *
 * Each flavor also provides a `LocationProviderFactory.create(context)` with the
 * same fully-qualified name, so shared code can obtain the right implementation
 * without knowing which flavor is compiled.
 */
interface LocationProvider {
    /**
     * Returns the current device location, or null if no fix is available
     * (e.g. all providers disabled / GPS returned nothing).
     *
     * @throws SecurityException if location permission is missing.
     */
    suspend fun getCurrentLocation(): LocationFix?
}
