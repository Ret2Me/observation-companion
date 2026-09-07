# Google Play Data safety audit

Last reviewed against version 1.3 (`versionCode 2`) on September 7, 2026.
This is an implementation audit, not legal advice. Recheck it whenever an SDK,
permission, endpoint or data flow changes.

## Recommended Play Console answers

The conservative declaration for the current `gmsRelease` build is:

| Play Console item | Recommended answer | Evidence |
| --- | --- | --- |
| Does the app collect or share required user data types? | Yes | The map library requests remote tiles for the viewed area, and the GMS flavor can obtain a location through Google Play services. Third-party SDK transfers count in the form. |
| Approximate location | Collected and shared | Map tile coordinates reveal the viewed area to OpenStreetMap. Google Play services can process location to return a fix. |
| Precise location | Collected and shared | The user can grant fine location and zoom the map enough for requested tile coordinates to describe an area below 3 km². |
| Purpose | App functionality | Location drives observer selection, pass prediction and the map. There is no advertising, profiling or analytics. |
| Required or optional | Optional | A user can refuse location permission and use a manually selected or default observation point. |
| Ephemeral processing | No | The app uses location immediately and stores the selected observer point locally. Third-party request-log retention cannot be guaranteed to be ephemeral. |
| Data encrypted in transit | Yes | Default SatNOGS, CelesTrak, OpenStreetMap and Google endpoints use HTTPS. |
| Account creation | No | The app has no account system or Developer-operated backend. |
| Data deletion request | Not applicable to a Developer account | Local data is removed by clearing app storage or uninstalling. Third-party services control their own request logs. |

Do not declare advertising data, financial information, contacts, messages,
photos, audio, files, health data, app interactions, crash logs, diagnostics or
device identifiers based on the current source. No advertising, analytics,
crash-reporting or telemetry SDK is present.

The `collected and shared` location answer is intentionally conservative. The
Developer does not receive location on a private backend, but Play defines
collection to include data sent off-device by libraries and SDKs. The app sends
map tile identifiers to a third-party tile server, and those identifiers encode
the visible geographic area. Play also requires SDK behavior to be reflected in
the declaration.

## Verified implementation

* `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are requested only while
  the app is in use. There is no background-location permission.
* `GmsLocationProvider` invokes `FusedLocationProviderClient.getCurrentLocation`
  once after a user action. It does not subscribe to continuous tracking.
* Latitude, longitude and altitude are stored in Android DataStore. Presets can
  contain the same values. They are used locally for orbital calculations.
* SatNOGS observation requests contain NORAD ID, status and result limit, not the
  observer coordinates. CelesTrak receives only the NORAD ID.
* OSMDroid requests OpenStreetMap tiles for the map viewport. The remote service
  receives the tile path, IP address, time and app user agent.
* Room stores downloaded public satellite, transmitter, TLE and observation
  data locally. No user account or uploaded user content exists.
* Cloud backup and device-to-device transfer are disabled in the manifest and
  explicitly excluded for every Android backup domain in both rule formats.

## Before submitting the form

1. Compare the merged release manifest and dependency graph with this audit.
2. Check the current Google Play SDK Index entry for
   `com.google.android.gms:play-services-location`.
3. Keep the store declaration and `docs/privacy-policy.html` consistent.
4. If the map moves to offline tiles or a first-party proxy, reassess whether
   location is still shared and whether it is collected at all.
5. If analytics, crash reporting, ads or telemetry are added, redo the entire
   form before releasing that build.

Official guidance:

* [Play Console Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469)
* [Android data-use declaration guide](https://developer.android.com/privacy-and-security/declare-data-use)
* [Google Play services disclosure guidance](https://developers.google.com/android/guides/play-data-disclosure)
