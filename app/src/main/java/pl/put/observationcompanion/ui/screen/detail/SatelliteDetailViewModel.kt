package pl.put.observationcompanion.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import pl.put.observationcompanion.di.AppContainer
import pl.put.observationcompanion.domain.model.DopplerPoint
import pl.put.observationcompanion.domain.model.GroundTrackPoint
import pl.put.observationcompanion.domain.model.Observation
import pl.put.observationcompanion.domain.model.Pass
import pl.put.observationcompanion.domain.model.Satellite
import pl.put.observationcompanion.domain.model.SkyPoint
import pl.put.observationcompanion.domain.model.Tle
import pl.put.observationcompanion.domain.model.Transmitter
import pl.put.observationcompanion.domain.orbit.SatPropagator
import pl.put.observationcompanion.domain.repository.SatnogsRepository
import pl.put.observationcompanion.domain.repository.SettingsRepository
import pl.put.observationcompanion.domain.usecase.EvaluateReceptionProbabilityUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class SatelliteDetail(
    val pass: Pass,
    val satellite: Satellite?,
    val tle: Tle?,
    val transmitters: List<Transmitter> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val doppler: List<DopplerPoint> = emptyList(),
    val sky: List<SkyPoint> = emptyList(),
    val groundTrack: List<GroundTrackPoint> = emptyList(),
    val previousPassTrack: List<GroundTrackPoint> = emptyList(),
    val nextPassTrack: List<GroundTrackPoint> = emptyList(),
    val fullOrbitTrack: List<GroundTrackPoint> = emptyList(),
    val observationsLoaded: Boolean = false,
    val observerLat: Double = 0.0,
    val observerLon: Double = 0.0,
    val loading: Boolean = true,
    val refreshingTle: Boolean = false,
    val tleNotice: String? = null
)

