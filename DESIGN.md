---
version: alpha
name: Observation Companion
description: Dark field console for experienced satellite radio operators.
colors:
  background: "#020617"
  surface: "#0F172A"
  surface-container: "#111C33"
  surface-container-high: "#1E293B"
  primary: "#6366F1"
  primary-container: "#1E1B4B"
  on-primary-container: "#A5B4FC"
  secondary: "#818CF8"
  tertiary: "#10B981"
  text-primary: "#F1F5F9"
  text-secondary: "#94A3B8"
  outline: "#1E293B"
  outline-variant: "#1E293B"
  success: "#34D399"
  warning: "#FBBF24"
  error: "#F43F5E"
typography:
  sans:
    fontFamily: "Roboto, system-ui, sans-serif"
  mono:
    fontFamily: "Roboto Mono, ui-monospace, monospace"
rounded:
  control: "0.375rem"
  card: "0.5rem"
  sheet: "1rem"
spacing:
  xs: "0.25rem"
  sm: "0.5rem"
  md: "1rem"
  lg: "1.5rem"
components:
  top-app-bar:
    description: "Quiet navigation bar with one title and standard Material icons."
  pass-card:
    description: "Dense operational summary with time, geometry, receiver and alarm state."
  map-workspace:
    description: "Full available canvas reserved for the ground track map."
  detail-sheet:
    description: "Partially expanded receiver summary with complete detail on expansion."
  chart:
    description: "Flat data view using semantic track colors and monospace values."
---

# Product context

Observation Companion is a mobile field tool for experienced amateur satellite radio operators. It prioritizes pass timing, azimuth, elevation, frequency, receiver history, decoder support and orbital freshness over onboarding or decorative explanation.

# Visual direction

The interface follows the original fixed Material 3 dark scheme. Deep navy and slate surfaces stay quiet while indigo marks selection and the active pass. Green means usable or available, amber means stale or uncertain, cyan identifies secondary orbit data, and rose is reserved for failure.

The ground track map is the primary surface on satellite details. A bottom sheet keeps the next operational data within thumb reach and reveals the full technical record without replacing the map context.

# Typography and content

Use the Android system sans family for interface language and monospace only for measurements, identifiers, coordinates, time and frequency. Use sentence case for interface headings. Keep established radio abbreviations such as AOS, TCA, LOS, RX, TLE and NORAD.

Do not use emoji, decorative punctuation, faux terminal prefixes, marketing slogans, artificial status prose or oversized tracking. Use Material icons for controls. Use a vertical bar only when compact technical values must share one line.

# Shape and elevation

Controls use 6 dp corners, cards and map frames use 8 to 10 dp, and the top corners of the detail sheet use 16 dp. Prefer borders and surface contrast to large shadows. Do not use gradients or glow effects.

# Responsive behavior

The detail map fills the available viewport behind a 276 dp summary sheet. On compact widths, geometry charts stack vertically. At widths of 600 dp and above, sky view and Doppler data share a row. Lists retain 48 dp minimum touch targets and support system font scaling.

# Runtime source of truth

Runtime colors in `app/src/main/java/pl/put/observationcompanion/ui/theme/Color.kt` and the Material mapping in `Theme.kt` are canonical. This document records the intent and must be updated whenever those tokens change.
