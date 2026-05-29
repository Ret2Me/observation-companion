package pl.put.observationcompanion.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.Preset
import pl.put.observationcompanion.domain.repository.SatnogsRepository
import pl.put.observationcompanion.domain.repository.SettingsRepository
import pl.put.observationcompanion.domain.repository.UserSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val satnogsRepository: SatnogsRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val userSettingsState: StateFlow<UserSettings?> = settingsRepository.getUserSettingsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val presetsState: StateFlow<List<Preset>> = settingsRepository.getPresetsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _syncEvents = MutableSharedFlow<String>()
    val syncEvents: SharedFlow<String> = _syncEvents

    fun updateDbBaseUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.updateDbBaseUrl(url)
        }
    }

    fun updateNetworkBaseUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.updateNetworkBaseUrl(url)
        }
    }

    fun toggleAntennaBand(band: AntennaBand) {
        viewModelScope.launch {
            val settings = settingsRepository.getUserSettings()
            val currentBands = settings.antennaBands
            val newBands = if (currentBands.contains(band)) {
                if (currentBands.size > 1) {
                    currentBands - band
                } else {
                    currentBands
                }
            } else {
                currentBands + band
            }
            settingsRepository.updateAntennaBands(newBands)
        }
    }

    fun updateAlarmsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAlarmsEnabled(enabled)
        }
    }

    fun updateMinElevation(elevation: Double) {
        viewModelScope.launch {
            settingsRepository.updateMinElevation(elevation)
        }
    }

    fun updateAlarmLeadTime(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateAlarmLeadTime(minutes)
        }
    }

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
        }
    }

    fun applyPreset(preset: Preset) {
        viewModelScope.launch { settingsRepository.applyPreset(preset) }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch { settingsRepository.deletePreset(name) }
    }

    fun editPreset(originalName: String, edited: Preset) {
        viewModelScope.launch {
            if (originalName != edited.name) settingsRepository.deletePreset(originalName)
            settingsRepository.savePreset(edited)
        }
    }

    fun triggerForceSync() {
        viewModelScope.launch {
            _syncEvents.emit("Syncing...")
            try {
                satnogsRepository.syncFromRemote()
                _syncEvents.emit("Sync Completed Successfully!")
            } catch (e: Exception) {
                _syncEvents.emit("Sync Failed: ${e.message}")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
class SettingsViewModelFactory(
    private val appContainer: pl.put.observationcompanion.di.AppContainer
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(
            appContainer.satnogsRepository,
            appContainer.settingsRepository
        ) as T
    }
}
