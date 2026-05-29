package pl.put.observationcompanion.ui.component

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.put.observationcompanion.alarm.AlarmReceiver
import pl.put.observationcompanion.alarm.AlarmScheduler
import pl.put.observationcompanion.domain.model.Pass
import pl.put.observationcompanion.domain.model.SatDumpSupport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// Predict4java's SGP4 is precise, but TLE epoch drift dominates accuracy:
// past ~14 days, semi-major axis and atmospheric decay are no longer faithful.
private const val TLE_STALENESS_THRESHOLD_DAYS = 14L

@Composable
fun PassCard(
    pass: Pass,
    alarmLeadTime: Int,
    onOpenDetail: (Pass) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isAlarmEnabled by remember {
        mutableStateOf(checkIsAlarmScheduled(context, pass.aos.hashCode()))
    }

    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("pass_card_${pass.satelliteId}")
            .border(
                width = 1.dp,
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onOpenDetail(pass) }
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pass.satelliteName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F5F9),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "NORAD ID: ${pass.noradId} • ${dateFormatter.format(pass.aos)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                SuccessRateChip(status = pass.status)
                IconButton(
                    onClick = { onOpenDetail(pass) },
                    modifier = Modifier.testTag("open_detail_${pass.satelliteId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open satellite details",
                        tint = Color(0xFF818CF8)
                    )
                }
            }

            pass.tleEpoch?.let { epoch ->
                val ageDays = ChronoUnit.DAYS.between(epoch, Instant.now())
                if (ageDays >= TLE_STALENESS_THRESHOLD_DAYS) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = Color(0x66F59E0B),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stale TLE: $ageDays days old - pass times may drift",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text("AOS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        timeFormatter.format(pass.aos),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                    )
                    Text("Az: ${pass.startAzimuth.roundToInt()}°", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("MAX ELEVATION", style = MaterialTheme.typography.labelSmall, color = Color(0xFF818CF8), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Max Elevation Arrow",
                            tint = Color(0xFF6366F1),
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(180f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "${pass.maxElevation.roundToInt()}°",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFA5B4FC)
                        )
                    }
                    Text("Az: ${pass.tcaAzimuth.roundToInt()}°", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("LOS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        timeFormatter.format(pass.los),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                    )
                    Text("Az: ${pass.endAzimuth.roundToInt()}°", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "RECEPTION CHANCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                ReceptionProbabilityChip(
                    probability = pass.receptionProbability,
                    goodCount = pass.observationGoodCount,
                    totalCount = pass.observationTotalCount
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            pass.matchedTransmitter?.let { tx ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = "Transmitter icon",
                        tint = Color(0xFF818CF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "RX Match: ${(tx.frequency / 1_000_000.0)} MHz (${tx.modulation ?: "Unknown"} / ${tx.mode ?: "No Mode"})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF818CF8),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, color, text) = if (pass.satelliteHasDecoder) {
                    Triple(Icons.Default.CheckCircle, Color(0xFF34D399), "SatNOGS decoder")
                } else {
                    Triple(Icons.Default.Block, Color(0xFFFB7185), "No SatNOGS decoder")
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val satDumpSupported = SatDumpSupport.isSupported(pass.satelliteName)
                val (icon, color, text) = if (satDumpSupported) {
                    Triple(Icons.Default.CheckCircle, Color(0xFF22D3EE), "SatDump pipeline")
                } else {
                    Triple(Icons.Default.Block, Color(0xFF64748B), "No SatDump pipeline")
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF1E293B))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Sound Alarm ($alarmLeadTime min before AOS)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFCBD5E1)
                )
                Switch(
                    checked = isAlarmEnabled,
                    onCheckedChange = { schedule ->
                        if (schedule) {
                            AlarmScheduler.schedulePassAlarm(context, pass, alarmLeadTime)
                            isAlarmEnabled = true
                            Toast.makeText(context, "Exact alarm scheduled!", Toast.LENGTH_SHORT).show()
                        } else {
                            AlarmScheduler.cancelPassAlarm(context, pass)
                            isAlarmEnabled = false
                            Toast.makeText(context, "Alarm canceled.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    thumbContent = {
                        if (isAlarmEnabled) {
                            Icon(Icons.Default.Alarm, null, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.AlarmOff, null, modifier = Modifier.size(16.dp))
                        }
                    },
                    modifier = Modifier.testTag("alarm_switch_${pass.satelliteId}")
                )
            }
        }
    }
}

@Composable
fun CompactPassCard(
    pass: Pass,
    onOpenDetail: (Pass) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("compact_pass_card_${pass.satelliteId}")
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .clickable { onOpenDetail(pass) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pass.satelliteName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F5F9),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SuccessRateChip(status = pass.status)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("AOS ${timeFormatter.format(pass.aos)}")
                        pass.matchedTransmitter?.let { append("  •  ${"%.3f".format(it.frequency / 1_000_000.0)} MHz") }
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${pass.maxElevation.roundToInt()}°",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFA5B4FC)
                )
                Text("MAX EL", fontSize = 9.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "Open details",
                tint = Color(0xFF475569),
                modifier = Modifier.padding(start = 8.dp).size(18.dp)
            )
        }
    }
}

private fun checkIsAlarmScheduled(context: Context, requestCode: Int): Boolean {
    val intent = Intent(context, AlarmReceiver::class.java).apply {
        action = AlarmReceiver.ACTION_PASS_ALERT
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    return pendingIntent != null
}
