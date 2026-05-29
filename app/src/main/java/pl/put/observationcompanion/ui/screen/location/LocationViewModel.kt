package pl.put.observationcompanion.ui.screen.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.Preset
import pl.put.observationcompanion.domain.repository.SettingsRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LocationViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
) : ViewModel() {

    private val TAG = "LocationViewModel"

    private val _locationEvents = MutableSharedFlow<String>()
    val locationEvents: SharedFlow<String> = _locationEvents

    val presetsState: StateFlow<List<Preset>> = settingsRepository.getPresetsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun saveCurrentAsPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val s = settingsRepository.getUserSettings()
            settingsRepository.savePreset(
                Preset(
                    name = trimmed,
                    groundLat = s.groundLat,
                    groundLon = s.groundLon,
                    groundAlt = s.groundAlt,
                    antennaBands = s.antennaBands
                )
            )
            _locationEvents.emit("Preset \"$trimmed\" saved.")
        }
    }

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            settingsRepository.applyPreset(preset)
            _locationEvents.emit("Applied: ${preset.name}")
        }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            settingsRepository.deletePreset(name)
            _locationEvents.emit("Deleted preset \"$name\".")
        }
    }

    fun editPreset(originalName: String, edited: Preset) {
        viewModelScope.launch {
            if (originalName != edited.name) settingsRepository.deletePreset(originalName)
            settingsRepository.savePreset(edited)
            _locationEvents.emit("Preset \"${edited.name}\" updated.")
        }
    }

    fun saveLocation(lat: Double, lon: Double, alt: Double) {
        viewModelScope.launch {
            settingsRepository.updateLocation(lat, lon, alt)
            _locationEvents.emit("Location Saved Successfully!")
        }
    }

    /**
     * Applies a built-in observatory preset: jumps the observer to the site
     * and pre-selects the bands that match the observatory's flagship
     * receivers. The user can still override bands afterwards in Settings.
     */
    fun saveLocationAndBands(
        lat: Double,
        lon: Double,
        alt: Double,
        bands: Set<pl.put.observationcompanion.domain.model.AntennaBand>
    ) {
        viewModelScope.launch {
            settingsRepository.updateLocation(lat, lon, alt)
            if (bands.isNotEmpty()) settingsRepository.updateAntennaBands(bands)
            _locationEvents.emit("Observatory preset applied.")
        }
    }

    @SuppressLint("MissingPermission")
    fun requestGpsLocation() {
        viewModelScope.launch {
            _locationEvents.emit("Locating observer...")
            // Sanity-check the user actually has location enabled. FusedLocation
            // will otherwise return null silently and the user gets a confusing
            // "no location" toast.
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val anyProviderEnabled = lm?.let {
                it.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } ?: false
            if (!anyProviderEnabled) {
                _locationEvents.emit("Enable Location in system settings.")
                return@launch
            }

            try {
                val client = LocationServices.getFusedLocationProviderClient(appContext)
                val cts = CancellationTokenSource()
                val location = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
                if (location != null) {
                    saveLocation(location.latitude, location.longitude, location.altitude)
                    _locationEvents.emit("Observer localized.")
                } else {
                    _locationEvents.emit("GPS returned no fix. Try again outdoors.")
                }
            } catch (e: SecurityException) {
                _locationEvents.emit("Location permissions missing or denied.")
            } catch (e: Exception) {
                Log.e(TAG, "GPS request failed", e)
                _locationEvents.emit("Error fetching GPS: ${e.localizedMessage}")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
class LocationViewModelFactory(
    private val appContainer: pl.put.observationcompanion.di.AppContainer,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return LocationViewModel(
            appContainer.settingsRepository,
            context
        ) as T
    }
}
