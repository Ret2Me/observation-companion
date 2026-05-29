package pl.put.observationcompanion.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val stats by viewModel.stats.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "STATS",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF020617))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020617))
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val s = stats
            if (s == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF818CF8))
                }
            } else {
                SyncCard(s)
                CatalogCard(s)
                BandBreakdownCard(s)
                ObservationsCard(s)
                StorageCard(s)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = Color(0xFF6366F1)
    )
}

@Composable
private fun StatCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color(0xFFE2E8F0)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF94A3B8))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SyncCard(s: Stats) {
    StatCard {
        SectionHeader("SYNC STATE")
        // Show only relative age in the value column - absolute timestamps
        // were overflowing on narrow phones. Tap-and-hold could reveal the
        // exact time later if we ever need it.
        StatRow("Last network sync", s.lastSync?.let { humanAge(it) } ?: "never")
        StatRow("Newest TLE epoch", s.newestTleEpoch?.let { humanAge(it) } ?: "-")
        StatRow(
            "Oldest TLE epoch",
            s.oldestTleEpoch?.let { humanAge(it) } ?: "-",
            valueColor = if (s.oldestTleEpoch?.let { Duration.between(it, Instant.now()).toDays() > 30 } == true)
                Color(0xFFFBBF24) else Color(0xFFE2E8F0)
        )
    }
}

@Composable
private fun CatalogCard(s: Stats) {
    StatCard {
        SectionHeader("CATALOG")
        StatRow("Satellites (total)", s.satellitesTotal.toString())
        StatRow("Satellites (active)", s.satellitesActive.toString(), Color(0xFFA5B4FC))
        StatRow("With decoders", s.satellitesWithDecoder.toString())
        StatRow("Transmitters (total)", s.transmittersTotal.toString())
        StatRow("Transmitters (active)", s.transmittersActive.toString(), Color(0xFFA5B4FC))
        StatRow("TLEs", s.tlesTotal.toString())
        val coverage = if (s.satellitesActive > 0)
            "${(100.0 * s.tlesTotal / s.satellitesActive).roundToInt()}%" else "-"
        StatRow("TLE coverage (TLEs / active sats)", coverage)
    }
}

@Composable
private fun BandBreakdownCard(s: Stats) {
    StatCard {
        SectionHeader("ACTIVE TRANSMITTERS BY BAND")
        s.transmittersPerBand.forEach { (band, count) ->
            StatRow(band.displayName, count.toString())
        }
        if (s.transmittersOutOfBand > 0) {
            StatRow(
                "Out of all configured bands",
                s.transmittersOutOfBand.toString(),
                Color(0xFF64748B)
            )
        }
    }
}

@Composable
private fun ObservationsCard(s: Stats) {
    StatCard {
        SectionHeader("OBSERVATIONS (CACHED)")
        StatRow("Total", s.observationsTotal.toString())
        StatRow("Good", s.observationsGood.toString(), Color(0xFF34D399))
        StatRow("Failed", s.observationsFailed.toString(), Color(0xFFFB7185))
        val ratio = if (s.observationsTotal > 0)
            "${(100.0 * s.observationsGood / s.observationsTotal).roundToInt()}%" else "-"
        StatRow("Success rate", ratio)
    }
}

@Composable
private fun StorageCard(s: Stats) {
    StatCard {
        SectionHeader("STORAGE")
        StatRow("Room DB on disk", formatBytes(s.dbSizeBytes))
    }
}

private fun humanAge(t: Instant): String {
    val d = Duration.between(t, Instant.now())
    val hours = d.toHours()
    return when {
        hours < 1 -> "${d.toMinutes()} min ago"
        hours < 48 -> "$hours h ago"
        else -> "${d.toDays()} d ago"
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1024.0 / 1024.0)
}
