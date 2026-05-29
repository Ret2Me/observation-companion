package pl.put.observationcompanion.ui.screen.passes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import pl.put.observationcompanion.domain.model.Pass
import pl.put.observationcompanion.domain.model.Tle
import pl.put.observationcompanion.domain.model.Transmitter
import pl.put.observationcompanion.data.local.dao.PassCacheDao
import pl.put.observationcompanion.data.mapper.toDomain
import pl.put.observationcompanion.data.mapper.toEntity
import pl.put.observationcompanion.domain.orbit.SatPropagator
import pl.put.observationcompanion.domain.repository.SatnogsRepository
import pl.put.observationcompanion.domain.repository.SettingsRepository
import pl.put.observationcompanion.domain.usecase.EvaluateReceptionProbabilityUseCase
import pl.put.observationcompanion.domain.usecase.FilterSatellitesByBandUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

enum class PassSortMode(val label: String) {
    BY_AOS("Time"),
    BY_MAX_ELEVATION("Elevation"),
    BY_PROBABILITY("Probability"),
    BY_NAME("Name")
}

data class LoadingProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val log: List<String> = emptyList()
)

sealed interface PassesUiState {
    data class Loading(val progress: LoadingProgress) : PassesUiState
    data class Success(
        val passes: List<Pass>,
        val totalCount: Int,
        val sortMode: PassSortMode,
        val canLoadMore: Boolean,
        val skippedCount: Int,
        val availableSatelliteNames: List<String>,
        val selectedSatelliteNames: Set<String>,
        val filterHiddenCount: Int
    ) : PassesUiState
    data class Error(val message: String) : PassesUiState
}

