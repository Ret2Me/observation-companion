package pl.put.observationcompanion.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AOSP LocationManager provider (F-Droid flavor) - no Google Play Services.
 * Returns a recent last-known fix immediately when available, otherwise waits
 * for a single fresh update from GPS (falling back to the network provider).
 */
class PlatformLocationProvider(private val context: Context) : LocationProvider {

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): LocationFix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }

        // Fast path: a fix the system already has.
        lm.getLastKnownLocation(provider)?.let { return it.toFix() }

        // Otherwise request one single update and suspend until it arrives.
        return suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location.toFix())
                }

                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("Required by LocationListener on older APIs")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }

    private fun Location.toFix() = LocationFix(latitude, longitude, altitude)
}

object LocationProviderFactory {
    fun create(context: Context): LocationProvider =
        PlatformLocationProvider(context.applicationContext)
}
