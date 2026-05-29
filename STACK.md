# Observation Companion - Technology Stack

## Language & build
- **Kotlin** 2.0.x (K2 compiler) + **Coroutines** + **Flow**
- **Gradle 9** + Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- **JDK 17** (toolchain), target SDK 36, min SDK 24 (Android 7.0+)

## UI
- **Jetpack Compose** (Compose BOM) - the entire UI, no XML
- **Material 3**
- **Compose Navigation** - navigation between screens (Passes -> Detail / Location / Settings / Stats)
- **Coil Compose** - image loading

## Map and charts
- **OSMDroid** 6.x - OSM map (ground track + location picker), no Google Play Services
- Custom `SkyMapChart` (polar plot of azimuth by elevation, Canvas) and `DopplerChart`
  (delta-f over time, Canvas) - minimal dependency surface
- Custom `GroundTrackMap` on OSMDroid with a dark-tile filter (ColorMatrix
  desaturate + invert) and four polyline layers (selected /
  previous / next / full orbit)

## Architecture
- **Clean Architecture** (`data` / `domain` / `ui`)
- **MVI-lite** - a single `StateFlow<UiState>` per screen, the UI fully driven
  by state collected through `collectAsStateWithLifecycle`
- **Dependency Injection: manual `AppContainer`** (service locator). Hilt is
  not used; the whole graph is ~10 nodes (Room, Retrofit, repositories, use
  cases, SatPropagator), single process

## Networking
- **Retrofit 2.11** + **OkHttp 4.12** + **Moshi** (kotlin-reflect + KSP codegen)
- **`DynamicBaseUrlInterceptor`** reads the `X-Satnogs-Host: DB|NETWORK` header
  and swaps the request host from DataStore - one Retrofit serves both APIs
  (`SatnogsDbApi`, `SatnogsNetworkApi`)
- `CelestrakApi` uses `@Url` (an absolute URL bypasses the interceptor)
- `HttpLoggingInterceptor` (BODY) + user-agent interceptor (`LSF-SatNOGS-Companion/1.2`)

## Database
- **Room** + KSP, entities: `satellites`, `transmitters`, `tles`, `observations`,
  `pass_cache`, FTS4 for `satellites_fts`
- Migration strategy: `fallbackToDestructiveMigration` - all data is a
  cache, so wiping it on a schema version bump is acceptable

## Preferences
- **DataStore Preferences** - API base URLs, observer location,
  antenna bands, alarm settings (lead-time, elevation threshold, master switch),
  user-defined location presets, compact mode toggle

## Orbital computation
- **predict4java** 1.1.3 (Apache 2.0) - SGP4/SDP4
- Custom `SatPropagator` wrapper that wraps the API in domain types
  (degrees / km / `Instant`)
- Per-call timeout (`Future.get(3s)` on a dedicated cached
  `ExecutorService "sat-prop"`) - the library can hang on
  degenerate orbits
- Geometric pre-filter (`maxPossibleElevationDeg`) - a fast test of "will
  this orbit even rise above `E_min` from this latitude"
  before we run the full propagation

## Background & alerts
- **WorkManager 2.9** - `SatnogsSyncWorker` (sync of TLEs / satellites /
  transmitters), `AlarmRescheduleWorker` (re-arming alarms after a reboot)
- **AlarmManager** (`setExactAndAllowWhileIdle`) - pass alarms,
  second-level precision, request code `pass.aos.hashCode()`
- **NotificationCompat** + 3 channels (`NotificationHelper`):
  TLE sync (low), Pass alerts (high + sound), Space weather (low, placeholder)

## Location
- **FusedLocationProviderClient** (Google Play Services Location) with a
  fallback to the platform `LocationManager`

## Tests
- **JUnit5** + **MockK** + **kotest-assertions** + **Turbine** (Flow assertions)
- **Roborazzi** available in the build script (screenshot tests) - not yet
  configured for a Compose host

## Tooling
- `kotlinx-coroutines-android` + `kotlinx-coroutines-play-services`
- Standard `android.util.Log`
