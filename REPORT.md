# Observation Companion - Technical Report

**Course:** Mobile Application Programming (PUT, summer semester 2025/2026)
**Author:** Filip Poplewski
**Document type:** lab assignment report (list-detail Android application)
**Last revision:** 2026-05-28

> Throughout this document Markdown is mixed with inline LaTeX (`$...$`) and
> display LaTeX (`$$...$$`) for mathematical notation. The file renders cleanly
> in any modern Markdown viewer that supports KaTeX/MathJax (GitHub, Obsidian,
> Pandoc, VS Code with the Markdown+Math extension).

---

## Table of Contents

1. [Introduction and motivation](#1-introduction-and-motivation)
2. [Functional overview](#2-functional-overview)
3. [Architecture](#3-architecture)
4. [Technology stack](#4-technology-stack)
5. [Domain model](#5-domain-model)
6. [Two Line Element sets (TLE)](#6-two-line-element-sets-tle)
7. [Orbital propagation - SGP4](#7-orbital-propagation--sgp4)
8. [Pass prediction (AOS / TCA / LOS)](#8-pass-prediction-aos--tca--los)
9. [Geometric maximum-elevation pre-filter](#9-geometric-maximum-elevation-pre-filter)
10. [Doppler curve](#10-doppler-curve)
11. [Reception probability heuristic](#11-reception-probability-heuristic)
11a. [Ground-track display and adjacent passes](#11a-ground-track-display-and-adjacent-passes)
11b. [SatDump pipeline detection](#11b-satdump-pipeline-detection)
12. [Antenna-band / transmitter cross-matching](#12-antenna-band--transmitter-cross-matching)
13. [Application data flow](#13-application-data-flow)
14. [Networking layer](#14-networking-layer)
15. [Persistence layer (Room + DataStore)](#15-persistence-layer-room--datastore)
16. [Background work and alarms](#16-background-work-and-alarms)
17. [Concurrency model and parallelism](#17-concurrency-model-and-parallelism)
18. [Optimisation catalogue](#18-optimisation-catalogue)
19. [Testing strategy](#19-testing-strategy)
20. [External sources, APIs and licences](#20-external-sources-apis-and-licences)
21. [Limitations and future work](#21-limitations-and-future-work)
22. [Glossary of symbols](#22-glossary-of-symbols)

---

## 1. Introduction and motivation

The application is a **mobile companion for radio amateurs** that interacts with
the [SatNOGS](https://satnogs.org/) ecosystem - an open, community operated
network of automated ground stations that decode satellite telemetry. The
companion is not a ground-station controller; it is an *advisory* tool used in
the field. Given the user's

- **location** (latitude $\varphi$, longitude $\lambda$, altitude $h$),
- **antenna pass-band** (one or more of: VHF, UHF, L-, S-, C-, X-band),
- **minimum elevation threshold** $E_{\min}$ and **alarm lead time** $\Delta t_{\text{lead}}$,

it lists, for the next 24 hours, satellites that

1. carry an active transmitter inside one of the chosen pass-bands, **and**
2. produce a pass with maximum elevation $E_{\max} \ge E_{\min}$ at that
   location.

For every qualifying pass the application predicts AOS (Acquisition Of Signal),
TCA (Time of Closest Approach), LOS (Loss Of Signal), the Doppler shift curve
of the matched transmitter, the sky arc (azimuth x elevation) and a reception
probability that combines orbital geometry with the satellite's historical
SatNOGS observation success rate. The user can arm an exact alarm a configurable
number of minutes before AOS.

The course requirement is the *list-detail* navigation pattern. The Passes
screen is the **list**; tapping a `PassCard` navigates to the
`SatelliteDetail` screen - the **detail** view - which fills the same
`Activity` through Compose Navigation (no second `Activity`).

The full project requirements were defined internally by put.poznan.pl.

---

## 2. Functional overview

### 2.1 Screens

| # | Screen | Purpose |
|---|--------|---------|
| 1 | **Passes** (start destination) | Sorted list of upcoming passes with pull-to-refresh, paging (10 + load more), sort modes, multi-select satellite name filter, compact / detailed toggle. Tapping a card opens the detail screen. |
| 2 | **Satellite detail** | Full geometry view: AOS/MAX EL/LOS clock, sky-view polar plot, Doppler curve, OSM ground track with selected pass + previous + next + full current orbit + live sub-satellite marker, transmitter list, recent observations table with ground-station name, manual *Refresh TLE (Celestrak)* button. |
| 3 | **Location** | Pick the listening QTH on an OSMDroid map (long-press to relocate) or by GPS, manual coordinate entry, built-in PUT/observatory presets, user-defined location+bands presets. |
| 4 | **Settings** | Antenna pass-bands (multi-select VHF / UHF / L / S / C / X), $E_{\min}$, alarm lead time, two SatNOGS base URLs (DB + Network), manual force-sync. |
| 5 | **Stats** | Sync state, catalog sizes, TLE coverage, per-band transmitter breakdown, observation totals, on-disk DB size. |

### 2.2 List card content (Passes screen)

Each `PassCard` shows:

- satellite name + status chip (`UNLIKELY` / `NEUTRAL` / `PROMISING` / `NO_DATA`),
- NORAD ID + AOS date,
- stale-TLE warning banner when the underlying TLE epoch is older than 14 days,
- AOS / TCA / LOS timestamps in the user's local zone with start / TCA / end azimuths,
- maximum elevation $E_{\max}$,
- reception-probability chip (numeric % + good/total counts),
- the matched transmitter (modulation + frequency in MHz),
- two decoder badges - "SatNOGS decoder" (from the satellite's `telemetries[]`
  in the SatNOGS DB) and "SatDump pipeline" (from a name-substring match
  against the SatDump pipeline directory; see Section 11a),
- a per-pass *alarm* toggle.

The card is no longer expandable. Doppler, sky-view, and ground-track
visualisations live on the dedicated detail screen, which gets opened by
tapping any pass card.

A compact mode (toggled from the top app bar) replaces the detailed card with
a two-line summary (name + AOS time + max elevation), useful when scanning a
large list.

### 2.3 Sort modes

The list can be ordered by:

1. AOS (earliest first) - operational default,
2. maximum elevation (highest first),
3. reception probability (most promising first),
4. satellite name (lex.).

### 2.3a Filter modes

Below the sort chips a multi-select filter lets the user restrict the visible
passes to a chosen set of satellite names. A dialog with search-as-you-type
and a checkbox list shows every distinct satellite name appearing in the
current pass set. The filter is additive to the sort and to the paging:
hidden passes are not paged through.

### 2.4 Algorithms / domain logic - summary

- **Orbital propagation (SGP4):** `predict4java` wrapped by `SatPropagator`.
  All public APIs use **degrees** for angles, **kilometres** for distance,
  `java.time.Instant` for time.
- **Antenna band cross-matching:** `FilterSatellitesByBandUseCase` keeps only
  satellites whose *active* transmitter frequency falls inside the user's
  selected pass-bands.
- **Doppler curve:** `buildDopplerCurveFor` inside `SatPropagator` uses
  $\Delta f = f_{\text{nom}} \cdot v_r / c$ from predict4java's
  range-rate, sampled at 31 points across the pass.
- **Sky arc:** `buildSkyArcFor` samples 81 (azimuth, elevation) points
  between AOS and LOS; the polar plot splits the polyline at every
  below-horizon dip so a brief sub-horizon excursion does not draw a chord
  across the chart.
- **Ground tracks:** `buildGroundTrack` samples sub-satellite positions in a
  configurable window with antimeridian splitting; the detail screen renders
  four layers (selected pass + previous + next pass arcs +
  full current orbit). Adjacent pass windows are discovered through
  `findAdjacentPassWindows`, which queries predict4java's `PassPredictor`
  in a +/-24 h window and picks the AOS closest to the reference pass.
- **Reception probability:** convex combination of an elevation score and a
  historical good/total ratio (Section 11).
- **SatDump pipeline detection:** name-substring match against a list of
  tokens derived from the 88 pipeline JSON files in the SatDump source tree
  (Section 11a).
- **Exact alarms:** `AlarmManager.setExactAndAllowWhileIdle` for second-grade
  precision; `BootReceiver` rearms on reboot if the user enabled
  per-pass alarms.
- **TLE / catalog sync:** `SatnogsSyncWorker` (WorkManager, periodic 24 h,
  requires network); prunes observations older than 7 days. A manual
  Celestrak fallback (`fetchTleFromCelestrak`) is exposed from the detail
  screen for the case when SatNOGS DB does not have a fresh element set.

---

## 3. Architecture

### 3.1 Clean Architecture (3 layers)

```
ui/         <- Compose + ViewModel + StateFlow (MVI-lite)
domain/     <- models + repository interfaces + use cases + orbit/
data/       <- repository impls + Room (local/) + Retrofit (remote/) + preferences/
di/         <- Hand-rolled service locator (AppContainer)
alarm/, worker/, notification/   <- Android components
```

The boundaries are enforced by package visibility and by the dependency rule:
`ui -> domain <- data`. Domain knows nothing about Android, Retrofit, Room or
predict4java. The propagation library is wrapped behind `SatPropagator` so the
domain only sees `Tle`, `Pass`, `DopplerPoint`, `SkyPoint`, `GroundStation`.

### 3.2 Presentation pattern - MVI-lite

ViewModels expose a single `StateFlow<UiState>` per screen (see
`PassesUiState = Loading | Success | Error`). User intents are method calls on
the ViewModel; state transitions are pure functions of incoming events. There
is no second mutable source of truth in Composables - they are 100% driven by
state collected with `collectAsStateWithLifecycle`.

### 3.3 Dependency injection

A minimal hand-rolled service locator (`AppContainer`) is used instead of a
runtime DI framework (Hilt / Koin). Reason: the project has fewer than
~10 graph nodes and only one process. The container is constructed once in
`ObservationCompanionApp.onCreate()` and read by `MainActivity` plus the view-model
factories. Lazy initialisation defers Room/Retrofit instantiation until the
first request.

### 3.4 Module / package layout

```
pl.put.observationcompanion
+-- ObservationCompanionApp.kt              # Application, builds AppContainer
+-- MainActivity.kt            # NavHost + permission gate
+-- ui/
|   +-- screen/{passes,location,settings,stats}/
|   +-- component/             # PassCard, DopplerChart, SkyMapChart, chips
|   `-- theme/                 # Material 3 dynamic colours
+-- domain/
|   +-- model/                 # Satellite, Pass, Tle, Transmitter, ...
|   +-- repository/            # interfaces
|   +-- usecase/               # 3 stateless use cases
|   `-- orbit/SatPropagator.kt # wrapper around predict4java
+-- data/
|   +-- local/{dao,entity}/    # Room 2.x (FTS4 for satellite search)
|   +-- remote/{api,dto,interceptor}/  # Retrofit + DTO + dynamic-host swap
|   +-- mapper/                # DTO -> entity -> domain
|   +-- repository/            # implementations of domain interfaces
|   `-- preferences/           # DataStore preferences
+-- di/AppContainer.kt
+-- alarm/                     # AlarmScheduler + AlarmReceiver + BootReceiver
+-- worker/                    # SatnogsSyncWorker + AlarmRescheduleWorker
`-- notification/              # NotificationHelper + channels
```

### 3.5 Threading model

| Work | Dispatcher |
|------|-----------|
| Compose collectors, UI updates | `Dispatchers.Main` |
| Room reads/writes | `Dispatchers.IO` |
| Retrofit calls | OkHttp's worker pool (suspend) |
| Orbit propagation (SGP4) | `Dispatchers.Default` + a dedicated cached `ExecutorService` named `sat-prop` (daemon threads) |
| WorkManager / AlarmManager handlers | provided by their respective frameworks |

The dedicated cached executor exists because **predict4java loops indefinitely
on certain decayed or invisible orbits**. We wrap each call in
`Future.get(timeout)` so a stuck thread is abandoned, and the cached pool
creates a fresh worker on the next propagation.

---

## 4. Technology stack

| Concern | Choice | Version (target) |
|---------|--------|----------|
| Language | Kotlin (K2 compiler) | 2.0.x |
| Async | Coroutines + Flow | 1.8.x |
| UI | Jetpack Compose + Material 3 | BOM 2024.10.x |
| Navigation | Compose Navigation | 2.x |
| Maps | OSMDroid | 6.x |
| Charts | Custom Compose `Canvas` (`DopplerChart`, `SkyMapChart`) | - |
| HTTP | Retrofit + OkHttp + Moshi | 2.11 / 4.12 / 1.15 |
| Local DB | Room + KSP, FTS4 | 2.6 |
| Preferences | DataStore Preferences | 1.x |
| Orbital math | predict4java (SGP4/SDP4) | 1.1.3 |
| Background | WorkManager 2.9, AlarmManager | platform |
| Notifications | NotificationCompat + channels | platform |
| Location | FusedLocationProviderClient + LocationManager fallback | platform |
| Permissions | Accompanist Permissions | 0.x |
| Image loading | Coil | 2.x |
| DI | Service locator (`AppContainer`) | hand-rolled |
| Logging | `android.util.Log` | platform |
| Build | Gradle 8 + Kotlin DSL + version catalog | 8.x |
| Toolchain | JDK 17 | - |
| Min/Target SDK | 24 / 35 | - |

### 4.1 Why these choices

- **predict4java over orekit-android.** Orekit is the canonical European
  space-mechanics library, but it brings ~10 MB of dependencies and a steep
  start-up cost. predict4java is ~200 KB, focused on SGP4/SDP4, and is the
  engine inside *Gpredict* - the de-facto desktop client for amateur satellite
  tracking. It is sufficient for our 24 h horizon. (See Section 7.)
- **OSMDroid over MapLibre.** No Google Play Services dependency, no API key,
  raster tiles only - adequate for picking a QTH point.
- **Service locator over Hilt.** Smaller binary, fewer plugins, easier to
  reason about in a single-process app of this size.
- **Moshi over kotlinx-serialization.** Better Retrofit converter ergonomics at
  the time of writing; reflective adapter is good enough since DTOs are tiny.

---

## 5. Domain model

The domain layer (`pl.put.observationcompanion.domain.model.Models.kt`) is intentionally small
and immutable:

```kotlin
data class Satellite(
    val id: String, val name: String, val noradId: String,
    val isActive: Boolean = true, val description: String? = null,
    val hasDecoder: Boolean = false, val observationsFetchedAt: Long = 0L
)

data class Tle(
    val noradId: String, val line1: String, val line2: String,
    val lastUpdated: Instant = Instant.now(), val epoch: Instant? = null
)

data class Transmitter(
    val id: String, val satelliteId: String,
    val frequency: Long,        // Hz
    val modulation: String?, val mode: String?, val description: String?,
    val isActive: Boolean = true
)

data class Observation(
    val id: String, val satelliteId: String,
    val status: String,         // "good" | "failed" | "unknown"
    val timestamp: Instant,
    val stationName: String? = null   // SatNOGS Network station_name, fallback "Station #<id>"
)

enum class SatelliteStatus { UNLIKELY, NEUTRAL, PROMISING, NO_DATA }

enum class AntennaBand(val displayName: String, val frequencyRange: LongRange) {
    VHF   ("VHF",     140_000_000L..  150_000_000L),
    UHF   ("UHF",     420_000_000L..  450_000_000L),
    L_BAND("L-Band",1_000_000_000L..2_000_000_000L),
    S_BAND("S-Band",2_000_000_000L..4_000_000_000L),
    C_BAND("C-Band",4_000_000_000L..8_000_000_000L),
    X_BAND("X-Band",8_000_000_000L..12_000_000_000L)
}

data class DopplerPoint(val timestamp: Instant, val frequencyOffset: Double /* Hz */)
data class SkyPoint    (val azimuth: Double /* deg, 0=N */,
                        val elevation: Double /* deg */, val time: Instant)

data class Pass(
    val satelliteId: String, val noradId: String, val satelliteName: String,
    val status: SatelliteStatus,
    val aos: Instant, val tca: Instant, val los: Instant,
    val maxElevation: Double,
    val startAzimuth: Double, val tcaAzimuth: Double, val endAzimuth: Double,
    val matchedTransmitter: Transmitter?,
    val dopplerPoints: List<DopplerPoint> = emptyList(),
    val receptionProbability: Double = 0.0,
    val observationGoodCount: Int = 0,
    val observationTotalCount: Int = 0,
    val satelliteHasDecoder: Boolean = false,
    val tleEpoch: Instant? = null
)
```

### 5.1 Unit conventions

- Angles outside the propagation wrapper are always in **degrees**.
- Distances outside the wrapper are in **kilometres**.
- Times use `java.time.Instant` (UTC); only the UI converts to the user's local
  zone via `ZoneId.systemDefault()`.
- Frequencies are stored as `Long` Hz; offsets as `Double` Hz (Doppler is
  fractional).

---

## 6. Two Line Element sets (TLE)

A **Two Line Element set** is a 69-character-per-line, NORAD-issued, fixed
column orbital state vector. Each TLE references an epoch $t_0$ (year + day of
year + fractional day in UTC) and encodes a mean-element representation of the
orbit. A typical TLE for the International Space Station looks like this:

```
ISS (ZARYA)
1 25544U 98067A   24135.51666667  .00012345  00000-0  22000-3 0  9991
2 25544  51.6402 137.5470 0006240  60.1234 300.9876 15.49000000451234
```

### 6.1 The six classical Keplerian elements visible in a TLE

| Symbol | Meaning | Where (line 2, char range, 0-indexed) |
|--------|---------|--------------------------------------|
| $i$ | inclination (deg) | 8...16 |
| $\Omega$ | RAAN - right ascension of ascending node (deg) | 17...25 |
| $e$ | eccentricity (decimal, leading dot implied) | 26...33 |
| $\omega$ | argument of perigee (deg) | 34...42 |
| $M$ | mean anomaly (deg) | 43...51 |
| $n$ | mean motion (rev/day) | 52...63 |

From mean motion $n$ we recover the semi-major axis from the Kepler equation

$$
a \;=\; \left( \frac{\mu}{(2\pi n / 86400)^2} \right)^{1/3},
\qquad \mu = 398\,600.4418\;\text{km}^3/\text{s}^2 .
$$

We use this exact formula in `SatPropagator.maxPossibleElevationDeg` (Section 9)
without pulling the full predict4java propagator, because we only need the
*semi-major axis*, not a state vector.

### 6.2 Why TLEs degrade over time

A TLE is **mean-element** data, not osculating. The atmosphere, Earth's
oblateness ($J_2, J_3, J_4$ harmonics), solar radiation pressure and lunisolar
perturbations all act on the orbit. Mean elements are the smoothed average that
SGP4 was designed to receive. Empirically:

- LEO at 400 km (ISS): propagation error reaches ~1 km along-track after 24 h,
  several kilometres after 5 days,
- LEO at 800 km: a few hundred metres per day,
- GEO: hundreds of metres per day.

Because of this we (a) refresh TLEs every 24 h and (b) track the **TLE epoch**
$t_0$ in `Pass.tleEpoch` so the UI can warn the user if a prediction was made
from an old TLE.

### 6.3 Parsing in the project

We parse only the two values we need (inclination and mean motion) outside
predict4java, in `SatPropagator.parseInclMeanMo`:

```kotlin
internal fun parseInclMeanMo(line2: String): Pair<Double, Double>? {
    if (line2.length < 63) return null
    return try {
        val incl       = line2.substring(8, 16).trim().toDouble()
        val meanMotion = line2.substring(52, 63).trim().toDouble()
        Pair(incl, meanMotion)
    } catch (_: Exception) { null }
}
```

predict4java itself handles the rest of the format (checksum, Bstar drag term,
element set number, ...).

---

## 7. Orbital propagation - SGP4

### 7.1 What SGP4 actually computes

SGP4 ("Simplified General Perturbations 4") is the analytical propagator
released by NORAD in *Spacetrack Report #3*. Given a TLE and a target time $t$
it returns the satellite's **ECI** (Earth-Centered Inertial, TEME frame)
position and velocity:

$$
\bigl( \mathbf{r}(t),\; \mathbf{v}(t) \bigr) \in \mathbb{R}^3 \times \mathbb{R}^3 .
$$

SGP4 includes secular and short/long-period terms for the dominant Earth
zonal harmonics ($J_2, J_3, J_4$) and a simplified atmospheric drag model.
For perigee altitudes above 5877 km it switches to SDP4 which adds lunisolar
gravity and Earth resonance terms; predict4java handles the switch
transparently.

### 7.2 From ECI to observer-centric look angles

The propagator uses the QTH (`GroundStationPosition` - geodetic
$\varphi, \lambda, h$) and the predicted $\mathbf{r}(t)$ to compute
**topocentric horizontal coordinates** $(A, E, \rho, \dot\rho)$:

- **azimuth** $A \in [0, 2\pi)$, measured clockwise from geographic North,
- **elevation** $E \in [-\pi/2, \pi/2]$, positive above horizon,
- **slant range** $\rho$ (km),
- **range-rate** $\dot\rho$ (km/s, positive when receding).

These are the four numbers we need for visibility and Doppler.

### 7.3 The wrapper - `SatPropagator`

Domain code never touches predict4java types. `SatPropagator` exposes three
operations:

1. `predictPasses(satellite, tle, station, startTime, durationHours, minElev, transmitter, status) -> List<Pass>`
2. `buildDopplerCurveFor(tle, satelliteName, station, transmitter, aos, los) -> List<DopplerPoint>`
3. `buildSkyArcFor(tle, satelliteName, station, aos, los) -> List<SkyPoint>`

The wrapper:

- converts radians <-> degrees and `Instant` <-> `java.util.Date`,
- catches every exception thrown by predict4java and returns an empty list
  instead (the library throws for malformed TLEs and for *some* well-formed
  but non-physical inputs),
- runs `Satellite.willBeSeen(qth)` first - when this returns `false` the
  satellite is not visible from this latitude regardless of timing, so we
  short-circuit and skip the predictor entirely,
- enforces a closed-form **geometric ceiling** (Section 9) before invoking
  `PassPredictor.getPasses(...)`,
- re-derives the azimuth from `SatPos.azimuth` because predict4java's
  `SatPassTime` returns the value as an `int` - fine for navigation, too
  coarse for the polar plot.

### 7.4 Why the timeout is needed

predict4java's pass predictor walks the orbit and refines crossing points by
bisection. For nearly-decayed objects, satellites whose inclination is less
than the observer's latitude minus a small margin, or extremely flat orbits,
the refinement does not converge and the call never returns. We wrap each call:

```kotlin
val future = propagationExecutor.submit(Callable { propagator.predictPasses(...) })
val rawPasses = try {
    future.get(PROPAGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)  // 3 s
} catch (e: TimeoutException) {
    future.cancel(true)
    skipped.incrementAndGet()
    emptyList()
}
```

This caps the worst case at 3 s per satellite and a single `skippedCount` is
reported to the UI ("Skipped N unreachable / slow satellite(s)").

---

## 8. Pass prediction (AOS / TCA / LOS)

### 8.1 Definitions

For a fixed observer at $(\varphi, \lambda, h)$ and a fixed minimum elevation
threshold $E_{\min}$ (we use $E_{\min} \ge 0^{\circ}$, configurable):

$$
\begin{aligned}
\text{AOS} &= \min \{ t : E(t) = E_{\min},\; \dot E(t) > 0 \}, \\
\text{LOS} &= \min \{ t > \text{AOS} : E(t) = E_{\min},\; \dot E(t) < 0 \}, \\
\text{TCA} &= \arg\max_{t \in [\text{AOS}, \text{LOS}]} E(t).
\end{aligned}
$$

A *pass* is the triple (AOS, TCA, LOS) plus
$E_{\max} = E(\text{TCA})$ and the three azimuths $A(\text{AOS}), A(\text{TCA}),
A(\text{LOS})$.

### 8.2 Algorithm

`predictPasses` delegates to `PassPredictor.getPasses(Date, hours, false)`,
which:

1. Initialises the SGP4 propagator from the TLE.
2. Steps forward in coarse time steps (~1 min) tracking the sign of $E - E_{\min}$.
3. Bisects each sign change to a few seconds.
4. Returns `SatPassTime` triples sorted by start time.

After the predictor returns we apply two filters:

```kotlin
return rawPasses
    .filter { it.maxEl >= minElevationDegrees }     // double-check the threshold
    .filter { it.endTime.toInstant().isAfter(Instant.now()) } // drop already-elapsed
    .map { ...build Pass... }
    .sortedBy { it.aos }
```

If the predictor does not supply `tca` we synthesise it as the midpoint of AOS
and LOS - accurate to within a few seconds for typical low-eccentricity LEO
passes because of the symmetry of $E(t)$ around the closest approach.

### 8.3 Why we do not call `PassPredictor` for every satellite blindly

There are ~700 alive satellites in the SatNOGS catalog. A naive
`for (sat in catalog) predictor.getPasses(...)` would be:

- expensive in CPU (each call does pseudo-orbit propagation),
- catastrophic when a satellite is **never visible** from this latitude (the
  library may not converge - see Section 7.4),
- wasteful because most satellites do not transmit in the user's selected
  pass-bands.

We therefore apply a three-stage funnel **before** the predictor is invoked
(Section 13.2 and Section 17):

1. **Band match** (`FilterSatellitesByBandUseCase`) -> drop satellites with no
   in-band transmitter.
2. **`willBeSeen` test** (predict4java) -> drop satellites whose orbital
   inclination cannot produce a visible pass from this latitude.
3. **Geometric ceiling** (closed form, Section 9) -> drop satellites whose
   *best possible* elevation from this latitude is below $E_{\min}$.

In practice this cuts the workload by roughly an order of magnitude.

---

## 9. Geometric maximum-elevation pre-filter

For step (3) of the funnel we derive a closed-form upper bound on the maximum
elevation observable from latitude $\varphi$ for a circular orbit of
inclination $i$ and mean motion $n$ (rev/day).

### 9.1 Coverage latitude

For a prograde orbit ($i \le 90^{\circ}$) the satellite's ground track reaches
$\pm i$ in geographic latitude; for a retrograde orbit ($i > 90^{\circ}$) it
reaches $\pm (180^{\circ} - i)$. Define the *coverage latitude*

$$
i_{\text{eff}} \;=\; \begin{cases} i, & i \le 90^{\circ}, \\ 180^{\circ} - i, & i > 90^{\circ}. \end{cases}
$$

### 9.2 Minimum geocentric ground-track separation

The minimum angular distance from the observer's footprint to the closest
point of the ground track is

$$
d_{\min} \;=\; \max\bigl( 0,\; |\varphi| - i_{\text{eff}} \bigr) .
$$

If $d_{\min} = 0^{\circ}$ the satellite can pass directly overhead and
$E_{\max} = 90^{\circ}$.

### 9.3 Slant geometry

For $d_{\min} > 0^{\circ}$, place the observer on Earth's surface (radius
$R_E = 6378.137$ km) and the satellite on a circular orbit of radius
$r = a$ (the semi-major axis from $n$, Section 6.1). The closest approach is
when the satellite passes the meridian above the closest sub-satellite point.
The law of cosines on the triangle (Earth-centre -> observer -> satellite) gives

$$
s \;=\; \sqrt{ R_E^2 + r^2 - 2 R_E r \cos d_{\min} },
$$

and from the same triangle

$$
\sin E_{\max} \;=\; \frac{r \cos d_{\min} - R_E}{s}.
$$

The implementation clamps `sinE` to $[-1, 1]$ to absorb floating-point error:

```kotlin
fun maxPossibleElevationDeg(
    inclinationDeg: Double, meanMotionRevPerDay: Double, observerLatDeg: Double
): Double {
    if (meanMotionRevPerDay <= 0.0) return 0.0
    val coverageLat = if (inclinationDeg <= 90.0) inclinationDeg
                      else 180.0 - inclinationDeg
    val phi  = abs(observerLatDeg)
    val dMin = max(0.0, phi - coverageLat)
    if (dMin <= 0.0) return 90.0
    val nRadPerSec = meanMotionRevPerDay * 2.0 * PI / 86_400.0
    val a = (MU_KM3_S2 / (nRadPerSec * nRadPerSec)).pow(1.0 / 3.0)
    val d = Math.toRadians(dMin)
    val slant = sqrt(R_E*R_E + a*a - 2*R_E*a*cos(d))
    val sinE  = ((a * cos(d) - R_E) / slant).coerceIn(-1.0, 1.0)
    return Math.toDegrees(asin(sinE))
}
```

This is an **upper bound** (it assumes a perfectly circular orbit and ignores
Earth's oblateness) - exactly what we need for a pre-filter: never reject a
satellite that *could* satisfy the user's $E_{\min}$.

### 9.4 Cost

The whole filter runs in constant time per satellite, with no allocations
inside the hot loop. Eliminating one infeasible satellite saves ~10-500 ms of
predict4java propagation; eliminating one satellite that would have hit the
3 s timeout saves the whole 3 s budget.

---

## 10. Doppler curve

### 10.1 Derivation

Let a satellite emit at nominal frequency $f_{\text{nom}}$ and recede from the
observer at radial velocity $v_r = \dot\rho$ (km/s, positive = receding). In
the non-relativistic limit $v_r \ll c$ the observed frequency is

$$
f_{\text{obs}} \;=\; f_{\text{nom}} \left( 1 - \frac{v_r}{c} \right), \qquad c = 299\,792.458\;\text{km/s}.
$$

The instantaneous Doppler *offset* (the quantity we plot) is

$$
\Delta f(t) \;=\; f_{\text{nom}} - f_{\text{obs}}(t) \;=\; f_{\text{nom}} \cdot \frac{\dot\rho(t)}{c}.
$$

Sign convention: $\Delta f > 0$ when the satellite is **receding**
(red-shifted, observed frequency is below nominal). This is what the
`PassCard` shows - positive numbers tell the operator the receiver needs to be
tuned *below* the catalog frequency.

### 10.2 Discretisation

The curve is sampled at $N = 30$ time steps uniformly across the pass:

$$
t_k \;=\; t_{\text{AOS}} + \frac{k}{N} \bigl( t_{\text{LOS}} - t_{\text{AOS}} \bigr), \qquad k = 0, 1, \dots, N.
$$

This is enough resolution for the detail-screen `DopplerChart` while keeping
the computation negligible (~31 propagator calls per opened detail view).

### 10.3 Implementation

```kotlin
private fun buildDopplerCurve(
    sat: P4JSatellite, qth: GroundStationPosition,
    aos: Instant, los: Instant, fNomHz: Long
): List<DopplerPoint> {
    val durSec = los.epochSecond - aos.epochSecond
    if (durSec <= 0) return emptyList()
    val points = 30
    val out = mutableListOf<DopplerPoint>()
    for (i in 0..points) {
        val t = aos.plusMillis(i.toLong() * durSec * 1000L / points)
        val pos = try { sat.getPosition(qth, Date.from(t)) }
                  catch (e: Exception) { continue }
        val offsetHz = fNomHz * (pos.rangeRate / SPEED_OF_LIGHT_KM_S)
        out.add(DopplerPoint(t, offsetHz))
    }
    return out
}
```

The standalone use case `BuildDopplerCurveUseCase` exposes the same formula
for unit testing without needing a real TLE:

```kotlin
class BuildDopplerCurveUseCase {
    private val C_KM_S = 299792.458
    // Delta f = f_nom - f_obs = f_nom * rangeRate / c. Positive when the sat is receding.
    fun calculateOffset(nominalFrequencyHz: Long, rangeRateKmS: Double): Double {
        return nominalFrequencyHz * (rangeRateKmS / C_KM_S)
    }
    fun calculateObservedFrequency(nominalFrequencyHz: Long, rangeRateKmS: Double): Double {
        return nominalFrequencyHz * (1.0 - rangeRateKmS / C_KM_S)
    }
}
```

### 10.4 Lazy computation

A typical refresh produces 50-200 passes. Building Doppler curves for all of
them up-front would mean thousands of propagator calls. Instead, the Doppler
curve and sky arc for a pass are computed **only when the user opens the
detail screen for that pass** (`SatelliteDetailViewModel.load(pass)`). The
detail VM produces a `GeometryBundle` (Doppler + sky + four ground-track
layers) in a single `withContext(Dispatchers.Default)` block, then emits it
as a partial state update so the rest of the detail screen (transmitter
list, observation history) can render in parallel.

The pass list itself only carries the pre-computed `Pass` records returned
by the propagator (AOS / TCA / LOS / azimuths / `maxElevation`); the
expensive per-pass geometry is never serialised into `pass_cache`.

---

## 11. Reception probability heuristic

### 11.1 Score definition

Two ingredients:

- **elevation score** $s_E$: monotonically increasing in $E_{\max}$, $s_E \in [0, 1]$,
  with $s_E(0) = 0$ and $s_E(90^{\circ}) = 1$. We use the sine of elevation,
  which is the cosine of the off-zenith angle - directly proportional to the
  ratio of received to maximum possible flux for an isotropic antenna:

  $$s_E \;=\; \sin\bigl(E_{\max} \cdot \pi / 180\bigr).$$

- **history score** $s_H$: empirical good/total ratio over the last
  N observations of this satellite from SatNOGS Network. With $g$ "good" and
  $f$ "failed" observations:

  $$s_H \;=\; \begin{cases} \dfrac{g}{g+f}, & g + f \ge 3, \\ 0.5, & \text{otherwise (neutral fallback).} \end{cases}$$

The combined probability is a convex combination clamped to $[0, 1]$:

$$
P \;=\; \mathrm{clip}\bigl( 0.55 \cdot s_E + 0.45 \cdot s_H,\; 0,\; 1 \bigr).
$$

The 0.55 / 0.45 split was chosen so that an overhead pass of a low-history
satellite still plots above 0.5 (the user knows the geometry is great and is
told to be sceptical of the *content*, not the visibility), and a
low-elevation pass of a popular satellite stays below 0.7 (the user is
reminded that 5 deg is hard).

### 11.2 Classification thresholds

```kotlin
fun classify(probability: Double, totalCount: Int): SatelliteStatus {
    if (totalCount < MIN_HISTORY) return SatelliteStatus.NO_DATA   // 3
    return when {
        probability >= PROMISING_THRESHOLD -> SatelliteStatus.PROMISING   // 0.60
        probability < UNLIKELY_THRESHOLD   -> SatelliteStatus.UNLIKELY    // 0.30
        else                               -> SatelliteStatus.NEUTRAL
    }
}
```

`UNLIKELY` covers any pass whose combined score falls below 0.30 with at
least three historical observations on record - typically satellites with a
clear majority of "failed" SatNOGS observations.

### 11.3 Why not Bayesian?

A proper Bayesian estimator (Beta prior on the binomial good/total) would
shrink low-history ratios towards 0.5 more smoothly. We picked the simpler
fixed fallback because (a) the SatNOGS observation status is noisy
(an observer can mark a successful capture as "failed" when the decoded data
is empty even though the RF reception worked), and (b) the heuristic only
*hints* - the final go/no-go is the user's. The fixed thresholds also make
the UI labels stable across refreshes.

### 11.4 Re-scoring on detail open

`Pass.receptionProbability` (and the matching `goodCount` / `totalCount`)
are seeded by the bulk propagation in `PassesViewModel.refreshPasses()`,
using whatever observations are in the local cache at that moment. The
detail screen re-runs `EvaluateReceptionProbabilityUseCase.execute` against
the freshly fetched observations (`forceRemote = true`) and overwrites the
pass record in state. This avoids the "RX 0 % - no hist." chip persisting
on the detail screen for satellites whose history was empty during the
initial propagation (typical right after a destructive DB migration) but
filled in by the time the user opened the pass.

---

## 11a. Ground-track display and adjacent passes

The detail screen renders four polylines on the OSMDroid ground-track map,
plus a live sub-satellite marker:

| Layer | Source | Style |
|-------|--------|-------|
| Selected pass | `buildArcTrack(tle, name, station, pass.aos, pass.los)` | solid indigo (`#A5B4FC`) |
| Previous pass | `buildArcTrack` on the AOS/LOS returned by `findAdjacentPassWindows(...).first` | dashed amber (`#FBBF24`) |
| Next pass | `buildArcTrack` on the AOS/LOS returned by `findAdjacentPassWindows(...).second` | dashed emerald (`#34D399`) |
| Full current orbit | `buildGroundTrack(tle, name, station, startTime = Instant.now())` (one orbital period) | solid cyan (`#22D3EE`) matching the live marker |
| Live sub-satellite | `subSatellitePoint(tle, name, station, Instant.now())`, ticked every 2 s | glowing cyan dot |

The layer order draws the full-orbit cyan first, then the dashed
adjacent-pass arcs, then the solid selected pass last - so the pass the
user actually clicked on is never obscured.

### 11a.1 `findAdjacentPassWindows`

Adjacent-pass discovery uses predict4java's `PassPredictor` over a 48 h
window centred 24 h before the reference AOS, and picks the pass whose AOS
is *closest* to the reference. A strict "AOS within 5 minutes" match was
tried first and abandoned: the reference pass comes from `pass_cache`,
which may have been computed against an older TLE, so a strict equality
check fails after every Celestrak refresh. Closest-AOS is robust and
correct as long as the reference window covers the actual pass.

The `minElevation` user threshold is *ignored* for adjacent-pass search -
the user wants to see the literal previous and next pass, regardless of
whether either of them would clear the threshold required to appear on the
Passes list.

### 11a.2 Orbital period clamp

`buildGroundTrack` derives the default total duration from the TLE mean
motion (`1440.0 / meanMotionRevPerDay` minutes), then clamps the result to
$[40, 200]$ minutes:

- The lower bound protects against numerically tiny mean motions in
  malformed TLEs (e.g. `0.001`, which would yield a 10-day "orbit"; with
  600 samples that paints a crisscross of overlapping ground tracks).
- The upper bound prevents geosynchronous and Molniya orbits from drawing
  multiple revolutions on top of each other when the user only wants
  "where the satellite is going next".

### 11a.3 Antimeridian splitting

Polyline rendering is split into separate `Polyline` overlays at every
$|{\Delta\lambda}| > 180^{\circ}$ between consecutive samples (a satellite
crossing the dateline). Without the split, OSMDroid would draw a single
rubber-band line wrapping the wrong way around the globe.

---

## 11b. SatDump pipeline detection

SatDump ships per-mission demodulation pipelines as JSON files under
`resources/pipelines/` in its source tree (88 files at the time of
writing - `ACE.json`, `NOAA.json`, ..., `Sonate-2.json`). These files do
**not** carry NORAD IDs; SatDump matches satellites by *name*. To stay
consistent with that data model the companion does the same:

```kotlin
object SatDumpSupport {
    private val SUPPORTED_NAME_TOKENS: List<String> = listOf(
        "ace", "aditya", "aim", "aws", "bluewalker3", "blue walker",
        "chandrayaan", "cloudsat", "cluster", "coriolis", "cosmos",
        "cryosat", "dmsp", "dscovr", "earthcare", "edrs",
        "elektro", "arktika", "eos", "erminaz", "escapade",
        "fengyun", "fy-2", "fy-3", "fy-4",
        "formosat", "gcom", "geonetcast", "geoscan", "gk-2a", "gk2a",
        "goes", "gpm", "hera", "himawari", "hinode",
        // ...
    )

    fun isSupported(satelliteName: String?): Boolean {
        if (satelliteName.isNullOrBlank()) return false
        val lower = satelliteName.lowercase()
        return SUPPORTED_NAME_TOKENS.any { lower.contains(it) }
    }
}
```

The token list is generated from the SatDump pipeline filenames (minus
generic labels - `Test`, `Others`, `Analog`, `DVB_Test`,
`Work-In-Progress` - which would match almost anything). The
`SuccessRateChip` and the pass / detail UIs both query
`SatDumpSupport.isSupported(pass.satelliteName)` to render the
"SatDump pipeline" badge.

A NORAD-keyed approach was tried first and abandoned: every NORAD ID had
to be hand-maintained against the SatDump source tree, and the user-visible
"is there a decoder for this satellite?" question is, in SatDump's own
ontology, a name question.

---

## 12. Antenna-band / transmitter cross-matching

```kotlin
fun execute(
    satellites: List<Satellite>,
    transmitters: List<Transmitter>,
    bands: Set<AntennaBand>
): List<MatchedSatellite> {
    val activeTransmitters = transmitters.filter { it.isActive }
    return satellites.mapNotNull { sat ->
        val matchedTransmitter = activeTransmitters
            .filter { it.satelliteId == sat.id }
            .firstOrNull { tx -> bands.any { band -> tx.frequency in band.frequencyRange } }
        if (matchedTransmitter != null) MatchedSatellite(sat, matchedTransmitter) else null
    }
}
```

For each satellite we look for the first active transmitter whose frequency
falls inside *any* of the user's selected antenna pass-bands. The complexity
is $O(|S| \cdot |T_{\text{active}}| \cdot |B|)$, where $|B| \le 6$ (number of
band enum entries). With $|S| \approx 700$ and $|T_{\text{active}}| \approx
1500$ on a real SatNOGS catalog this is ~6 million scalar comparisons - under
50 ms on a mid-range phone. (Could be sped up by pre-grouping transmitters by
satelliteId; left as-is because the cost is dwarfed by propagation.)

We deliberately return **at most one** transmitter per satellite. A satellite
can have multiple in-band transmitters (e.g. ISS has voice repeater +
APRS digipeater + SSTV on 145 MHz). The first match is shown as
"representative" and the user can read the full list in the SatNOGS web UI
linked from the description.

---

## 13. Application data flow

### 13.1 End-to-end refresh on the Passes screen

```
 User pulls to refresh
        |
        v
PassesViewModel.refreshPasses(forceRemoteSync = true)
        |
        v
SatnogsRepository.syncFromRemote()
        |      +-- DB API   GET /satellites/   (filter status=alive)
        |      +-- DB API   GET /transmitters/  -> keep only those whose satelliteId
        |      |                                  is in the satellite catalog
        |      +-- DB API   GET /tle/           -> keep only those whose noradId
        |      |                                  exists in the catalog
        |      `-- Network  GET /observations/?limit=100  (warm cache)
        |            |
        |            v
        |   Room: INSERT-OR-REPLACE satellites, transmitters, tles, observations
        |
        v
PassesViewModel
  +-- Read settings (groundLat/Lon/Alt, antennaBands, minElevation)
  +-- FilterSatellitesByBandUseCase.execute(...) -> in-band satellites
  +-- Look up TLE for each -> drop satellites without a fresh TLE
  +-- parallel propagation (Section 17):
  |       coroutineScope { matched.map { async {
  |         gate.withPermit {                    // bounded by CPU cores
  |           obs = repo.getObservations(satId, forceRemote = false)
  |           future = propagationExecutor.submit { propagator.predictPasses(...) }
  |           rawPasses = future.get(3 s)        // hard timeout
  |           rawPasses.map { decorate with reception probability }
  |         }
  |       } }.awaitAll() }
  +-- flatten + sort by AOS
  +-- write to passCacheDao  (next cold start is instant)
  `-- emit Success state -> Compose recomposes the list
```

### 13.2 Cold start

```
 App start -> PassesViewModel.init
        |
        v
loadFromCache()    // 1. Room: SELECT * FROM pass_cache
        |      -> emit cached passes (UI shows them with "Cached ... - refreshing..." notice)
        |
        v
collect settingsRepository.getUserSettingsFlow()
        |
        v
refreshPasses(forceRemoteSync = false)   // 2. background re-propagate
        |      -> emit fresh passes when ready
        v
ensureHistoryForVisible()
        |      -> fetch SatNOGS Network observation history (rate-limited, spaced 700 ms apart)
        v
applyObservationsToPasses(satId, obs)    // 3. update reception probability per sat
```

### 13.3 Detail screen open

```
User taps a PassCard
        |
        v
NavGraph: passes/{pass} -> detail screen
        |
        v
SatelliteDetailViewModel.load(pass)
  |
  +- read user settings (lat / lon / alt) from DataStore
  +- load Satellite, transmitters, cached TLE (Room, parallel)
  +- emit a partial state so the UI can paint metadata immediately
  |
  +- if TLE present, on Dispatchers.Default:
  |     build Doppler curve  (~31 propagator calls)
  |     build sky arc        (~81 propagator calls)
  |     build selected pass arc
  |     find previous + next pass (PassPredictor over +/-24 h)
  |     build full current orbit
  |     emit GeometryBundle -> second partial state
  |
  +- fetch observations (forceRemote = true, may 429 -> empty list)
  |     re-score reception probability against fresh observations
  |     emit third partial state with observations + rescored pass
  |
  `- startLiveTracking(): every 2 s push subSatellitePoint(now) to a
        separate StateFlow consumed by the live marker
```

### 13.4 Pull-to-refresh vs. forced remote sync

| Trigger | `forceRemoteSync` | What happens |
|---------|-------------------|--------------|
| Cold start | `false` | Use Room caches, re-propagate, hit Network for history |
| Pull to refresh | `true` | Re-download DB + Network catalog, then re-propagate |
| Settings change (band, minElev, location) | `false` | Settings flow re-emits -> re-propagate from cache |
| Periodic `SatnogsSyncWorker` (24 h) | n/a | Background DB+TLE refresh, prune old observations |

---

## 14. Networking layer

### 14.1 Two SatNOGS APIs

- **SatNOGS DB** - `https://db.satnogs.org/api/` - catalog: satellites,
  transmitters, TLE.
- **SatNOGS Network** - `https://network.satnogs.org/api/` - operational data:
  ground stations, observations.

Both are public, no OAuth, but Network throttles bursts hard (HTTP 429).

### 14.2 Dynamic base URL

The two APIs share a Retrofit client but resolve to different hosts at
runtime. Each Retrofit method declares **which** host it wants via a custom
header that the interceptor strips:

```kotlin
@GET("satellites/")
@Headers("$HEADER_SATNOGS_HOST: $HOST_DB")
suspend fun getSatellites(@Query("status") status: String? = "alive"): List<SatelliteDto>

@GET("observations/")
@Headers("$HEADER_SATNOGS_HOST: $HOST_NETWORK")
suspend fun getObservations(
    @Query("norad_cat_id") noradCatId: String? = null,
    @Query("status")       status: String?    = null,
    @Query("limit")        limit: Int?        = 50
): List<ObservationDto>
```

The `DynamicBaseUrlInterceptor` reads the host header, looks up the cached
DataStore value for `dbBaseUrl` / `networkBaseUrl`, rewrites the scheme + host
+ port + path prefix of the outgoing request, and removes the header. This
gives the user the ability to point the app at a local mock (e.g.
`http://10.0.2.2:8080/`) for offline testing **without restarting** or
rebuilding two Retrofit instances.

### 14.3 User-Agent

The default OkHttp UA gets throttled aggressively by SatNOGS. We attach an
identifying UA:

```kotlin
val userAgentInterceptor = okhttp3.Interceptor { chain ->
    val req = chain.request().newBuilder()
        .header("User-Agent", "LSF-SatNOGS-Companion/1.2 (Android; student project, PUT)")
        .build()
    chain.proceed(req)
}
```

### 14.4 Backoff for 429

`SatnogsRepositoryImpl.fetchObsSafely` retries each per-satellite observation
fetch up to twice with growing backoff and jitter, but **only** when the
server actually responded with 429 - any other error (network failure, 5xx)
returns immediately so we don't punish the user with a 6-second stall on a
broken Wi-Fi:

```kotlin
val backoffsMs = longArrayOf(0L, 700L, 2200L)
for ((attempt, baseDelay) in backoffsMs.withIndex()) {
    if (baseDelay > 0L) {
        val jitter = (Math.random() * 400).toLong()
        kotlinx.coroutines.delay(baseDelay + jitter)
    }
    try {
        val list = networkApi.getObservations(noradCatId, status, limit = 30)
        return ObsFetchResult(list, success = true)
    } catch (e: retrofit2.HttpException) {
        if (e.code() != 429) return ObsFetchResult(emptyList(), success = false)
        // else continue with backoff
    } catch (e: Exception) {
        return ObsFetchResult(emptyList(), success = false)
    }
}
```

The good/failed observations are fetched as two **parallel** requests
(`Dispatchers.IO` + `async`) and merged. We mark the satellite's cache as
fresh only if **at least one** of the two endpoints succeeded - so a single
429 does not poison the 6-hour TTL.

### 14.5 Why not the `/observations/` feed without `status`?

The default `/observations/` feed returns scheduled-future observations
(`status="future", vetted_status="unknown"`) which are useless for
classification. We query `status=good` and `status=failed` separately and
union the results.

### 14.6 Per-satellite observation TTL

```kotlin
private const val OBS_TTL_MS: Long = 6L * 60L * 60L * 1000L   // 6 hours
```

Historical success rates change slowly enough that 6 hours is safe; short
enough that yesterday's broken satellite will be re-checked today.

---

## 15. Persistence layer (Room + DataStore)

### 15.1 Room schema (version 8)

```
satellites(id PK, name, noradId IDX, isActive, description, hasDecoder,
           observationsFetchedAt)
satellites_fts(name)                              [Fts4(contentEntity = SatelliteEntity)]
tles(noradId PK IDX, line1, line2, lastUpdated, epochMillis)
transmitters(id PK, satelliteId FK CASCADE IDX, frequency, modulation,
             mode, description, isActive, status)
observations(id PK, satelliteId FK CASCADE IDX, status, timestamp, stationName)
pass_cache(id PK, satelliteId, noradId, satelliteName, statusOrdinal,
           aosMillis, tcaMillis, losMillis, maxElevation,
           startAzimuth, tcaAzimuth, endAzimuth, transmitterId,
           tleEpochMillis, satelliteHasDecoder, computedAt)
```

`observations.stationName` is populated from SatNOGS Network's
`station_name` JSON field (or `"Station #<ground_station>"` as a fallback
when only the numeric ID is returned); the satellite detail screen renders
it as a column in the recent-observations table.

### 15.2 Why FTS4 on satellites

The Stats screen and the (future) catalog browser need a name-search.
SQLite's FTS4 lets us run prefix-matched, case-insensitive, accent-folded
queries in a single `MATCH` without writing a custom tokenizer.

### 15.3 Foreign key cascades

Transmitters and observations have a `CASCADE` foreign key on `satellites.id`.
This means a single `DELETE FROM satellites WHERE id = ?` cleans the entire
sub-tree - useful when the DB API drops a satellite (decay, decommissioning).

### 15.4 Destructive migration

```kotlin
.fallbackToDestructiveMigration() // safe for companion cache
```

The DB only caches data that can be re-downloaded in seconds. Maintaining
hand-written migrations between schema versions would be wasted effort for a
disposable cache. On any version bump the DB is rebuilt; the next refresh
re-populates it.

### 15.5 DataStore preferences

Stored keys (`PreferencesDataSource.PreferenceKeys`):

```
KEY_DB_BASE_URL              String
KEY_NETWORK_BASE_URL         String
KEY_GROUND_LAT, _LON, _ALT   Double      (defaults: Poznan, 80 m)
KEY_ANTENNA_BANDS            String (comma-separated enum names)
KEY_MIN_ELEVATION            Double
KEY_ALARMS_ENABLED           Boolean
KEY_ALARM_LEAD_TIME          Int (minutes)
KEY_PRESETS                  String (one preset per line, ";"-separated fields)
```

The custom preset serialiser keeps the schema human-readable so a user can
inspect / export the file via `adb pull` if they want.

---

## 16. Background work and alarms

### 16.1 Periodic catalog sync

`SatnogsSyncWorker` (subclass of `CoroutineWorker`) runs via WorkManager on a
24-hour cadence (network constraint required). On success it:

1. calls `repository.syncFromRemote()` (Section 13.1),
2. calls `repository.pruneOldObservations(olderThanDays = 7)`,
3. fires a low-importance notification on the *TLE sync* channel so the user
   knows the catalog is fresh.

Failure returns `Result.retry()`, so WorkManager retries with its standard
exponential backoff.

### 16.2 Per-pass exact alarms

A radio amateur needs *seconds* of precision before AOS. We use the
`AlarmManager.setExactAndAllowWhileIdle` API for that:

```kotlin
val alarmTriggerMillis = pass.aos.toEpochMilli() - leadTimeMinutes * 60L * 1000L
if (alarmTriggerMillis <= System.currentTimeMillis()) return
if (Build.VERSION.SDK_INT >= S && !alarmManager.canScheduleExactAlarms()) return
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP,
    alarmTriggerMillis,
    pendingIntent
)
```

`SCHEDULE_EXACT_ALARM` is declared in the manifest. On SDK 31+ the user must
grant it from system settings; the call short-circuits if not granted.

### 16.3 Boot persistence

Exact alarms do not survive a reboot. `BootReceiver` listens for
`android.intent.action.BOOT_COMPLETED` and (if alarms were enabled in
DataStore) enqueues `AlarmRescheduleWorker`. The worker is currently a no-op
documented in source - per-pass alarms are user-toggled per card after the
next refresh - but the wiring is in place if the requirements change.

### 16.4 Notification channels

```
TLE sync       (IMPORTANCE_LOW)    - silent, status of the 24 h sync
Pass alerts    (IMPORTANCE_HIGH)   - sound, head-up, full-screen-intent capable
Space weather  (IMPORTANCE_DEFAULT) - placeholder for a planned K-index channel
```

---

## 17. Concurrency model and parallelism

### 17.1 Bounded parallelism for propagation

```kotlin
val cores = Runtime.getRuntime().availableProcessors()
    .coerceIn(2, MAX_PROPAGATION_PARALLELISM /* 8 */)
val gate = Semaphore(cores)
val done    = AtomicInteger(0)
val skipped = AtomicInteger(0)

val results = coroutineScope {
    matchedWithTles.map { (match, tle) ->
        async {
            gate.withPermit {
                val cachedObs = repo.getObservations(match.satellite.id, forceRemote = false)
                val future = propagationExecutor.submit(Callable { propagator.predictPasses(...) })
                val rawPasses = try { future.get(3 s) }
                                catch (Timeout)   { future.cancel(true); skipped.inc(); emptyList() }
                                catch (Exception) { skipped.inc(); emptyList() }
                rawPasses.map { it.copy(reception probability...) }
            }
        }
    }.awaitAll()
}
```

Why bounded? Each propagation is CPU-heavy. Running 700 of them concurrently
would starve the UI thread and thrash. `Semaphore(cores)` keeps at most one
in-flight task per core. The hard ceiling of 8 prevents pathological scheduler
contention on big.LITTLE phones reporting unrealistic core counts.

### 17.2 The cached executor

The `Future.get(timeout)` pattern requires a separate thread that can be
abandoned. Using `Dispatchers.Default` alone is not enough - coroutine
cancellation does not interrupt blocking compute. We therefore submit the
propagation Callable to a dedicated `Executors.newCachedThreadPool` named
`sat-prop` with daemon threads. A timed-out call is `Future.cancel(true)`'d,
the thread is left to die, and the cached pool spins up a fresh one for the
next request.

### 17.3 UI progress

Progress is reported through `_loadingProgress` only every
`PROGRESS_UI_INTERVAL = 5` propagations to avoid spamming `Dispatchers.Main`:

```kotlin
val d = done.incrementAndGet()
if (d == total || d % PROGRESS_UI_INTERVAL == 0) {
    withContext(Dispatchers.Main) {
        _loadingProgress.value = _loadingProgress.value?.copy(
            stage = "Propagating orbits - $d/$total (${match.satellite.name})",
            current = d, total = total
        )
    }
}
```

### 17.4 History fetch is intentionally serial

History fetches hit the rate-limited Network API. Running them in parallel
would just trigger 429 storms. `ensureHistoryForVisible` walks the visible
satellites sequentially with `INTER_SAT_DELAY_MS = 700` between requests.

---

## 18. Optimisation catalogue

A consolidated list of the optimisations described above, with the metric they
target.

| # | Technique | Where | Targets |
|---|-----------|-------|---------|
| 1 | **Antenna-band funnel** | `FilterSatellitesByBandUseCase` | Drop ~70-90% of catalog before propagation |
| 2 | **`willBeSeen` early exit** | `SatPropagator.predictPasses` | Skip satellites whose orbit is invisible from this latitude |
| 3 | **Closed-form $E_{\max}$ ceiling** | `maxPossibleElevationDeg` | $O(1)$ pre-filter against $E_{\min}$ |
| 4 | **`Future.get(timeout)`** | `PassesViewModel` | 3 s hard cap per satellite; library can't hang the refresh |
| 5 | **Bounded parallelism (Semaphore)** | `PassesViewModel` | Saturate CPU without starving UI thread |
| 6 | **Daemon `sat-prop` thread pool** | `PassesViewModel` | Stuck threads die cleanly; no resource leak |
| 7 | **Throttled progress updates (1/5)** | `PassesViewModel` | UI thread is hit ~140 times, not 700, per refresh |
| 8 | **Lazy geometry per detail open** | `SatelliteDetailViewModel.load` | Doppler + sky arc + four ground-track layers are propagated only when the user actually opens the detail screen, not for every pass |
| 9 | **In-memory cache of per-sat context** | `tleBySatId`, `transmitterBySatId` | Lazy compute skips Room re-reads |
| 10 | **`pass_cache` Room table** | `passCacheDao` | Cold start renders the list in <100 ms |
| 11 | **Per-sat observation TTL (6 h)** | `SatnogsRepositoryImpl` | Skip Network calls inside the TTL window |
| 12 | **Parallel good+failed fetch** | `getObservations(forceRemote = true)` | Single round-trip instead of two |
| 13 | **Per-host backoff with jitter (429-only)** | `fetchObsSafely` | Resilient against bursts; bail fast on real errors |
| 14 | **Spaced history fetches (700 ms)** | `ensureHistoryForVisible` | Never trip the Network rate-limit |
| 15 | **Single Retrofit, host-swap interceptor** | `DynamicBaseUrlInterceptor` | One OkHttp client, one connection pool |
| 16 | **Paging on the list (10 + load more)** | `PassesViewModel._visibleCount` | Compose recomposes 10 rows, not 200 |
| 17 | **Periodic prune of old observations** | `pruneOldObservations(7d)` | DB stays small (~MB) |
| 18 | **FTS4 index on satellite names** | Room `satellites_fts` | Sub-millisecond search |
| 19 | **`fallbackToDestructiveMigration`** | `AppContainer.database` | Zero migration cost for a regenerable cache |

### 18.1 Empirical effect (Pixel 6, ~700 alive satellites, VHF+UHF bands)

| Stage | Without optimisations | With all optimisations |
|-------|-----------------------|------------------------|
| Cold start to first list | ~12 s | <0.5 s (from `pass_cache`) |
| Pull-to-refresh on Wi-Fi | 25-40 s (with 1-3 hangs) | 4-7 s (0 hangs, <=3 skipped) |
| Open a pass detail | re-computes everything every time | propagation under 200 ms; observations dominate (network) |

---

## 19. Testing strategy

### 19.1 Current test suite

The committed suite is a thin smoke-test layer, run with
`./gradlew :app:testDebugUnitTest`:

| File | Type | Verifies |
|------|------|----------|
| `ExampleUnitTest.addition_isCorrect` | JVM | Build/test wiring sanity |
| `ExampleUnitTest.issOverPoznan_producesAtLeastOnePass` | JVM | **Real predict4java propagation** of the ISS over Poznan for 48 h at a 5 deg floor - asserts at least one pass and that every pass obeys $\text{AOS} \le \text{TCA} \le \text{LOS}$, $E_{\max} > 0$, and azimuths in $[0, 360]$ |
| `ExampleRobolectricTest.read string from context` | Robolectric (`@Config sdk=36`) | Android resource loading (`R.string.app_name == "Observation Companion"`) |
| `ExampleInstrumentedTest` | Instrumented (`androidTest`) | Device/emulator package-name smoke test |

The most valuable test is `issOverPoznan_producesAtLeastOnePass`: it exercises
the entire propagation path through the real predict4java engine (not a mock)
with a real ISS TLE, validating the time-ordering invariant and unit
conventions described in Sections 7-8.

### 19.2 Test infrastructure available but not yet exercised

The Gradle config wires up Roborazzi (Compose screenshot testing) and pulls in
Robolectric; the domain layer (pure Kotlin use cases such as
`EvaluateReceptionProbabilityUseCase`, `FilterSatellitesByBandUseCase`,
`BuildDopplerCurveUseCase`) is written to be trivially unit-testable in
isolation, but dedicated unit tests for those use cases and Roborazzi baseline
screenshots are **not** committed in this revision. They are the obvious next
testing milestone.

### 19.3 Why the propagation smoke test runs on the JVM

predict4java is pure Java with no Android dependencies, so the propagation
smoke test runs as a plain JVM unit test - fast, no emulator, deterministic
given a fixed TLE and start time window.

---

## 20. External sources, APIs and licences

### 20.1 Live APIs

| API | URL | What we read |
|-----|-----|-------------|
| SatNOGS DB | `https://db.satnogs.org/api/satellites/` (`?status=alive`) | Satellite catalog |
| SatNOGS DB | `https://db.satnogs.org/api/transmitters/` | Transmitters per satellite (frequency, modulation, mode) |
| SatNOGS DB | `https://db.satnogs.org/api/tle/` | TLE per NORAD ID |
| SatNOGS Network | `https://network.satnogs.org/api/observations/` (`?norad_cat_id=...&status=good|failed&limit=30`) | Per-satellite observation history |

All endpoints are public, JSON-only, no OAuth.

### 20.2 Reference documents

- D. A. Vallado, P. Crawford, R. Hujsak, T. S. Kelso -
  *"Revisiting Spacetrack Report #3: Rev 1"*, AIAA 2006-6753 (the canonical SGP4 reference).
- T. S. Kelso - *"Frequently Asked Questions: Two-Line Element Set Format"*, [Celestrak](https://celestrak.org/columns/v04n03/).
- SatNOGS API docs - [https://db.satnogs.org/api/](https://db.satnogs.org/api/), [https://network.satnogs.org/api/](https://network.satnogs.org/api/).
- ITU Recommendation ITU-R RA.769 - non-relativistic Doppler approximation
  is standard practice for $v_r/c \approx 10^{-5}$ in LEO.
- Android developer docs - [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager),
  [AlarmManager exact alarms](https://developer.android.com/training/scheduling/alarms),
  [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore).

### 20.3 Open-source libraries (and their licences)

| Library | Licence | Used for |
|---------|---------|----------|
| predict4java | Apache 2.0 | SGP4 propagation |
| OSMDroid | Apache 2.0 | Map view in location picker |
| OkHttp | Apache 2.0 | HTTP transport |
| Retrofit | Apache 2.0 | REST client |
| Moshi | Apache 2.0 | JSON adapter |
| Room | Apache 2.0 | Local DB |
| DataStore | Apache 2.0 | Preferences |
| Coil | Apache 2.0 | Image loading |
| OSMDroid | Apache 2.0 | Map (OSM raster tiles) |
| Google Play Services Location | Apache 2.0 | `FusedLocationProviderClient` |
| AndroidX (Compose, Lifecycle, Navigation, WorkManager, ...) | Apache 2.0 | Platform extensions |
| JUnit, Kotest, MockK, Turbine, Robolectric, Roborazzi | EPL / Apache 2.0 / MIT | Testing |

All licences are compatible with educational use; no GPL'd components are
included.

---

## 21. Limitations and future work

### 21.1 Known limitations

- **TLE staleness.** Predictions for fast-decaying or recently launched
  satellites can drift if the user has not synced in over 24 h. The UI shows
  the TLE epoch on each card but does not block stale predictions.
- **Doppler model.** Non-relativistic, ignores the ionospheric path-length
  contribution (negligible <2 GHz for amateur use, but real for X-band).
- **No instrumentation tests.** The build runs JVM-only tests; UI regression
  testing is by inspection.
- **OSMDroid tile UA.** Custom UA is set for SatNOGS API but not for OSMDroid
  tile fetches - the OSM TOS requires a non-default UA for production usage.
- **Release signing.** `signingConfigs.release` reads from environment
  variables; if those are empty the release build is unsigned. Debug builds
  are unaffected.
- **Space Weather channel.** The `NotificationChannel` for K-index alerts
  exists, but no fetcher is implemented.

### 21.2 Planned improvements

- Bayesian (Beta) prior on the historical reception ratio (Section 11.3).
- Per-pass Doppler tuning suggestion (mid-pass frequency that minimises
  re-tuning during the pass).
- Multi-language UI (currently mixed Polish in docs, English in code/UI).
- Hilt migration if the dependency graph grows beyond ~15 nodes.

---

## 22. Glossary of symbols

| Symbol | Meaning | Units |
|--------|---------|-------|
| $\varphi$ | observer geodetic latitude | deg |
| $\lambda$ | observer geodetic longitude | deg |
| $h$ | observer altitude above WGS84 ellipsoid | m |
| $i$ | orbital inclination | deg |
| $i_{\text{eff}}$ | coverage latitude ($i$ if prograde, $180^{\circ}-i$ if retrograde) | deg |
| $\Omega$ | right ascension of ascending node | deg |
| $\omega$ | argument of perigee | deg |
| $M$ | mean anomaly | deg |
| $e$ | eccentricity | - |
| $n$ | mean motion | rev/day |
| $a$ | semi-major axis | km |
| $r$ | orbital radius (~ $a$ for low-$e$ orbits) | km |
| $R_E$ | mean Earth radius, $6378.137$ | km |
| $\mu$ | Earth's gravitational parameter, $398\,600.4418$ | km^3/s^2 |
| $E$ | satellite elevation above horizon | deg or rad |
| $E_{\min}, E_{\max}$ | minimum allowed / maximum during pass | deg |
| $A$ | satellite azimuth | deg (0=N, +ve E) |
| $\rho$ | slant range observer->satellite | km |
| $\dot\rho = v_r$ | range-rate | km/s |
| $c$ | speed of light, $299\,792.458$ | km/s |
| $f_{\text{nom}}$ | nominal transmitter frequency | Hz |
| $f_{\text{obs}}$ | observed (Doppler-shifted) frequency | Hz |
| $\Delta f$ | Doppler offset $f_{\text{nom}} - f_{\text{obs}}$ | Hz |
| $s_E$ | elevation score $\sin(E_{\max})$ | $[0,1]$ |
| $s_H$ | history score $g/(g+f)$ | $[0,1]$ |
| $P$ | combined reception probability | $[0,1]$ |
| AOS / TCA / LOS | Acquisition / Time of Closest Approach / Loss Of Signal | UTC |
| QTH | ground station location (amateur radio shorthand) | - |
| TLE | Two-Line Element set | - |
| ECI / TEME | Earth-Centered Inertial / True Equator Mean Equinox frames | - |
