package pl.put.observationcompanion.ui.screen.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import pl.put.observationcompanion.data.local.AppDatabase
import pl.put.observationcompanion.di.AppContainer
import pl.put.observationcompanion.domain.model.AntennaBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class Stats(
    val satellitesTotal: Int,
    val satellitesActive: Int,
    val satellitesWithDecoder: Int,
    val transmittersTotal: Int,
    val transmittersActive: Int,
    val transmittersPerBand: List<Pair<AntennaBand, Int>>,
    val transmittersOutOfBand: Int,
    val tlesTotal: Int,
    val lastSync: Instant?,
    val newestTleEpoch: Instant?,
    val oldestTleEpoch: Instant?,
    val observationsTotal: Int,
    val observationsGood: Int,
    val observationsFailed: Int,
    val dbSizeBytes: Long
)

class StatsViewModel(
    private val database: AppDatabase,
    private val context: Context
) : ViewModel() {

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _stats.value = withContext(Dispatchers.IO) { compute() }
        }
    }

    private suspend fun compute(): Stats {
        val sats = database.satelliteDao().getAllSatellites()
        val txs = database.transmitterDao().getAllTransmitters()
        val tleDao = database.tleDao()
        val obsDao = database.observationDao()

        val activeTxs = txs.filter { it.isActive }
        val perBand = AntennaBand.values().map { band ->
            band to activeTxs.count { it.frequency in band.frequencyRange }
        }
        val inAnyBand = activeTxs.count { tx ->
            AntennaBand.values().any { tx.frequency in it.frequencyRange }
        }
        val outOfBand = activeTxs.size - inAnyBand

        val dbFile = context.getDatabasePath("satnogs_companion.db")
        val dbSize = if (dbFile.exists()) dbFile.length() else 0L

        // Parse oldest/newest TLE epoch by scanning the table - number of rows
        // is small (~1.5k) so a full scan is fine for an admin/debug screen.
        val allTles = database.satelliteDao().getAllSatellites()
            .mapNotNull { tleDao.getTle(it.noradId)?.epochMillis }
        val newest = allTles.maxOrNull()?.let(Instant::ofEpochMilli)
        val oldest = allTles.minOrNull()?.let(Instant::ofEpochMilli)

        return Stats(
            satellitesTotal = sats.size,
            satellitesActive = sats.count { it.isActive },
            satellitesWithDecoder = sats.count { it.hasDecoder },
            transmittersTotal = txs.size,
            transmittersActive = activeTxs.size,
            transmittersPerBand = perBand,
            transmittersOutOfBand = outOfBand,
            tlesTotal = tleDao.countTles(),
            lastSync = tleDao.lastSyncMillis()?.let(Instant::ofEpochMilli),
            newestTleEpoch = newest,
            oldestTleEpoch = oldest,
            observationsTotal = obsDao.countAll(),
            observationsGood = obsDao.countGood(),
            observationsFailed = obsDao.countFailed(),
            dbSizeBytes = dbSize
        )
    }
}

@Suppress("UNCHECKED_CAST")
class StatsViewModelFactory(
    private val container: AppContainer,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return StatsViewModel(container.database, context.applicationContext) as T
    }
}
