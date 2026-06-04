package pl.put.observationcompanion.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

/** Google Play Services FusedLocation provider (Play Store flavor). */
class GmsLocationProvider(private val context: Context) : LocationProvider {

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): LocationFix? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val location = client
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .await()
        return location?.let { LocationFix(it.latitude, it.longitude, it.altitude) }
    }
}

object LocationProviderFactory {
    fun create(context: Context): LocationProvider =
        GmsLocationProvider(context.applicationContext)
}
