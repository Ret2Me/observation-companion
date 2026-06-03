package pl.put.observationcompanion.ui.screen.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.put.observationcompanion.data.preferences.PreferencesDataSource
import pl.put.observationcompanion.di.AppContainer
import pl.put.observationcompanion.domain.model.AntennaBand
import pl.put.observationcompanion.domain.model.Preset
import pl.put.observationcompanion.ui.component.OBSERVER_DOT_COLOR
import pl.put.observationcompanion.ui.component.darkMapFilter
import pl.put.observationcompanion.ui.component.dotIcon
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
private fun ObserverMap(
    lat: Double,
    lon: Double,
    onLocationTapped: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    // OSMDroid needs a one-time configuration (user agent + cache dir).
    // Tile server admins block clients sending the default UA.
    LaunchedEffect(Unit) {
        org.osmdroid.config.Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        )
        org.osmdroid.config.Configuration.getInstance().userAgentValue =
            "Observation-Companion/1.2 (Android student project, PUT)"
    }

    // Keep a single MapView across recompositions; mutate its center/marker
    // when the observer location changes.
    val mapView = remember {
        org.osmdroid.views.MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(8.0)
            controller.setCenter(org.osmdroid.util.GeoPoint(lat, lon))
            // Same dark/desaturated treatment as GroundTrackMap so the two
            // screens look like the same map, not two different ones.
            overlayManager.tilesOverlay.setColorFilter(darkMapFilter())
        }
    }

    // Recenter + redraw marker whenever the observer settings change (e.g.
    // user picked a built-in observatory or hit Fetch GPS).
    LaunchedEffect(lat, lon) {
        mapView.controller.animateTo(org.osmdroid.util.GeoPoint(lat, lon))
        mapView.overlays.clear()
        val marker = org.osmdroid.views.overlay.Marker(mapView).apply {
            position = org.osmdroid.util.GeoPoint(lat, lon)
            setAnchor(
                org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                org.osmdroid.views.overlay.Marker.ANCHOR_CENTER
            )
            icon = dotIcon(context, OBSERVER_DOT_COLOR, ringPx = 5f, radiusPx = 10f, glow = false)
            title = "Observer"
        }
        mapView.overlays.add(marker)
        // Long-press the map to relocate the observer.
        val tapOverlay = object : org.osmdroid.views.overlay.Overlay() {
            override fun onLongPress(e: android.view.MotionEvent, mv: org.osmdroid.views.MapView): Boolean {
                val proj = mv.projection
                val gp = proj.fromPixels(e.x.toInt(), e.y.toInt()) as org.osmdroid.util.GeoPoint
                onLocationTapped(gp.latitude, gp.longitude)
                return true
            }
        }
        mapView.overlays.add(tapOverlay)
        mapView.invalidate()
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    viewModel: LocationViewModel,
    appContainer: AppContainer,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Fetch live settings directly from the catalog
    val userSettingsFlow = remember { appContainer.settingsRepository.getUserSettingsFlow() }
    val settingsState by userSettingsFlow.collectAsState(initial = null)

    var latInput by remember { mutableStateOf("52.4064") }
    var lonInput by remember { mutableStateOf("16.9252") }
    var altInput by remember { mutableStateOf("80.0") }

    val presets by viewModel.presetsState.collectAsState()
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<Preset?>(null) }

    LaunchedEffect(settingsState) {
        settingsState?.let {
            val dbLat = it.groundLat
            val dbLon = it.groundLon
            val dbAlt = it.groundAlt
            if (latInput.toDoubleOrNull() != dbLat) {
                latInput = String.format("%.4f", dbLat)
            }
            if (lonInput.toDoubleOrNull() != dbLon) {
                lonInput = String.format("%.4f", dbLon)
            }
            if (altInput.toDoubleOrNull() != dbAlt) {
                altInput = String.format("%.1f", dbAlt)
            }
        }
    }

    // Capture positioning toast events
    LaunchedEffect(viewModel.locationEvents) {
        viewModel.locationEvents.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Location runtime permissions request
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            viewModel.requestGpsLocation()
        } else {
            Toast.makeText(context, "Location permission rejected. Standby.", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Observer Location", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color(0xFFF1F5F9)) },
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
        modifier = modifier.testTag("location_screen")
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
                Text(
                    text = "OBSERVER MAP",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF818CF8), // Indigo-400
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                ObserverMap(
                    lat = settings.groundLat,
                    lon = settings.groundLon,
                    onLocationTapped = { newLat, newLon ->
                        viewModel.saveLocation(newLat, newLon, settings.groundAlt)
                    }
                )

                Text(
                    text = "Long-press the map to move the observer.",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )

                // GPS Fetch Button
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("gps_locate_button")
                ) {
                    Icon(Icons.Default.GpsFixed, contentDescription = "GPS Finder")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("FETCH GPS STATION LOCATOR", fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Section 2: Precise coordinate inputs
                Text(
                    text = "MANUAL COORDINATES CONFIGURATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF818CF8), // Indigo-400
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latInput,
                        onValueChange = { latInput = it },
                        label = { Text("Latitude (°)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("latitude_textfield")
                    )

                    OutlinedTextField(
                        value = lonInput,
                        onValueChange = { lonInput = it },
                        label = { Text("Longitude (°)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("longitude_textfield")
                    )
                }

                OutlinedTextField(
                    value = altInput,
                    onValueChange = { altInput = it },
                    label = { Text("Altitude (meters above sea level)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("altitude_textfield")
                )

                val isInputValid = remember(latInput, lonInput, altInput) {
                    val lat = latInput.toDoubleOrNull()
                    val lon = lonInput.toDoubleOrNull()
                    val alt = altInput.toDoubleOrNull()
                    lat != null && lat in -90.0..90.0 &&
                    lon != null && lon in -180.0..180.0 &&
                    alt != null
                }

                Button(
                    onClick = {
                        val lat = latInput.toDoubleOrNull()
                        val lon = lonInput.toDoubleOrNull()
                        val alt = altInput.toDoubleOrNull()
                        if (lat != null && lon != null && alt != null) {
                            viewModel.saveLocation(lat, lon, alt)
                        }
                    },
                    enabled = isInputValid,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_coordinates_button")
                ) {
                    Text("APPLY COORDINATES", fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Section 3: Observatory + user presets (built-ins are seeded here)
                Text(
                    text = "OBSERVATORY PRESETS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF818CF8),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                UserPresetsSection(
                    presets = presets,
                    onApply = { viewModel.applyPreset(it) },
                    onEdit = { editingPreset = it },
                    onDelete = { viewModel.deletePreset(it) },
                    onSaveCurrent = { showSavePresetDialog = true }
                )
            }
        }
    }

    if (showSavePresetDialog) {
        SavePresetDialog(
            existingNames = presets.map { it.name }.toSet(),
            onDismiss = { showSavePresetDialog = false },
            onConfirm = { name ->
                viewModel.saveCurrentAsPreset(name)
                showSavePresetDialog = false
            }
        )
    }

    editingPreset?.let { original ->
        EditPresetDialog(
            original = original,
            existingNames = presets.map { it.name }.toSet(),
            onDismiss = { editingPreset = null },
            onSave = { edited ->
                viewModel.editPreset(original.name, edited)
                editingPreset = null
            }
        )
    }
}

@Composable
private fun UserPresetsSection(
    presets: List<Preset>,
    onApply: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    onDelete: (String) -> Unit,
    onSaveCurrent: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onSaveCurrent,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().testTag("save_preset_button")
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("SAVE CURRENT LOCATION + BANDS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (presets.isEmpty()) {
            Text(
                text = "No saved presets yet. Pick coords + bands, then tap above to bookmark them.",
                fontSize = 11.sp,
                color = Color(0xFF64748B),
                fontFamily = FontFamily.Monospace
            )
        } else {
            presets.forEach { preset ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE2E8F0)
                        )
                        Text(
                            text = String.format(
                                "%.2f°N, %.2f°E · %s",
                                preset.groundLat,
                                preset.groundLon,
                                preset.antennaBands.joinToString(", ") { it.displayName }
                                    .ifBlank { "no bands" }
                            ),
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(
                        onClick = { onApply(preset) },
                        modifier = Modifier.testTag("apply_preset_${preset.name}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Apply", tint = Color(0xFFA5B4FC))
                    }
                    IconButton(
                        onClick = { onEdit(preset) },
                        modifier = Modifier.testTag("edit_preset_${preset.name}")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFFCBD5E1))
                    }
                    IconButton(
                        onClick = { onDelete(preset.name) },
                        modifier = Modifier.testTag("delete_preset_${preset.name}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFB7185))
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val isDuplicate = trimmed in existingNames
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Snapshots your current location + selected antenna bands under this name.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name") },
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = {
                        if (isDuplicate) Text(
                            "Will overwrite existing preset \"$trimmed\".",
                            color = Color(0xFFFBBF24)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = trimmed.isNotBlank()
            ) { Text("SAVE", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun EditPresetDialog(
    original: Preset,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Preset) -> Unit
) {
    var name by remember(original) { mutableStateOf(original.name) }
    var lat by remember(original) { mutableStateOf(original.groundLat.toString()) }
    var lon by remember(original) { mutableStateOf(original.groundLon.toString()) }
    var alt by remember(original) { mutableStateOf(original.groundAlt.toString()) }
    var bands by remember(original) { mutableStateOf(original.antennaBands) }

    val trimmed = name.trim()
    val latD = lat.toDoubleOrNull()
    val lonD = lon.toDoubleOrNull()
    val altD = alt.toDoubleOrNull()
    val isNameDup = trimmed != original.name && trimmed in existingNames
    val isValid = trimmed.isNotBlank() && latD != null && lonD != null && altD != null &&
            latD in -90.0..90.0 && lonD in -180.0..180.0 && bands.isNotEmpty() && !isNameDup

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit preset", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = isNameDup,
                    supportingText = {
                        if (isNameDup) Text(
                            "Name already used by another preset.",
                            color = Color(0xFFFB7185)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("Lat") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text("Lon") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = alt,
                    onValueChange = { alt = it },
                    label = { Text("Alt (m AMSL)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Antenna bands",
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.SemiBold
                )
                val chunkedBands = AntennaBand.values().toList().chunked(3)
                chunkedBands.forEach { rowBands ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        rowBands.forEach { band ->
                            val selected = band in bands
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    bands = if (selected) bands - band else bands + band
                                },
                                label = { Text(band.displayName, fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onSave(
                        Preset(
                            name = trimmed,
                            groundLat = latD!!,
                            groundLon = lonD!!,
                            groundAlt = altD!!,
                            antennaBands = bands
                        )
                    )
                }
            ) { Text("SAVE", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
