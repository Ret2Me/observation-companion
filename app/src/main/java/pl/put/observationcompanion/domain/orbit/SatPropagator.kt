package pl.put.observationcompanion.domain.orbit

import pl.put.observationcompanion.domain.model.DopplerPoint
import pl.put.observationcompanion.domain.model.GroundTrackPoint
import pl.put.observationcompanion.domain.model.Pass
import pl.put.observationcompanion.domain.model.Satellite
import pl.put.observationcompanion.domain.model.SatelliteStatus
import pl.put.observationcompanion.domain.model.SkyPoint
import pl.put.observationcompanion.domain.model.Tle
import pl.put.observationcompanion.domain.model.Transmitter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.PassPredictor
import uk.me.g4dpz.satellite.SatPassTime
import uk.me.g4dpz.satellite.Satellite as P4JSatellite
import uk.me.g4dpz.satellite.SatelliteFactory
import uk.me.g4dpz.satellite.TLE as P4JTLE
import java.time.Instant
import java.util.Date

// Wrapper around predict4java (SGP4/SDP4). predict4java uses radians + Date,
// rest of the app uses degrees + Instant.
class SatPropagator {

    data class GroundStation(
        val latDegrees: Double,
        val lonDegrees: Double,
        val altMeters: Double
    )

    fun predictPasses(
        satellite: Satellite,
        tle: Tle,
        station: GroundStation,
        startTime: Instant,
        durationHours: Long,
        minElevationDegrees: Double,
        transmitter: Transmitter? = null,
        status: SatelliteStatus = SatelliteStatus.NEUTRAL
    ): List<Pass> {
        val p4jTle = try {
            P4JTLE(arrayOf(satellite.name.ifBlank { "SAT" }, tle.line1, tle.line2))
        } catch (e: Exception) {
            return emptyList()
        }
        val qth = GroundStationPosition(
            station.latDegrees,
            station.lonDegrees,
            station.altMeters
        )
        val sat: P4JSatellite = try {
            SatelliteFactory.createSatellite(p4jTle)
        } catch (e: Exception) {
            return emptyList()
        }
        // willBeSeen: predict4java loops on orbits not visible from QTH.
        try {
            if (!sat.willBeSeen(qth)) return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        // Geometric cap: skip when max possible elevation < user threshold.
        val orbitGeometry = parseInclMeanMo(tle.line2)
        if (orbitGeometry != null) {
            val (inclDeg, meanMotion) = orbitGeometry
            val maxPossibleEl = maxPossibleElevationDeg(
                inclinationDeg = inclDeg,
                meanMotionRevPerDay = meanMotion,
                observerLatDeg = station.latDegrees
            )
            if (maxPossibleEl < minElevationDegrees) return emptyList()
        }
        val predictor = try {
            PassPredictor(p4jTle, qth)
        } catch (e: Exception) {
            return emptyList()
        }
        val rawPasses: List<SatPassTime> = try {
            predictor.getPasses(Date.from(startTime), durationHours.toInt(), false)
        } catch (e: Exception) {
            return emptyList()
        }

        return rawPasses
            .filter { it.maxEl >= minElevationDegrees }
            .filter { it.endTime.toInstant().isAfter(Instant.now()) }
            .map { spt ->
                val aos = spt.startTime.toInstant()
                val los = spt.endTime.toInstant()
                val tca = spt.tca?.toInstant() ?: midpoint(aos, los)

                // p4j returns az as int; recompute from SatPos for double precision.
                val startAz = lookAzimuthDeg(sat, qth, aos) ?: spt.aosAzimuth.toDouble()
                val endAz = lookAzimuthDeg(sat, qth, los) ?: spt.losAzimuth.toDouble()
                val tcaAz = lookAzimuthDeg(sat, qth, tca) ?: startAz

                Pass(
                    satelliteId = satellite.id,
                    noradId = satellite.noradId,
                    satelliteName = satellite.name,
                    status = status,
                    aos = aos,
                    tca = tca,
                    los = los,
                    maxElevation = spt.maxEl,
                    startAzimuth = startAz,
                    tcaAzimuth = tcaAz,
                    endAzimuth = endAz,
                    matchedTransmitter = transmitter,
                    dopplerPoints = emptyList(),
                    tleEpoch = tle.epoch
                )
            }
            .sortedBy { it.aos }
    }

    private fun lookAzimuthDeg(
        sat: P4JSatellite,
        qth: GroundStationPosition,
        at: Instant
    ): Double? = try {
        Math.toDegrees(sat.getPosition(qth, Date.from(at)).azimuth)
    } catch (e: Exception) {
        null
    }

    private fun buildDopplerCurve(
        sat: P4JSatellite,
        qth: GroundStationPosition,
        aos: Instant,
        los: Instant,
        fNomHz: Long
    ): List<DopplerPoint> {
        val durSec = los.epochSecond - aos.epochSecond
        if (durSec <= 0) return emptyList()
        val points = 30
        val out = mutableListOf<DopplerPoint>()
        for (i in 0..points) {
            val t = aos.plusMillis(i.toLong() * durSec * 1000L / points)
            val pos = try {
                sat.getPosition(qth, Date.from(t))
            } catch (e: Exception) {
                continue
            }
            // offset = f_nom - f_obs = f_nom * rangeRate / c
            val offsetHz = fNomHz * (pos.rangeRate / SPEED_OF_LIGHT_KM_S)
            out.add(DopplerPoint(t, offsetHz))
        }
        return out
    }

    private fun midpoint(a: Instant, b: Instant): Instant =
        Instant.ofEpochMilli((a.toEpochMilli() + b.toEpochMilli()) / 2)

    fun buildDopplerCurveFor(
        tle: Tle,
        satelliteName: String,
        station: GroundStation,
        transmitter: Transmitter?,
        aos: Instant,
        los: Instant
    ): List<DopplerPoint> {
        if (transmitter == null) return emptyList()
        return try {
            val p4jTle = P4JTLE(arrayOf(satelliteName.ifBlank { "SAT" }, tle.line1, tle.line2))
            val qth = GroundStationPosition(station.latDegrees, station.lonDegrees, station.altMeters)
            val sat = SatelliteFactory.createSatellite(p4jTle)
            buildDopplerCurve(sat, qth, aos, los, transmitter.frequency)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun buildSkyArcFor(
        tle: Tle,
        satelliteName: String,
        station: GroundStation,
        aos: Instant,
        los: Instant
    ): List<SkyPoint> {
        return try {
            val p4jTle = P4JTLE(arrayOf(satelliteName.ifBlank { "SAT" }, tle.line1, tle.line2))
            val qth = GroundStationPosition(station.latDegrees, station.lonDegrees, station.altMeters)
            val sat = SatelliteFactory.createSatellite(p4jTle)
            val durSec = los.epochSecond - aos.epochSecond
            if (durSec <= 0) return emptyList()
            // Denser sampling matters most for high-elevation passes - azimuth
            // can swing 100 deg+ in seconds near zenith, and a 20-sample polyline
            // cuts a chord across the chart instead of arcing around.
            val samples = 80
            val out = mutableListOf<SkyPoint>()
            for (i in 0..samples) {
                val t = aos.plusMillis(i.toLong() * durSec * 1000L / samples)
                val pos = try {
                    sat.getPosition(qth, Date.from(t))
                } catch (e: Exception) {
                    continue
                }
                out.add(
                    SkyPoint(
                        azimuth = Math.toDegrees(pos.azimuth),
                        elevation = Math.toDegrees(pos.elevation),
                        time = t
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Sub-satellite ground track. Defaults to one full orbital period (derived
    // from the TLE mean motion) sampled every [stepSeconds], which draws a clean
    // single-orbit trace on the map. The walk stops early once we detect a
    // full orbit has been completed (latitude returns to the starting value
    // moving in the same direction) - this protects us against TLEs whose
    // SGP4 atmospheric-drag model has effectively decayed the satellite into a
    // much faster orbit than the line-2 mean motion suggests (stale TLEs of
    // decayed satellites would otherwise produce several overlapping ground
    // tracks even with a tight time cap).
    fun buildGroundTrack(
        tle: Tle,
        satelliteName: String,
        station: GroundStation,
        startTime: Instant,
        durationMinutes: Long = 0L,
        stepSeconds: Long = 30L
    ): List<GroundTrackPoint> {
        return try {
            val p4jTle = P4JTLE(arrayOf(satelliteName.ifBlank { "SAT" }, tle.line1, tle.line2))
            val qth = GroundStationPosition(station.latDegrees, station.lonDegrees, station.altMeters)
            val sat = SatelliteFactory.createSatellite(p4jTle)

            // Clamp mean-motion-derived period to a sane LEO range; see
            // commit history for the cases this guards against.
            val periodMin = (parseInclMeanMo(tle.line2)
                ?.let { (_, meanMotion) -> if (meanMotion > 0.0) 1440.0 / meanMotion else 95.0 }
                ?: 95.0).coerceIn(40.0, 110.0)
            val totalSec = (if (durationMinutes > 0L) durationMinutes.toDouble() else periodMin) * 60.0
            val samples = (totalSec / stepSeconds).toInt().coerceIn(2, 600)

            val out = ArrayList<GroundTrackPoint>(samples + 1)
            // Orbit-completion tracking: count zero-crossings of (lat - startLat).
            // A satellite returns to its starting latitude twice per orbit
            // (descending through it, then ascending through it again). After
            // 2 crossings one full revolution has elapsed - stop sampling.
            var startLat = Double.NaN
            var prevDiffSign = 0
            var crossings = 0

            for (i in 0..samples) {
                val t = startTime.plusSeconds(i.toLong() * stepSeconds)
                val pos = try {
                    sat.getPosition(qth, Date.from(t))
                } catch (e: Exception) {
                    continue
                }
                val latDeg = Math.toDegrees(pos.latitude)
                // predict4java longitude is [0, 2pi); fold to [-180, 180].
                val lonDeg = ((Math.toDegrees(pos.longitude) + 540.0) % 360.0) - 180.0
                out.add(GroundTrackPoint(latDeg, lonDeg, t))

                if (startLat.isNaN()) {
                    startLat = latDeg
                } else if (out.size > MIN_SAMPLES_BEFORE_ORBIT_DETECT) {
                    val diff = latDeg - startLat
                    val sign = if (diff > 0.0) 1 else if (diff < 0.0) -1 else 0
                    if (prevDiffSign != 0 && sign != 0 && sign != prevDiffSign) {
                        crossings++
                        if (crossings >= 2) break
                    }
                    if (sign != 0) prevDiffSign = sign
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class PassWindow(val aos: Instant, val los: Instant)

    // Pass windows immediately before and after [referenceAos] for the same
    // satellite, ignoring the user-set elevation minimum (we want the *adjacent*
    // pass, not the next pass that happens to clear some threshold).
    fun findAdjacentPassWindows(
        tle: Tle,
        satelliteName: String,
        station: GroundStation,
        referenceAos: Instant
    ): Pair<PassWindow?, PassWindow?> {
        return try {
            val p4jTle = P4JTLE(arrayOf(satelliteName.ifBlank { "SAT" }, tle.line1, tle.line2))
            val qth = GroundStationPosition(station.latDegrees, station.lonDegrees, station.altMeters)
            val predictor = PassPredictor(p4jTle, qth)
            // ~48 h window centred on the reference AOS - even slow LEO with
            // ~2 passes/day has several inside this range; HEO sats get at
            // least one neighbour.
            val searchStart = Date.from(referenceAos.minusSeconds(24 * 3600L))
            val raw: List<SatPassTime> = predictor.getPasses(searchStart, 48, false)
            val windows = raw.map { PassWindow(it.startTime.toInstant(), it.endTime.toInstant()) }
                .sortedBy { it.aos }
            if (windows.isEmpty()) return Pair(null, null)
            val refMillis = referenceAos.toEpochMilli()
            // The cached AOS may differ from a freshly-propagated one because
            // the TLE was refreshed in the meantime. Pick the *closest* pass
            // (not a strict equality) and call its neighbours prev/next.
            val selectedIdx = windows.indices.minByOrNull {
                kotlin.math.abs(windows[it].aos.toEpochMilli() - refMillis)
            } ?: return Pair(null, null)
            val prev = windows.getOrNull(selectedIdx - 1)
            val next = windows.getOrNull(selectedIdx + 1)
            Pair(prev, next)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    // Sub-satellite point at a single instant (for the live "now" marker).
    fun subSatellitePoint(
        tle: Tle,
        satelliteName: String,
        station: GroundStation,
        at: Instant
    ): GroundTrackPoint? {
        return try {
            val p4jTle = P4JTLE(arrayOf(satelliteName.ifBlank { "SAT" }, tle.line1, tle.line2))
            val qth = GroundStationPosition(station.latDegrees, station.lonDegrees, station.altMeters)
            val sat = SatelliteFactory.createSatellite(p4jTle)
            val pos = sat.getPosition(qth, Date.from(at))
            val latDeg = Math.toDegrees(pos.latitude)
            val lonDeg = ((Math.toDegrees(pos.longitude) + 540.0) % 360.0) - 180.0
            GroundTrackPoint(latDeg, lonDeg, at)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // Minimum number of samples before we trust the lat-crossing detector.
        // Without this, a satellite that starts the track right next to its
        // starting latitude could falsely register a "completed orbit" within
        // the first few seconds of sampling.
        private const val MIN_SAMPLES_BEFORE_ORBIT_DETECT = 8

        private const val SPEED_OF_LIGHT_KM_S = 299_792.458
        private const val MU_KM3_S2 = 398_600.4418
        private const val EARTH_RADIUS_KM = 6378.137

        // TLE line 2: inclination chars 8..16, mean motion chars 52..63.
        internal fun parseInclMeanMo(line2: String): Pair<Double, Double>? {
            if (line2.length < 63) return null
            return try {
                val incl = line2.substring(8, 16).trim().toDouble()
                val meanMotion = line2.substring(52, 63).trim().toDouble()
                Pair(incl, meanMotion)
            } catch (_: Exception) {
                null
            }
        }

        // Max possible elevation for orbit (i, n) seen from observer latitude.
        // d_min = max(0, |phi| - i_eff); E from the slant triangle.
        fun maxPossibleElevationDeg(
            inclinationDeg: Double,
            meanMotionRevPerDay: Double,
            observerLatDeg: Double
        ): Double {
            if (meanMotionRevPerDay <= 0.0) return 0.0
            val coverageLat = if (inclinationDeg <= 90.0) inclinationDeg else 180.0 - inclinationDeg
            val phi = abs(observerLatDeg)
            val dMinDeg = max(0.0, phi - coverageLat)
            if (dMinDeg <= 0.0) return 90.0

            val nRadPerSec = meanMotionRevPerDay * 2.0 * PI / 86_400.0
            val a = (MU_KM3_S2 / (nRadPerSec * nRadPerSec)).pow(1.0 / 3.0)
            val r = a
            val d = Math.toRadians(dMinDeg)
            val slant = sqrt(EARTH_RADIUS_KM * EARTH_RADIUS_KM + r * r - 2.0 * EARTH_RADIUS_KM * r * cos(d))
            if (slant <= 0.0) return 0.0
            val sinE = ((r * cos(d) - EARTH_RADIUS_KM) / slant).coerceIn(-1.0, 1.0)
            return Math.toDegrees(asin(sinE))
        }
    }
}