class PassesViewModel(
    private val satnogsRepository: SatnogsRepository,
    private val settingsRepository: SettingsRepository,
    private val propagator: SatPropagator,
    private val receptionProbabilityEvaluator: EvaluateReceptionProbabilityUseCase,
    private val filterByBandUseCase: FilterSatellitesByBandUseCase,
    private val passCacheDao: PassCacheDao
) : ViewModel() {

    private val TAG = "PassesViewModel"

    // predict4java loops forever for some orbits. Future.get(timeout) lets us
    // drop the thread (cached pool -> fresh thread for the next propagation).
    private val propagationExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "sat-prop").apply { isDaemon = true }
    }

    override fun onCleared() {
        super.onCleared()
        propagationExecutor.shutdownNow()
    }

    private val _allPasses = MutableStateFlow<List<Pass>>(emptyList())
    private val _loadingProgress = MutableStateFlow<LoadingProgress?>(LoadingProgress("Starting…"))
    private val _error = MutableStateFlow<String?>(null)
    private val _skippedCount = MutableStateFlow(0)
    private val _sortMode = MutableStateFlow(PassSortMode.BY_AOS)
    private val _visibleCount = MutableStateFlow(PAGE_SIZE)
    private val _selectedSatelliteNames = MutableStateFlow<Set<String>>(emptySet())

    private val historyLoadedSats: MutableSet<String> = mutableSetOf()
    @Volatile private var historyJob: Job? = null

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val loadingProgress: StateFlow<LoadingProgress?> = _loadingProgress.asStateFlow()

    // Per-satellite context kept from the last propagation.
    private val tleBySatId: MutableMap<String, Tle> = mutableMapOf()
    private val transmitterBySatId: MutableMap<String, Transmitter?> = mutableMapOf()
    @Volatile private var lastStation: SatPropagator.GroundStation? = null

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    val sortMode: StateFlow<PassSortMode> = _sortMode.asStateFlow()

    val uiState: StateFlow<PassesUiState> = combine(
        combine(_allPasses, _loadingProgress, _error) { passes, progress, err -> Triple(passes, progress, err) },
        combine(_sortMode, _selectedSatelliteNames) { sort, selected -> sort to selected },
        _visibleCount,
        _skippedCount
    ) { (passes, progress, err), sortAndFilter, visibleCount, skipped ->
        val (sort, selectedNames) = sortAndFilter
        when {
            err != null && passes.isEmpty() -> PassesUiState.Error(err)
            progress != null && passes.isEmpty() -> PassesUiState.Loading(progress)
            else -> {
                val allNames = passes.map { it.satelliteName }.distinct().sortedBy { it.lowercase() }
                val filtered = if (selectedNames.isEmpty()) passes
                else passes.filter { it.satelliteName in selectedNames }
                val sorted = sortPasses(filtered, sort)
                val visible = sorted.take(visibleCount)
                PassesUiState.Success(
                    passes = visible,
                    totalCount = sorted.size,
                    sortMode = sort,
                    canLoadMore = visibleCount < sorted.size,
                    skippedCount = skipped,
                    availableSatelliteNames = allNames,
                    selectedSatelliteNames = selectedNames,
                    filterHiddenCount = passes.size - filtered.size
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PassesUiState.Loading(LoadingProgress("Starting…")))

    private fun setStage(stage: String, current: Int = 0, total: Int = 0) {
        val prev = _loadingProgress.value
        val newLog = (prev?.log.orEmpty() + (prev?.stage?.takeIf { it.isNotBlank() } ?: ""))
            .filter { it.isNotBlank() }
            .takeLast(LOG_TAIL_SIZE)
        _loadingProgress.value = LoadingProgress(stage, current, total, newLog)
    }

    init {
        viewModelScope.launch {
            loadFromCache()
            settingsRepository.getUserSettingsFlow().collect {
                refreshPasses(forceRemoteSync = false)
            }
        }
    }

    private suspend fun loadFromCache() {
        try {
            val cached = withContext(Dispatchers.IO) { passCacheDao.getAll() }
            if (cached.isEmpty()) return
            val transmitters = satnogsRepository.getTransmitters().associateBy { it.id }
            val passes = cached.map { it.toDomain(transmitter = transmitters[it.transmitterId]) }
            _allPasses.value = passes
            _loadingProgress.value = null
            val computedAt = passCacheDao.lastComputedAt()
            if (computedAt != null) {
                _statusNotice.value = "Cached ${humanAgo(Instant.ofEpochMilli(computedAt))} - refreshing…"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading pass cache", e)
        }
    }

    private fun humanAgo(t: Instant): String {
        val secs = java.time.Duration.between(t, Instant.now()).seconds
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 86_400 -> "${secs / 3600} h ago"
            else -> "${secs / 86_400} d ago"
        }
    }

    fun setSortMode(mode: PassSortMode) {
        _sortMode.value = mode
        ensureHistoryForVisible()
    }

    fun setSelectedSatelliteNames(names: Set<String>) {
        _selectedSatelliteNames.value = names
        resetPaging()
    }

    fun clearSatelliteNameFilter() {
        _selectedSatelliteNames.value = emptySet()
        resetPaging()
    }

    fun loadMore() {
        _visibleCount.value = _visibleCount.value + PAGE_SIZE
        ensureHistoryForVisible()
    }

    fun resetPaging() {
        _visibleCount.value = PAGE_SIZE
    }

    private fun sortPasses(list: List<Pass>, mode: PassSortMode): List<Pass> = when (mode) {
        PassSortMode.BY_AOS -> list.sortedBy { it.aos }
        PassSortMode.BY_MAX_ELEVATION -> list.sortedByDescending { it.maxElevation }
        PassSortMode.BY_PROBABILITY -> list.sortedByDescending { it.receptionProbability }
        PassSortMode.BY_NAME -> list.sortedBy { it.satelliteName.lowercase() }
    }

    fun refreshPasses(forceRemoteSync: Boolean = false) {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            _skippedCount.value = 0
            val syncProgress: (String) -> Unit = { msg ->
                viewModelScope.launch { setStage(msg) }
            }
            try {
                if (forceRemoteSync) {
                    setStage("Refreshing SatNOGS catalog…")
                    try {
                        satnogsRepository.syncFromRemote(syncProgress)
                    } catch (e: Exception) {
                        Log.e(TAG, "Pre-sync failed, using cached db", e)
                    }
                }

                setStage("Reading local satellite cache…")
                val settings = settingsRepository.getUserSettings()
                var availableSatellites = satnogsRepository.getSatellites().filter { it.isActive }

                if (availableSatellites.isEmpty()) {
                    Log.d(TAG, "Empty satellite cache, bootstrapping...")
                    setStage("First run - bootstrapping from SatNOGS DB…")
                    try {
                        satnogsRepository.syncFromRemote(syncProgress)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bootstrap failed", e)
                    }
                    availableSatellites = satnogsRepository.getSatellites().filter { it.isActive }
                }

                setStage("Loaded ${availableSatellites.size} active satellites. Loading transmitters…")
                val transmitters = satnogsRepository.getTransmitters().filter { it.isActive }

                val station = SatPropagator.GroundStation(
                    latDegrees = settings.groundLat,
                    lonDegrees = settings.groundLon,
                    altMeters = settings.groundAlt
                )
                lastStation = station

                setStage("Matching ${transmitters.size} transmitters against your antenna bands…")
                val matched = filterByBandUseCase.execute(
                    availableSatellites,
                    transmitters,
                    settings.antennaBands
                )

                if (matched.isEmpty()) {
                    _allPasses.value = emptyList()
                    _loadingProgress.value = null
                    _isRefreshing.value = false
                    return@launch
                }

                val predicted = withContext(Dispatchers.Default) {
                    val startTime = Instant.now()
                    val matchedWithTles = matched.mapNotNull { match ->
                        val tle = satnogsRepository.getTle(match.satellite.noradId)
                        if (tle != null) Pair(match, tle) else null
                    }
                    tleBySatId.clear()
                    transmitterBySatId.clear()
                    for ((m, t) in matchedWithTles) {
                        tleBySatId[m.satellite.id] = t
                        transmitterBySatId[m.satellite.id] = m.transmitter
                    }
                    val total = matchedWithTles.size
                    withContext(Dispatchers.Main) {
                        setStage("Propagating orbits - 0/$total satellites", current = 0, total = total)
                    }

                    val cores = Runtime.getRuntime().availableProcessors()
                        .coerceIn(2, MAX_PROPAGATION_PARALLELISM)
                    val gate = Semaphore(cores)
                    val done = AtomicInteger(0)
                    val skipped = AtomicInteger(0)

                    val results = coroutineScope {
                        matchedWithTles.map { (match, tle) ->
                            async {
                                gate.withPermit {
                                    val cachedObs = try {
                                        satnogsRepository.getObservations(match.satellite.id, forceRemote = false)
                                    } catch (e: Exception) {
                                        emptyList()
                                    }

                                    val future = propagationExecutor.submit(Callable {
                                        propagator.predictPasses(
                                            satellite = match.satellite,
                                            tle = tle,
                                            station = station,
                                            startTime = startTime,
                                            durationHours = 24L,
                                            minElevationDegrees = settings.minElevation,
                                            transmitter = match.transmitter,
                                            status = pl.put.observationcompanion.domain.model.SatelliteStatus.NO_DATA
                                        )
                                    })
                                    val rawPasses = try {
                                        future.get(PROPAGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                    } catch (e: TimeoutException) {
                                        future.cancel(true)
                                        Log.w(TAG, "Propagation timed out for ${match.satellite.name} (${match.satellite.noradId})")
                                        skipped.incrementAndGet()
                                        emptyList()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Propagation failed for ${match.satellite.name}", e)
                                        skipped.incrementAndGet()
                                        emptyList()
                                    }

                                    val decorated = rawPasses.map { pass ->
                                        val rxScore = receptionProbabilityEvaluator.execute(pass.maxElevation, cachedObs)
                                        pass.copy(
                                            status = rxScore.status,
                                            receptionProbability = rxScore.probability,
                                            observationGoodCount = rxScore.goodCount,
                                            observationTotalCount = rxScore.totalCount,
                                            satelliteHasDecoder = match.satellite.hasDecoder
                                        )
                                    }

                                    val d = done.incrementAndGet()
                                    if (d == total || d % PROGRESS_UI_INTERVAL == 0) {
                                        withContext(Dispatchers.Main) {
                                            _loadingProgress.value = _loadingProgress.value?.copy(
                                                stage = "Propagating orbits - $d/$total (${match.satellite.name})",
                                                current = d,
                                                total = total
                                            )
                                        }
                                    }
                                    decorated
                                }
                            }
                        }.awaitAll()
                    }

                    val flat = results.flatten()
                    val skippedCount = skipped.get()
                    withContext(Dispatchers.Main) {
                        _skippedCount.value = skippedCount
                        if (skippedCount > 0) {
                            setStage("Skipped $skippedCount unreachable / slow satellite(s).")
                        }
                    }
                    flat.sortedBy { it.aos }
                }

                setStage("Sorting ${predicted.size} passes…")
                _allPasses.value = predicted
                _loadingProgress.value = null
                resetPaging()

                withContext(Dispatchers.IO) {
                    try {
                        val now = Instant.now().toEpochMilli()
                        passCacheDao.clearAll()
                        passCacheDao.insertAll(predicted.map { it.toEntity(now) })
                    } catch (e: Exception) {
                        Log.w(TAG, "Pass cache write failed", e)
                    }
                }

                _statusNotice.value = "Updated just now"
                viewModelScope.launch {
                    kotlinx.coroutines.delay(STATUS_NOTICE_LINGER_MS)
                    if (_statusNotice.value == "Updated just now") {
                        _statusNotice.value = null
                    }
                }

                ensureHistoryForVisible()

            } catch (e: Exception) {
                Log.e(TAG, "Failed calculating passes", e)
                _error.value = "Failed propagating orbits: ${e.localizedMessage}"
                _loadingProgress.value = null
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun ensureHistoryForVisible() {
        val passes = _allPasses.value
        if (passes.isEmpty()) return
        val sorted = sortPasses(passes, _sortMode.value)
        val visible = sorted.take(_visibleCount.value)
        val newSatIds = visible.map { it.satelliteId }
            .distinct()
            .filter { it.isNotEmpty() && it !in historyLoadedSats }

        if (newSatIds.isEmpty()) return

        historyJob?.cancel()
        historyJob = viewModelScope.launch(Dispatchers.IO) {
            // SatNOGS Network has an aggressive rate-limit, so we space out the requests.
            for (satId in newSatIds) {
                val obs = try {
                    satnogsRepository.getObservations(satId, forceRemote = true)
                } catch (e: Exception) {
                    Log.w(TAG, "History fetch failed for $satId", e)
                    emptyList()
                }
                applyObservationsToPasses(satId, obs)
                historyLoadedSats.add(satId)
                kotlinx.coroutines.delay(INTER_SAT_DELAY_MS)
            }
        }
    }

    private fun applyObservationsToPasses(satelliteId: String, obs: List<pl.put.observationcompanion.domain.model.Observation>) {
        val current = _allPasses.value
        if (current.none { it.satelliteId == satelliteId }) return

        val updated = current.map { pass ->
            if (pass.satelliteId != satelliteId) {
                pass
            } else {
                val rx = receptionProbabilityEvaluator.execute(pass.maxElevation, obs)
                pass.copy(
                    receptionProbability = rx.probability,
                    observationGoodCount = rx.goodCount,
                    observationTotalCount = rx.totalCount,
                    status = rx.status
                )
            }
        }
        _allPasses.value = updated
    }

    companion object {
        fun passKey(pass: Pass): String = "${pass.satelliteId}-${pass.aos.epochSecond}"

        private const val PAGE_SIZE = 10
        private const val INTER_SAT_DELAY_MS: Long = 700L
        private const val PROGRESS_UI_INTERVAL = 5
        private const val LOG_TAIL_SIZE = 6
        private const val MAX_PROPAGATION_PARALLELISM = 8
        private const val PROPAGATION_TIMEOUT_MS = 3_000L
        private const val STATUS_NOTICE_LINGER_MS = 4_000L
    }
}

@Suppress("UNCHECKED_CAST")
class PassesViewModelFactory(
    private val appContainer: pl.put.observationcompanion.di.AppContainer
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PassesViewModel(
            appContainer.satnogsRepository,
            appContainer.settingsRepository,
            appContainer.satPropagator,
            appContainer.evaluateReceptionProbabilityUseCase,
            appContainer.filterSatellitesByBandUseCase,
            appContainer.database.passCacheDao()
        ) as T
    }
}
