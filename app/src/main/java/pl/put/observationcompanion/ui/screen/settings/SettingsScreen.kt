package pl.put.observationcompanion.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.put.observationcompanion.domain.model.AntennaBand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsState by viewModel.userSettingsState.collectAsState()
    val scrollState = rememberScrollState()

    var dbUrlInput by remember { mutableStateOf("") }
    var networkUrlInput by remember { mutableStateOf("") }

    // Synchronize inputs when preference loads
    LaunchedEffect(settingsState) {
        settingsState?.let {
            dbUrlInput = it.dbBaseUrl
            networkUrlInput = it.networkBaseUrl
        }
    }

    // Monitor background sync feedback events
    LaunchedEffect(viewModel.syncEvents) {
        viewModel.syncEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color(0xFFF1F5F9)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFCBD5E1))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF020617) // Slate-950
                )
            )
        },
        modifier = modifier.testTag("settings_screen")
    ) { innerPadding ->
        if (settingsState == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF020617)) // Slate-950
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF6366F1))
            }
        } else {
            val settings = settingsState!!

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF020617)) // Slate-950
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Endpoints Configuration
                Text(
                    text = "API SERVER CONFIGURATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF818CF8), // Indigo-400
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                OutlinedTextField(
                    value = dbUrlInput,
                    onValueChange = {
                        dbUrlInput = it
                        viewModel.updateDbBaseUrl(it)
                    },
                    label = { Text("SatNOGS Database API URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("db_url_textfield")
                )

                OutlinedTextField(
                    value = networkUrlInput,
                    onValueChange = {
                        networkUrlInput = it
                        viewModel.updateNetworkBaseUrl(it)
                    },
                    label = { Text("SatNOGS Network API URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("network_url_textfield")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info URL",
                        tint = Color(0xFF64748B), // Slate-500
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Updated URLs are loaded dynamically in OkHttp interceptors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8) // Slate-400
                    )
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Section 2: Antenna & Band Settings
                Text(
                    text = "ANTENNA FREQUENCY BANDS (MULTI-SELECT)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF818CF8), // Indigo-400
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                val chunkedBands = AntennaBand.values().toList().chunked(3)
                chunkedBands.forEach { rowBands ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowBands.forEach { band ->
                            val isSelected = settings.antennaBands.contains(band)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.toggleAntennaBand(band) },
                                label = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = band.displayName,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Text(
                    text = "Active Listeners:\n" + settings.antennaBands.joinToString("\n") { "• ${it.displayName}: ${it.frequencyRange.first / 1_000_000} - ${it.frequencyRange.last / 1_000_000} MHz" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFA5B4FC), // Indigo-300
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                HorizontalDivider(color = Color(0xFF1E293B))

                // Section 3: Alarm configurations
                Text(
                    text = "SCHEDULING ALARMS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF818CF8), // Indigo-400
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Enable Pass Countdown Alarms", fontWeight = FontWeight.SemiBold, color = Color(0xFFF1F5F9))
                        Text(
                            text = "Sound warning warning alert on AOS arrive",
                            color = Color(0xFF64748B), // Slate-500
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.alarmsEnabled,
                        onCheckedChange = { viewModel.updateAlarmsEnabled(it) },
                        modifier = Modifier.testTag("global_alarms_switch")
                    )
                }

                if (settings.alarmsEnabled) {
                    Column {
                        var localLeadTime by remember(settings.alarmLeadTimeMinutes) { mutableFloatStateOf(settings.alarmLeadTimeMinutes.toFloat()) }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Warning Lead Time", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFCBD5E1))
                            Text("${localLeadTime.toInt()} minutes before AOS", fontWeight = FontWeight.Bold, color = Color(0xFFA5B4FC))
                        }
                        Slider(
                            value = localLeadTime,
                            onValueChange = { localLeadTime = it },
                            onValueChangeFinished = { viewModel.updateAlarmLeadTime(localLeadTime.toInt()) },
                            valueRange = 1f..15f,
                            steps = 14,
                            modifier = Modifier.testTag("lead_time_slider")
                        )
                    }
                }

                Column {
                    var localMinElevation by remember(settings.minElevation) { mutableFloatStateOf(settings.minElevation.toFloat()) }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Minimum Horizon Evaluation cutoff", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFCBD5E1))
                        Text("${localMinElevation.toInt()}°", fontWeight = FontWeight.Bold, color = Color(0xFFA5B4FC))
                    }
                    Slider(
                        value = localMinElevation,
                        onValueChange = { localMinElevation = it },
                        onValueChangeFinished = { viewModel.updateMinElevation(localMinElevation.toDouble()) },
                        valueRange = 0f..45f,
                        steps = 9,
                        modifier = Modifier.testTag("elevation_slider")
                    )
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Section 4: Database Actions
                Button(
                    onClick = { viewModel.triggerForceSync() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981), // Emerald-500 for secondary action success flow
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("force_sync_button")
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = "Manual Sync")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("FORCE TELEMETRY SYNC (24H)", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF1E293B))
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "LSF SatNOGS Companion · v1.2",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Made by Filip Poplewski",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

}