class SatelliteDetailViewModel(
    private val satnogsRepository: SatnogsRepository,
    private val settingsRepository: SettingsRepository,
    private val propagator: SatPropagator,
    private val evaluateReceptionProbability: EvaluateReceptionProbabilityUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<SatelliteDetail?>(null)
    val state: StateFlow<SatelliteDetail?> = _state.asStateFlow()

    private val _liveSatPosition = MutableStateFlow<GroundTrackPoint?>(null)
    val liveSatPosition: StateFlow<GroundTrackPoint?> = _liveSatPosition.asStateFlow()

    private var station: SatPropagator.GroundStation? = null
    private var liveJob: Job? = null

    fun load(pass: Pass) {
        _liveSatPosition.value = null
        _state.value = SatelliteDetail(pass = pass, satellite = null, tle = null, loading = true)
        viewModelScope.launch {
            val settings = settingsRepository.getUserSettings()
            val station = SatPropagator.GroundStation(
                latDegrees = settings.groundLat,
                lonDegrees = settings.groundLon,
                altMeters = settings.groundAlt
            )
            this@SatelliteDetailViewModel.station = station

            val satellite = withContext(Dispatchers.IO) {
                satnogsRepository.getSatellite(pass.satelliteId)
            }
            val transmitters = withContext(Dispatchers.IO) {
                satnogsRepository.getTransmittersForSatellite(pass.satelliteId)
                    .sortedWith(compareByDescending<Transmitter> { it.isActive }.thenBy { it.frequency })
            }
            val tle = withContext(Dispatchers.IO) { satnogsRepository.getTle(pass.noradId) }

            // Emit the cheap parts first so the screen paints, then fill in the
            // propagated geometry and the network-backed observation history.
            _state.value = _state.value?.copy(
                satellite = satellite,
                tle = tle,
                transmitters = transmitters,
                observerLat = settings.groundLat,
                observerLon = settings.groundLon,
                loading = false
            )

            if (tle != null) {
                val geometry = withContext(Dispatchers.Default) {
                    val doppler = propagator.buildDopplerCurveFor(
                        tle = tle,
                        satelliteName = pass.satelliteName,
                        station = station,
                        transmitter = pass.matchedTransmitter,
                        aos = pass.aos,
                        los = pass.los
                    )
                    val sky = propagator.buildSkyArcFor(
                        tle = tle,
                        satelliteName = pass.satelliteName,
                        station = station,
                        aos = pass.aos,
                        los = pass.los
                    )
                    val selectedTrack = buildArcTrack(tle, pass.satelliteName, station, pass.aos, pass.los)
                    val (prevWindow, nextWindow) = propagator.findAdjacentPassWindows(
                        tle = tle,
                        satelliteName = pass.satelliteName,
                        station = station,
                        referenceAos = pass.aos
                    )
                    val prevTrack = prevWindow?.let {
                        buildArcTrack(tle, pass.satelliteName, station, it.aos, it.los)
                    } ?: emptyList()
                    val nextTrack = nextWindow?.let {
                        buildArcTrack(tle, pass.satelliteName, station, it.aos, it.los)
                    } ?: emptyList()
                    val fullOrbit = propagator.buildGroundTrack(
                        tle = tle,
                        satelliteName = pass.satelliteName,
                        station = station,
                        startTime = Instant.now(),
                        // Hard cap: one LEO revolution. Without this, satellites
                        // whose parsed mean motion lands near the clamp upper
                        // bound (LEO ~95 min, clamp 110) would still produce a
                        // smear of 2-3 parallel sinusoids on the map.
                        durationMinutes = 95L
                    )
                    GeometryBundle(doppler, sky, selectedTrack, prevTrack, nextTrack, fullOrbit)
                }
                _state.value = _state.value?.copy(
                    doppler = geometry.doppler,
                    sky = geometry.sky,
                    groundTrack = geometry.selectedTrack,
                    previousPassTrack = geometry.previousTrack,
                    nextPassTrack = geometry.nextTrack,
                    fullOrbitTrack = geometry.fullOrbit
                )
            }

            val obs = withContext(Dispatchers.IO) {
                try {
                    satnogsRepository.getObservations(pass.satelliteId, forceRemote = true)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            // Re-score the pass against freshly fetched observations - the
            // cached values (RX %, good/total) were computed during the last
            // bulk propagation and can be stale, e.g. "0% no hist." right
            // after a destructive DB migration even though the table now
            // shows good observations.
            val sortedObs = obs.sortedByDescending { it.timestamp }
            val rx = evaluateReceptionProbability.execute(pass.maxElevation, sortedObs)
            val currentPass = _state.value?.pass ?: pass
            val rescoredPass = currentPass.copy(
                receptionProbability = rx.probability,
                observationGoodCount = rx.goodCount,
                observationTotalCount = rx.totalCount,
                status = rx.status
            )
            _state.value = _state.value?.copy(
                pass = rescoredPass,
                observations = sortedObs,
                observationsLoaded = true
            )
        }
    }

    fun refreshTleFromCelestrak() {
        val current = _state.value ?: return
        val norad = current.pass.noradId
        _state.value = current.copy(refreshingTle = true, tleNotice = null)
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                satnogsRepository.fetchTleFromCelestrak(norad)
            }
            if (fresh == null) {
                _state.value = _state.value?.copy(refreshingTle = false, tleNotice = "Celestrak: no TLE for NORAD $norad")
                return@launch
            }
            val pass = current.pass
            val settings = settingsRepository.getUserSettings()
            val station = SatPropagator.GroundStation(settings.groundLat, settings.groundLon, settings.groundAlt)
            val rebuilt = withContext(Dispatchers.Default) {
                val selected = buildArcTrack(fresh, pass.satelliteName, station, pass.aos, pass.los)
                val (prevW, nextW) = propagator.findAdjacentPassWindows(
                    tle = fresh,
                    satelliteName = pass.satelliteName,
                    station = station,
                    referenceAos = pass.aos
                )
                val prev = prevW?.let { buildArcTrack(fresh, pass.satelliteName, station, it.aos, it.los) } ?: emptyList()
                val next = nextW?.let { buildArcTrack(fresh, pass.satelliteName, station, it.aos, it.los) } ?: emptyList()
                val full = propagator.buildGroundTrack(
                    tle = fresh,
                    satelliteName = pass.satelliteName,
                    station = station,
                    startTime = Instant.now(),
                    durationMinutes = 95L
                )
                CelestrakRebuild(selected, prev, next, full)
            }
            _state.value = _state.value?.copy(
                tle = fresh,
                groundTrack = rebuilt.selected,
                previousPassTrack = rebuilt.previous,
                nextPassTrack = rebuilt.next,
                fullOrbitTrack = rebuilt.full,
                refreshingTle = false,
                tleNotice = "TLE updated from Celestrak"
            )
        }
    }

    private data class CelestrakRebuild(
        val selected: List<GroundTrackPoint>,
        val previous: List<GroundTrackPoint>,
        val next: List<GroundTrackPoint>,
        val full: List<GroundTrackPoint>
    )

    private fun buildArcTrack(
        tle: Tle,
        satelliteName: String,
        station: SatPropagator.GroundStation,
        aos: Instant,
        los: Instant
    ): List<GroundTrackPoint> {
        val durationSec = (los.epochSecond - aos.epochSecond).coerceAtLeast(60L)
        // 20-second steps so even a short ~5-minute pass gets ~15 samples; bumps
        // up the polyline density enough that the arc isn't visibly chunky.
        val stepSec = 20L
        val durationMin = (durationSec + 59L) / 60L
        return propagator.buildGroundTrack(
            tle = tle,
            satelliteName = satelliteName,
            station = station,
            startTime = aos,
            durationMinutes = durationMin,
            stepSeconds = stepSec
        )
    }

    private data class GeometryBundle(
        val doppler: List<DopplerPoint>,
        val sky: List<SkyPoint>,
        val selectedTrack: List<GroundTrackPoint>,
        val previousTrack: List<GroundTrackPoint>,
        val nextTrack: List<GroundTrackPoint>,
        val fullOrbit: List<GroundTrackPoint>
    )

    fun startLiveTracking() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val current = _state.value
                val tle = current?.tle
                val st = station
                if (tle != null && st != null) {
                    _liveSatPosition.value = propagator.subSatellitePoint(
                        tle = tle,
                        satelliteName = current.pass.satelliteName,
                        station = st,
                        at = Instant.now()
                    )
                }
                delay(LIVE_UPDATE_MS)
            }
        }
    }

    fun stopLiveTracking() {
        liveJob?.cancel()
        liveJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveTracking()
    }

    companion object {
        private const val LIVE_UPDATE_MS = 2_000L
    }
}

@Suppress("UNCHECKED_CAST")
class SatelliteDetailViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SatelliteDetailViewModel(
            container.satnogsRepository,
            container.settingsRepository,
            container.satPropagator,
            container.evaluateReceptionProbabilityUseCase
        ) as T
    }
}
