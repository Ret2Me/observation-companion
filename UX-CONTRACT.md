# UX Contract

## Form pattern

Location and settings forms use Material 3 outlined fields with persistent labels. Numeric coordinate fields validate before enabling the primary action. Existing values remain visible while editing. Validation appears next to the affected field and never relies on color alone.

## Feedback pattern

Long running sync and orbit work uses an inline progress indicator with a short current-stage label. Brief completion messages use the existing application event channel. Errors keep the attempted values and offer a retry action where retry is possible.

## Dialog pattern

Dialogs are reserved for focused naming or editing tasks and for irreversible local deletion. The primary action is right aligned and uses sentence case. Cancel remains available without changing saved data.

## Detail disclosure pattern

Satellite detail opens with the map and a partially expanded bottom sheet. The collapsed state contains NORAD identity, status, AOS, TCA, LOS, receiver match, reception history and decoder availability. The expanded state adds sky view, Doppler shift, satellite notes, TLE controls, transmitters and SatNOGS observations.

## Navigation pattern

The pass list is the operational home screen. Statistics, observer location and settings remain direct top-app-bar actions. Satellite rows and cards open the same detail route. Back navigation always returns to the previous list position.

## State conventions

Loading preserves layout dimensions where possible. Empty lists explain what is missing and provide a relevant action. Stale TLE data uses amber and a text warning. Failed observations use red with a visible label. Disabled actions remain legible and unavailable to accessibility actions.

## Ownership map

| Pattern | Owner | Used by |
| --- | --- | --- |
| App palette and typography | `ui/theme` | All screens |
| Operational pass summary | `PassCard` and detail sheet | Pass list, satellite detail |
| Reception state | `SuccessRateChip`, `ReceptionProbabilityChip` | Pass list, satellite detail |
| Map treatment and track legend | `GroundTrackMap` | Satellite detail, observer location |
| Form fields and dialogs | Material 3 components | Settings, observer location |

## Accessibility baseline

All icon-only actions require a content description. Interactive controls keep at least a 48 dp target. Body text targets readable contrast on the fixed dark surfaces. Color-coded data also includes a text label, line style or both.
