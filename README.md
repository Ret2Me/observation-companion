# Observation Companion

> **Unofficial app.** Not affiliated with or endorsed by SatNOGS or the Libre
> Space Foundation it only consumes their public, open APIs.

A mobile companion for amateur-radio satellite operators built on the
[SatNOGS](https://satnogs.org/) ecosystem. It lists upcoming satellite passes
for a chosen location and antenna band, scores the likelihood of reception by
combining pass geometry with historical observations from the SatNOGS network,
draws the Doppler curve and a sky map, shows the ground track (previous / next
pass plus the live orbit), and fires an alarm a few minutes before AOS.

Author: Filip Poplewski.

---

## Documentation

| Document | Contents |
|----------|----------|
| **[Technical Report (`REPORT.md`)](REPORT.md)** | Full theoretical & technical description orbital mechanics (SGP4/SDP4), the Doppler model, the reception-probability scoring, sky-arc and ground-track geometry, architecture, and the data flow end to end. **Start here for the theory.** |
| **[Tech Stack (`STACK.md`)](STACK.md)** | Libraries, versions and the rationale behind each dependency (in Polish). |

---

## Features

### Screens

1. **Passes** (home) - list of upcoming passes (next 24 h) with pull-to-refresh,
   sorting (time / max elevation / reception chance / name), a multi-select
   filter by satellite name, and a compact/detailed view toggle. Each card shows
   AOS / TCA / LOS, max elevation with azimuth, the matched transmitter, a
   reception-chance chip, decoder badges (SatNOGS + SatDump), and a pre-AOS alarm
   switch. Tapping a card opens the detail screen.
2. **Satellite detail** - full pass view: AOS / MAX EL / LOS clock, a "PASS
   GEOMETRY" panel (sky-view polar plot + Doppler curve), a "GROUND TRACK" with
   four track layers (selected pass, previous and next as dashed lines, current
   orbit in cyan) and a live sub-satellite marker updated every 2 s, the full
   transmitter list, a table of recent observations with ground-station names,
   and a manual "Refresh TLE" button (Celestrak).
3. **Location** - pick the observation point on an OSMDroid map (long-press to
   move the observer), enter coordinates manually, fetch from GPS
   (`FusedLocationProviderClient`), built-in observatory presets, and
   user-saved presets (location + band set).
4. **Settings** - antenna bands (multi-select: VHF / UHF / L / S / C / X),
   minimum-elevation threshold `E_min`, alarm lead time, base URLs for both
   SatNOGS APIs (DB + Network) swapped at runtime by `DynamicBaseUrlInterceptor`,
   and a manual "Force sync".
5. **Stats** - catalog and local-database statistics: last sync state, age of
   the newest/oldest TLE, transmitter breakdown by band, observation aggregates,
   and on-disk database size.

### Domain logic / algorithms

See **[`REPORT.md`](REPORT.md)** for the full derivations. In brief:

- **Orbit propagation (SGP4 / SDP4)** - `predict4java` wrapped by
  `SatPropagator`. The domain exchanges angles in **degrees**, distance in
  **km**, time as `java.time.Instant`. Every `predict4java` call is wrapped in a
  `Future.get(timeout = 3 s)` on a dedicated executor - the library can hang on
  non-converging orbits.
- **Geometric pre-filter** - before full propagation, satellites whose maximum
  possible elevation (from inclination, mean motion and observer latitude) is
  below `E_min` are dropped (`maxPossibleElevationDeg`).
- **Band x transmitter cross-matching** - `FilterSatellitesByBandUseCase` keeps
  satellites with any active `Transmitter` inside a user-selected band.
- **Doppler curve** - `BuildDopplerCurveUseCase`: `delta_f = f_nom * (v_r / c)`, 31
  samples between AOS and LOS, range-rate from `predict4java`.
- **Reception probability** - `EvaluateReceptionProbabilityUseCase`:
  `0.55 * sin(E_max) + 0.45 * (good / total)`. With `total < 3` the history term
  falls back to a neutral 0.5. Classified into
  `UNLIKELY / NEUTRAL / PROMISING / NO_DATA`.
- **Sky arc** - `buildSkyArcFor`: 81 (azimuth, elevation) samples; the polar
  plot splits the polyline at below-horizon dips to avoid chords across the disc.
- **Ground tracks** - `buildGroundTrack` samples the sub-satellite point from the
  TLE, splitting segments at the antimeridian (`|delta_lon| > 180 deg`); the orbital
  period for the full-orbit drawing is clamped to `[40, 200]` min to guard
  against degenerate TLEs.
- **SatDump decoder detection** - `SatDumpSupport.isSupported(satelliteName)`
  matches the satellite name against tokens extracted from the
  [SatDump](https://github.com/SatDump/SatDump/tree/master/resources/pipelines)
  pipeline files (substring, case-insensitive).
- **Alarms** - `AlarmManager.setExactAndAllowWhileIdle` for second-level
  precision; `BootReceiver` + `AlarmRescheduleWorker` restore scheduled alarms
  after reboot / app update.
- **Lazy observation fetch** - `refreshPasses()` is cache-only; after the list
  renders, `ensureHistoryForVisible()` pulls history for visible satellites
  sequentially with a 700 ms throttle (6 h per-satellite TTL).

### Notifications

Three channels (`NotificationHelper`): TLE sync (low), pass alerts (high, sound,
toggled from the UI), and space weather (placeholder for a future K-index feed).

---

## Architecture

Clean Architecture across three layers plus DI in a separate package:

```
ui/            <- Compose + ViewModel + StateFlow
domain/        <- models + repository interfaces + use cases + orbit/
data/          <- repository impls + Room (local/) + Retrofit (remote/) + DataStore
di/            <- AppContainer (manual service locator)
alarm/         <- AlarmScheduler + AlarmReceiver + BootReceiver
worker/        <- SatnogsSyncWorker + AlarmRescheduleWorker
notification/  <- channels + helper
```

- **DI**: a hand-written `AppContainer` (no Hilt the graph has ~10 nodes in a
  single process; runtime DI isn't worth the cost).
- **Async**: Coroutines + Flow; heavy compute (propagation, geometry) on
  `Dispatchers.Default`, Room on `Dispatchers.IO`, UI on `Main`.
- **Cache**: Room (FTS4 for satellite-name search, foreign-key cascade across
  satellites / transmitters / observations).
- **Config**: DataStore Preferences API base URLs, location, bands, alarm
  settings, location presets, compact mode.
- **Network**: Retrofit + OkHttp + Moshi. `DynamicBaseUrlInterceptor` swaps the
  host based on an `X-Satnogs-Host: DB | NETWORK` header - one Retrofit instance
  serves both APIs.

Full details: **[`STACK.md`](STACK.md)** and **[`REPORT.md`](REPORT.md)**.

---

## External APIs

| API | Default URL | Endpoints |
|-----|-------------|-----------|
| SatNOGS DB | `https://db.satnogs.org/api/` | `satellites/`, `transmitters/`, `tle/` |
| SatNOGS Network | `https://network.satnogs.org/api/` | `observations/` |
| Celestrak | `https://celestrak.org/NORAD/elements/` | `gp.php?CATNR=<norad>&FORMAT=TLE` |

Both SatNOGS APIs are public, no OAuth. Hosts can be overridden in Settings (e.g.
a local mock at `http://10.0.2.2:8080/`). Celestrak is a manual TLE fallback from
the satellite detail screen when SatNOGS DB has no fresh element set.

---

## Build & run

No Android Studio required - the Android command-line tools plus Gradle are enough.

### Requirements
- **JDK 17** (project toolchain)
- Android SDK with platform `android-36`
- `local.properties` containing `sdk.dir=<path to SDK>`

### Commands
```bash
# debug APK
JAVA_HOME=/path/to/jdk-17 gradle :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# release App Bundle (the Play artifact)
JAVA_HOME=/path/to/jdk-17 gradle :app:bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab

# unit tests
gradle :app:testDebugUnitTest

# install on a connected device / emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# launch
adb shell monkey -p pl.put.observationcompanion -c android.intent.category.LAUNCHER 1
```

### Signing a release for Google Play
`bundleRelease` is signed with the **upload keystore** only when these
environment variables are present; otherwise it falls back to the debug key so
the build still completes for verification (such an artifact **cannot** be
uploaded to Play):

```bash
export KEYSTORE_PATH=/path/to/my-upload-key.jks   # optional; defaults to ./my-upload-key.jks
export STORE_PASSWORD=...
export KEY_ALIAS=upload                            # optional; defaults to "upload"
export KEY_PASSWORD=...
gradle :app:bundleRelease
```

R8/minification is currently **off**: keep rules are drafted (but untested) in
`proguard-rules.pro`. Verify a release build on a real device before enabling
`isMinifyEnabled`.

---

## Tests

JVM unit tests live in `app/src/test/`, covering the compute core - propagation,
band matching, reception scoring, the Doppler curve, and the TLE parser.
Instrumentation tests (Roborazzi) are not configured.

> The Robolectric example test runs under SDK 34 because emulating SDK 36
> requires JDK 21, while the project toolchain is JDK 17.

---

## Status & limitations

- Builds and installs from the command line (debug APK ~24 MB).
- Room migration uses `fallbackToDestructiveMigration` - on a DB version bump the
  local cache is wiped and rebuilt on first sync (acceptable, since everything is
  a cache).
- SatNOGS Network throttles aggressively (429); the repository uses exponential
  backoff with jitter, and observation history is fetched lazily per satellite.
- No instrumentation tests (would require an AVD / device).
- The Space Weather (K-index) channel exists but has no fetcher yet.

### Before publishing
- This is coursework - check your institution's policy before publishing publicly.
- Google Play requires a **privacy policy** (the app requests fine location) and a
  completed **Data safety** form.
- Add a `LICENSE` file. Note that `predict4java` is GPL-licensed, which constrains
  the distribution license you can choose.
