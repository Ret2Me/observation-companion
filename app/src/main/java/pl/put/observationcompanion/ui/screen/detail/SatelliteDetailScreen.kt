package pl.put.observationcompanion.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.put.observationcompanion.domain.model.Observation
import pl.put.observationcompanion.domain.model.SatDumpSupport
import pl.put.observationcompanion.domain.model.Transmitter
import pl.put.observationcompanion.ui.component.DopplerChart
import pl.put.observationcompanion.ui.component.GroundTrackMap
import pl.put.observationcompanion.ui.component.ReceptionProbabilityChip
import pl.put.observationcompanion.ui.component.SkyMapChart
import pl.put.observationcompanion.ui.component.SuccessRateChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val bg = Color(0xFF020617)
private val card = Color(0xFF0F172A)
private val border = Color(0xFF1E293B)
private val accent = Color(0xFF818CF8)
private val textPrimary = Color(0xFFE2E8F0)
private val textMuted = Color(0xFF94A3B8)
private val textFaint = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteDetailScreen(
    viewModel: SatelliteDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val livePosition by viewModel.liveSatPosition.collectAsStateWithLifecycle()
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    DisposableEffect(Unit) {
        viewModel.startLiveTracking()
        onDispose { viewModel.stopLiveTracking() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state?.pass?.satelliteName ?: "Satellite",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFCBD5E1))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
            )
        }
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize().background(bg).padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PassSummarySection(s, timeFmt)

            s.satellite?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                SectionCard {
                    SectionLabel("ABOUT")
                    Spacer(Modifier.height(6.dp))
                    Text(desc, fontSize = 13.sp, color = textMuted)
                }
            }

            // Geometry charts
            SectionCard {
                SectionLabel("PASS GEOMETRY")
                Spacer(Modifier.height(10.dp))
                Text("SKY VIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = textFaint)
                Spacer(Modifier.height(4.dp))
                SkyMapChart(points = s.sky, modifier = Modifier.fillMaxWidth(0.85f))
                Spacer(Modifier.height(14.dp))
                DopplerChart(dopplerPoints = s.doppler, modifier = Modifier.fillMaxWidth())
            }

            // Ground track + Celestrak
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionLabel("GROUND TRACK")
                    Spacer(Modifier.weight(1f))
                    TleEpochBadge(s.tle?.epoch)
                }
                Spacer(Modifier.height(10.dp))
                GroundTrackMap(
                    track = s.groundTrack,
                    observerLat = s.observerLat,
                    observerLon = s.observerLon,
                    livePosition = livePosition,
                    previousTrack = s.previousPassTrack,
                    nextTrack = s.nextPassTrack,
                    fullTrack = s.fullOrbitTrack
                )
                Spacer(Modifier.height(8.dp))
                TrackLegend(
                    hasPrevious = s.previousPassTrack.isNotEmpty(),
                    hasNext = s.nextPassTrack.isNotEmpty(),
                    hasFullOrbit = s.fullOrbitTrack.isNotEmpty()
                )
                Spacer(Modifier.height(6.dp))
                LivePositionLine(livePosition)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { viewModel.refreshTleFromCelestrak() },
                        enabled = !s.refreshingTle,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (s.refreshingTle) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (s.tle == null) "FETCH TLE (CELESTRAK)" else "REFRESH TLE (CELESTRAK)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                s.tleNotice?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = textMuted, fontFamily = FontFamily.Monospace)
                }
            }

            // Transmitters
            SectionCard {
                SectionLabel("TRANSMITTERS (${s.transmitters.size})")
                Spacer(Modifier.height(8.dp))
                if (s.transmitters.isEmpty()) {
                    Text("No transmitters listed for this satellite.", fontSize = 12.sp, color = textFaint)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.transmitters.forEach { tx -> TransmitterRow(tx) }
                    }
                }
            }

            // Observation history
            SectionCard {
                SectionLabel("RECENT OBSERVATIONS (SATNOGS)")
                Spacer(Modifier.height(8.dp))
                ObservationTable(s.observations, dateTimeFmt, loaded = s.observationsLoaded)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PassSummarySection(s: SatelliteDetail, timeFmt: DateTimeFormatter) {
    val pass = s.pass
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("NORAD ${pass.noradId}", fontSize = 12.sp, color = textMuted, fontFamily = FontFamily.Monospace)
                s.satellite?.id?.takeIf { it.isNotBlank() }?.let {
                    Text("SatNOGS ID: $it", fontSize = 11.sp, color = textFaint, fontFamily = FontFamily.Monospace)
                }
            }
            SuccessRateChip(status = pass.status)
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TimeColumn("AOS", timeFmt.format(pass.aos), "Az ${pass.startAzimuth.roundToInt()}°", Alignment.Start)
            TimeColumn("MAX EL", "${pass.maxElevation.roundToInt()}°", "Az ${pass.tcaAzimuth.roundToInt()}°", Alignment.CenterHorizontally, accentValue = true)
            TimeColumn("LOS", timeFmt.format(pass.los), "Az ${pass.endAzimuth.roundToInt()}°", Alignment.End)
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RECEPTION CHANCE", fontSize = 11.sp, color = textFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            ReceptionProbabilityChip(
                probability = pass.receptionProbability,
                goodCount = pass.observationGoodCount,
                totalCount = pass.observationTotalCount
            )
        }

        Spacer(Modifier.height(10.dp))

        DecoderBadgeRow(
            hasSatnogs = pass.satelliteHasDecoder,
            hasSatDump = SatDumpSupport.isSupported(pass.satelliteName)
        )
    }
}

