package pl.put.observationcompanion.data.repository

import pl.put.observationcompanion.data.preferences.PreferencesDataSource
import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.BuiltInObservatories
import pl.put.observationcompanion.domain.model.Preset
import pl.put.observationcompanion.domain.repository.SettingsRepository
import pl.put.observationcompanion.domain.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val preferencesDataSource: PreferencesDataSource
) : SettingsRepository {

    override fun getUserSettingsFlow(): Flow<UserSettings> {
        return preferencesDataSource.userSettingsFlow
    }

    override suspend fun getUserSettings(): UserSettings {
        return preferencesDataSource.getUserSettings()
    }

    override suspend fun updateDbBaseUrl(url: String) {
        preferencesDataSource.updateDbBaseUrl(url)
    }

    override suspend fun updateNetworkBaseUrl(url: String) {
        preferencesDataSource.updateNetworkBaseUrl(url)
    }

    override suspend fun updateLocation(lat: Double, lon: Double, alt: Double) {
        preferencesDataSource.updateLocation(lat, lon, alt)
    }

    override suspend fun updateAntennaBands(bands: Set<AntennaBand>) {
        preferencesDataSource.updateAntennaBands(bands)
    }

    override suspend fun updateAlarmsEnabled(enabled: Boolean) {
        preferencesDataSource.updateAlarmsEnabled(enabled)
    }

    override suspend fun updateMinElevation(elevation: Double) {
        preferencesDataSource.updateMinElevation(elevation)
    }

    override suspend fun updateAlarmLeadTime(minutes: Int) {
        preferencesDataSource.updateAlarmLeadTime(minutes)
    }

    override fun getPresetsFlow(): Flow<List<Preset>> = preferencesDataSource.presetsFlow

    override suspend fun seedDefaultPresetsIfNeeded() =
        preferencesDataSource.seedDefaultPresetsIfNeeded(BuiltInObservatories.all)

    override suspend fun savePreset(preset: Preset) = preferencesDataSource.savePreset(preset)

    override suspend fun deletePreset(name: String) = preferencesDataSource.deletePreset(name)

    override suspend fun applyPreset(preset: Preset) {
        preferencesDataSource.updateLocation(preset.groundLat, preset.groundLon, preset.groundAlt)
        preferencesDataSource.updateAntennaBands(preset.antennaBands)
    }

    override fun getCompactModeFlow(): Flow<Boolean> = preferencesDataSource.compactModeFlow

    override suspend fun setCompactMode(enabled: Boolean) = preferencesDataSource.setCompactMode(enabled)
}
