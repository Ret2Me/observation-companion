package pl.put.observationcompanion.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.Preset
import pl.put.observationcompanion.domain.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = "observation_companion_prefs")

@Singleton
class PreferencesDataSource @Inject constructor(
    private val context: Context
) {
    companion object {
        val KEY_DB_BASE_URL = stringPreferencesKey("db_base_url")
        val KEY_NETWORK_BASE_URL = stringPreferencesKey("network_base_url")
        val KEY_GROUND_LAT = doublePreferencesKey("ground_lat")
        val KEY_GROUND_LON = doublePreferencesKey("ground_lon")
        val KEY_GROUND_ALT = doublePreferencesKey("ground_alt")
        val KEY_ANTENNA_BANDS = stringPreferencesKey("antenna_bands")
        val KEY_ALARMS_ENABLED = booleanPreferencesKey("alarms_enabled")
        val KEY_MIN_ELEVATION = doublePreferencesKey("min_elevation")
        val KEY_ALARM_LEAD_TIME = intPreferencesKey("alarm_lead_time_minutes")
        val KEY_PRESETS = stringPreferencesKey("presets_v1")
        val KEY_COMPACT_MODE = booleanPreferencesKey("compact_mode")
    }

    val compactModeFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_COMPACT_MODE] ?: false }

    suspend fun setCompactMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_COMPACT_MODE] = enabled }
    }

    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            dbBaseUrl = prefs[KEY_DB_BASE_URL] ?: "https://db.satnogs.org/api/",
            networkBaseUrl = prefs[KEY_NETWORK_BASE_URL] ?: "https://network.satnogs.org/api/",
            groundLat = prefs[KEY_GROUND_LAT] ?: 52.4064, // Poznan default
            groundLon = prefs[KEY_GROUND_LON] ?: 16.9252,
            groundAlt = prefs[KEY_GROUND_ALT] ?: 80.0,
            antennaBands = prefs[KEY_ANTENNA_BANDS]?.let { bandsStr ->
                if (bandsStr.isNotEmpty()) {
                    bandsStr.split(",").mapNotNull {
                        try {
                            when (it) {
                                "BAND_2M" -> AntennaBand.VHF
                                "BAND_70CM" -> AntennaBand.UHF
                                "BAND_L" -> AntennaBand.L_BAND
                                "BAND_S" -> AntennaBand.S_BAND
                                else -> AntennaBand.valueOf(it)
                            }
                        } catch (e: java.lang.Exception) {
                            null
                        }
                    }.toSet()
                } else {
                    null
                }
            } ?: run {
                val legacyBandStr = prefs[stringPreferencesKey("antenna_band")]
                val fallbackBand = legacyBandStr?.let {
                    try {
                        when (it) {
                            "BAND_2M" -> AntennaBand.VHF
                            "BAND_70CM" -> AntennaBand.UHF
                            "BAND_L" -> AntennaBand.L_BAND
                            "BAND_S" -> AntennaBand.S_BAND
                            else -> AntennaBand.valueOf(it)
                        }
                    } catch (e: java.lang.Exception) {
                        null
                    }
                } ?: AntennaBand.VHF
                setOf(fallbackBand)
            },
            alarmsEnabled = prefs[KEY_ALARMS_ENABLED] ?: true,
            minElevation = prefs[KEY_MIN_ELEVATION] ?: 10.0,
            alarmLeadTimeMinutes = prefs[KEY_ALARM_LEAD_TIME] ?: 5
        )
    }

    suspend fun getUserSettings(): UserSettings {
        return userSettingsFlow.first()
    }

    suspend fun updateDbBaseUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DB_BASE_URL] = url }
    }

    suspend fun updateNetworkBaseUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[KEY_NETWORK_BASE_URL] = url }
    }

    suspend fun updateLocation(lat: Double, lon: Double, alt: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GROUND_LAT] = lat
            prefs[KEY_GROUND_LON] = lon
            prefs[KEY_GROUND_ALT] = alt
        }
    }

    suspend fun updateAntennaBands(bands: Set<AntennaBand>) {
        context.dataStore.edit { prefs -> prefs[KEY_ANTENNA_BANDS] = bands.joinToString(",") { it.name } }
    }

    suspend fun updateAlarmsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_ALARMS_ENABLED] = enabled }
    }

    suspend fun updateMinElevation(elevation: Double) {
        context.dataStore.edit { prefs -> prefs[KEY_MIN_ELEVATION] = elevation }
    }

    suspend fun updateAlarmLeadTime(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_ALARM_LEAD_TIME] = minutes }
    }

    // Presets: one per line, format `name=...;lat=...;lon=...;alt=...;bands=...`.
    val presetsFlow: Flow<List<Preset>> = context.dataStore.data.map { prefs ->
        prefs[KEY_PRESETS]?.takeIf { it.isNotBlank() }
            ?.lineSequence()
            ?.mapNotNull(::deserializePreset)
            ?.toList()
            ?: emptyList()
    }

    suspend fun getPresets(): List<Preset> = presetsFlow.first()

    suspend fun savePreset(preset: Preset) {
        val current = getPresets().filter { it.name != preset.name } + preset
        context.dataStore.edit { prefs ->
            prefs[KEY_PRESETS] = current.joinToString("\n", transform = ::serializePreset)
        }
    }

    suspend fun deletePreset(name: String) {
        val current = getPresets().filter { it.name != name }
        context.dataStore.edit { prefs ->
            prefs[KEY_PRESETS] = current.joinToString("\n", transform = ::serializePreset)
        }
    }

    private fun serializePreset(p: Preset): String {
        val safeName = p.name.replace(Regex("[;\\n=]"), " ").trim()
        val bands = p.antennaBands.joinToString(",") { it.name }
        return "name=$safeName;lat=${p.groundLat};lon=${p.groundLon};alt=${p.groundAlt};bands=$bands"
    }

    private fun deserializePreset(line: String): Preset? {
        val parts = line.split(";").mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()
        val name = parts["name"]?.takeIf { it.isNotBlank() } ?: return null
        val lat = parts["lat"]?.toDoubleOrNull() ?: return null
        val lon = parts["lon"]?.toDoubleOrNull() ?: return null
        val alt = parts["alt"]?.toDoubleOrNull() ?: 0.0
        val bands = parts["bands"]?.split(",")
            ?.mapNotNull { runCatching { AntennaBand.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: emptySet()
        return Preset(name, lat, lon, alt, bands)
    }
}
