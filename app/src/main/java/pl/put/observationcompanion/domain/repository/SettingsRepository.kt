package pl.put.observationcompanion.domain.repository

import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.Preset
import kotlinx.coroutines.flow.Flow

data class UserSettings(
    val dbBaseUrl: String,
    val networkBaseUrl: String,
    val groundLat: Double,
    val groundLon: Double,
    val groundAlt: Double,
    val antennaBands: Set<AntennaBand>,
    val alarmsEnabled: Boolean,
    val minElevation: Double,
    val alarmLeadTimeMinutes: Int
)

interface SettingsRepository {
    fun getUserSettingsFlow(): Flow<UserSettings>
    suspend fun getUserSettings(): UserSettings
    suspend fun updateDbBaseUrl(url: String)
    suspend fun updateNetworkBaseUrl(url: String)
    suspend fun updateLocation(lat: Double, lon: Double, alt: Double)
    suspend fun updateAntennaBands(bands: Set<AntennaBand>)
    suspend fun updateAlarmsEnabled(enabled: Boolean)
    suspend fun updateMinElevation(elevation: Double)
    suspend fun updateAlarmLeadTime(minutes: Int)

    fun getPresetsFlow(): Flow<List<Preset>>
    suspend fun savePreset(preset: Preset)
    suspend fun deletePreset(name: String)
    /** Applies preset's location + bands to the active user settings. */
    suspend fun applyPreset(preset: Preset)

    fun getCompactModeFlow(): Flow<Boolean>
    suspend fun setCompactMode(enabled: Boolean)
}
