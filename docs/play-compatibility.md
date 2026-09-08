# Google Play compatibility checks

The warnings reported by Play refer to version code 1 (version name 1.2).
Changes below are for the next GMS release, version code 2 (version name 1.3).
If code 2 has already been uploaded to any Play track, increment it before
building another bundle. Renaming the AAB does not change its version code.

## Fragment SDK

Google Play services pulled in `androidx.fragment:fragment:1.1.0` transitively.
The GMS flavor now explicitly depends on `1.8.9`. This does not add Google
dependencies to the FOSS flavor.

Check the resolved release dependency, not just the version catalog:

```sh
./gradlew :app:dependencyInsight --configuration gmsReleaseRuntimeClasspath \
  --dependency androidx.fragment:fragment --single-path
```

The built AAB also includes the resolved version:

```sh
unzip -p app/build/outputs/bundle/gmsRelease/app-gms-release.aab \
  base/root/META-INF/androidx.fragment_fragment.version
```

## Edge-to-edge

- Activity Compose is updated to 1.12.4, including the Android 15 inset fixes
  introduced in Activity 1.12.0.
- `MainActivity` uses AndroidX `enableEdgeToEdge` with transparent system bars
  and light system icons for the fixed dark app palette.
- `Theme.kt` no longer calls `Window.setStatusBarColor` or
  `Window.setNavigationBarColor` during composition.
- The navigation host consumes horizontal display-cutout and keyboard insets.
  Material Scaffold and top app bars own the remaining system-bar spacing.
- `adjustResize` enables delivery of keyboard insets on older Android versions.
- No edge-to-edge opt-out is used. The target SDK remains 36 and the minimum
  SDK remains 24.

These changes remove the app-owned deprecated color setters. They do **not**
prove that Play's deprecated-API warning will disappear: the AndroidX Activity
1.12.4 implementation itself still contains these setters, including in its
API 35 implementation. Do not strip library methods or disable edge-to-edge
to silence a scanner. If Play flags the new bundle, expand its list of call
sites and distinguish application code from library code.

Activity 1.13.0 and Fragment 1.9.0 are also available. This patch targets the
reported compatibility issues rather than updating every dependency to the
latest release. Inspection of Activity 1.13.0 confirmed that its API 35
implementation still calls both deprecated color setters, too.

## Verification

```sh
./gradlew :app:lintGmsRelease :app:testGmsDebugUnitTest :app:bundleGmsRelease
./gradlew :app:connectedGmsDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=pl.put.observationcompanion.EdgeToEdgeTest
```

The instrumentation regression tests check system icon appearance, navigation
control bounds, navigation between the main screens and a coordinate field
with the keyboard open. Run on Android 15 and 16 with gesture and three-button
navigation, in portrait and landscape, including a display cutout. Also smoke
test an older supported Android version. Compilation of the tests alone is
not a successful device test.

Core-library desugaring is enabled for `java.time` used on Android 7 (API 24/25).
Without it, release lint reported API compatibility errors unrelated to the
Play edge-to-edge warnings.

Before production, upload the signed new bundle to internal testing, inspect
the warnings for **that version code**, and check its pre-launch report.
The presence or absence of these three warnings is not a guarantee of review
approval; privacy and Data safety declarations must also match the application.

## References

- [Fragment releases](https://developer.android.com/jetpack/androidx/releases/fragment)
- [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity)
- [Compose edge-to-edge setup](https://developer.android.com/develop/ui/compose/system/setup-e2e)
- [Compose insets](https://developer.android.com/develop/ui/compose/system/insets-ui)
- [Android 15 edge-to-edge changes](https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge)