@Composable
private fun DecoderBadgeRow(hasSatnogs: Boolean, hasSatDump: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DecoderBadge(
            label = "SatNOGS",
            present = hasSatnogs,
            presentColor = Color(0xFF34D399)
        )
        DecoderBadge(
            label = "SatDump",
            present = hasSatDump,
            presentColor = Color(0xFF22D3EE)
        )
    }
}

@Composable
private fun DecoderBadge(label: String, present: Boolean, presentColor: Color) {
    val bg = if (present) presentColor.copy(alpha = 0.15f) else Color(0xFF111C33)
    val fg = if (present) presentColor else textFaint
    val text = if (present) "$label  ✓" else "$label  ✗"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TimeColumn(
    label: String,
    value: String,
    sub: String,
    align: Alignment.Horizontal,
    accentValue: Boolean = false
) {
    Column(horizontalAlignment = align) {
        Text(label, fontSize = 10.sp, color = if (accentValue) accent else textFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (accentValue) Color(0xFFA5B4FC) else textPrimary)
        Text(sub, fontSize = 11.sp, color = textFaint)
    }
}

@Composable
private fun TransmitterRow(tx: Transmitter) {
    val statusText = tx.status?.replaceFirstChar { it.uppercase() } ?: if (tx.isActive) "Active" else "Inactive"
    val statusColor = when {
        tx.status?.equals("active", true) == true || (tx.status == null && tx.isActive) -> Color(0xFF34D399)
        tx.status?.equals("inactive", true) == true -> Color(0xFFFBBF24)
        else -> Color(0xFFFB7185)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111C33))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.4f MHz".format(tx.frequency / 1_000_000.0),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(statusText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOfNotNull(
                tx.mode?.takeIf { it.isNotBlank() }?.let { "Mode: $it" },
                tx.modulation?.takeIf { it.isNotBlank() }?.let { "Type: $it" }
            ).joinToString("  •  ").ifBlank { "No mode/type info" },
            fontSize = 11.sp,
            color = textMuted,
            fontFamily = FontFamily.Monospace
        )
        tx.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, fontSize = 11.sp, color = textFaint)
        }
    }
}

@Composable
private fun ObservationTable(observations: List<Observation>, fmt: DateTimeFormatter, loaded: Boolean) {
    if (!loaded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = accent
            )
            Spacer(Modifier.width(8.dp))
            Text("Fetching from SatNOGS Network…", fontSize = 12.sp, color = textMuted)
        }
        return
    }
    if (observations.isEmpty()) {
        Text(
            "No observations cached yet. SatNOGS Network rate-limits aggressively - " +
                "pull-to-refresh on the passes list or try again later.",
            fontSize = 12.sp,
            color = textFaint
        )
        return
    }
    val good = observations.count { it.status.equals("good", true) }
    val failed = observations.count { it.status.equals("failed", true) }

    Text(
        text = "$good good · $failed failed · ${observations.size} total",
        fontSize = 11.sp,
        color = textMuted,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        Text("WHEN", fontSize = 10.sp, color = textFaint, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
        Text("STATION", fontSize = 10.sp, color = textFaint, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("STATUS", fontSize = 10.sp, color = textFaint, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = border)

    observations.take(20).forEach { obs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
        ) {
            Text(
                fmt.format(obs.timestamp),
                fontSize = 12.sp,
                color = textMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1.1f)
            )
            Text(
                obs.stationName ?: "-",
                fontSize = 11.sp,
                color = textMuted,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            val (label, color) = when (obs.status.lowercase()) {
                "good" -> "GOOD" to Color(0xFF34D399)
                "failed" -> "FAILED" to Color(0xFFFB7185)
                else -> "UNKNOWN" to textFaint
            }
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        }
        HorizontalDivider(color = Color(0xFF111C33))
    }
}

@Composable
private fun TleEpochBadge(epoch: Instant?) {
    if (epoch == null) {
        Text("no TLE epoch", fontSize = 10.sp, color = textFaint, fontFamily = FontFamily.Monospace)
        return
    }
    val ageDays = ChronoUnit.DAYS.between(epoch, Instant.now())
    val color = if (ageDays >= 14) Color(0xFFFBBF24) else textMuted
    Text("TLE age: ${ageDays}d", fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TrackLegend(hasPrevious: Boolean, hasNext: Boolean, hasFullOrbit: Boolean) {
    androidx.compose.foundation.layout.FlowRow(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LegendSwatch(color = Color(0xFFA5B4FC), dashed = false, label = "Selected pass")
        if (hasPrevious) LegendSwatch(color = Color(0xFFFBBF24), dashed = true, label = "Previous")
        if (hasNext) LegendSwatch(color = Color(0xFF34D399), dashed = true, label = "Next")
        if (hasFullOrbit) LegendSwatch(color = Color(0xFF22D3EE), dashed = false, label = "Full orbit")
    }
}

@Composable
private fun LegendSwatch(color: Color, dashed: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(width = 22.dp, height = 6.dp)
        ) {
            val strokeWidth = 3f
            if (dashed) {
                val dash = 6f
                val gap = 4f
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(x, size.height / 2f),
                        end = androidx.compose.ui.geometry.Offset((x + dash).coerceAtMost(size.width), size.height / 2f),
                        strokeWidth = strokeWidth
                    )
                    x += dash + gap
                }
            } else {
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                    strokeWidth = strokeWidth
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = textMuted, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LivePositionLine(live: pl.put.observationcompanion.domain.model.GroundTrackPoint?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (live == null) textFaint else Color(0xFF22D3EE))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (live == null) {
                "Live position unavailable (no TLE)"
            } else {
                "Live: %.2f°, %.2f° • updates every 2 s".format(live.latitude, live.longitude)
            },
            fontSize = 11.sp,
            color = textMuted,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(card)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = accent)
}
