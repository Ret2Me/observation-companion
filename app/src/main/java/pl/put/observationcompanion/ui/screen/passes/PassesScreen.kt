package pl.put.observationcompanion.ui.screen.passes

import android.Manifest
import android.widget.Toast
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.put.observationcompanion.di.AppContainer
import pl.put.observationcompanion.domain.model.Pass
import pl.put.observationcompanion.ui.component.CompactPassCard
import pl.put.observationcompanion.ui.component.PassCard
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassesScreen(
    passesViewModel: PassesViewModel,
    appContainer: AppContainer,
    onNavigateToLocation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onOpenDetail: (Pass) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by passesViewModel.uiState.collectAsState()
    val compactMode by remember(appContainer) { appContainer.settingsRepository.getCompactModeFlow() }
        .collectAsState(initial = false)
    val isRefreshing by passesViewModel.isRefreshing.collectAsState()
    val refreshProgress by passesViewModel.loadingProgress.collectAsState()
    val statusNotice by passesViewModel.statusNotice.collectAsState()
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // TopAppBar that hides on scroll-down and reveals on scroll-up. We only
    // enable this behavior in landscape - in portrait there's plenty of room
    // and a jumpy top bar feels worse than a static one.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Collapse the location pill once the user has scrolled into the list
    // (any vertical scroll past the first item). This buys back ~70 dp of
    // vertical room which matters a lot in landscape.
    val locationPillVisible = remember(isLandscape) {
        derivedStateOf {
            if (!isLandscape) true
            else listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40
        }
    }

    // Fetch live user settings to show current listening band and coordinate badge at the top
    val userSettingsFlow = remember { appContainer.settingsRepository.getUserSettingsFlow() }
    val userSettingsState by userSettingsFlow.collectAsState(initial = null)

    // Trigger runtime permission checks to ensure push notifications and location are fine!
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Pass warning alerts may be silent. Please enable notifications.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        // Request Post Notifications permission on SDK 33+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = modifier
            .testTag("passes_screen")
            .let { if (isLandscape) it.nestedScroll(scrollBehavior.nestedScrollConnection) else it },
        topBar = {
            TopAppBar(
                scrollBehavior = if (isLandscape) scrollBehavior else null,
                title = {
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            text = "LSF SatNOGS",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "COMPANION V1.2",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = Color(0xFF818CF8) // Indigo-400
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { appContainer.settingsRepository.setCompactMode(!compactMode) } },
                        modifier = Modifier.testTag("toggle_compact_button")
                    ) {
                        Icon(
                            imageVector = if (compactMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.ViewHeadline,
                            contentDescription = if (compactMode) "Switch to detailed cards" else "Switch to compact list"
                        )
                    }
                    IconButton(onClick = onNavigateToStats, modifier = Modifier.testTag("nav_stats_button")) {
                        Icon(Icons.Default.QueryStats, contentDescription = "Stats")
                    }
                    IconButton(onClick = onNavigateToLocation, modifier = Modifier.testTag("nav_location_button")) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Localization")
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("nav_settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF020617) // Slate-950
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020617)) // Slate-950
                .padding(innerPadding)
        ) {
            // Observer Positioning Badge Banner - single compact row:
            //   ● 52.40°N, 16.92°E · VHF·UHF·L·S
            // Everything fits on one line; bands ellipsize on overflow.
            userSettingsState?.let { settings ->
              AnimatedVisibility(visible = locationPillVisible.value) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x266366F1))
                        .clickable { onNavigateToLocation() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Glowing indicator dot
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = Color(0x6610B981),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = Color(0xFF10B981),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                    Text(
                        text = String.format("%.2f°N, %.2f°E", settings.groundLat, settings.groundLon),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFCBD5E1),
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = Color(0xFF475569)
                    )
                    Text(
                        text = settings.antennaBands.joinToString("·") { it.displayName }
                            .ifBlank { "no bands" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFA5B4FC),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
              }
            }

            // Inline status bar between the location pill and the sort bar.
            // While refreshing -> thin progress line + stage text.
            // While idle -> small notice ("Cached N min ago", "Updated", …) or
            // nothing at all. Same row in both modes so the layout doesn't jump.
            RefreshStatusBar(
                isRefreshing = isRefreshing,
                stage = refreshProgress?.stage,
                idleNotice = statusNotice
            )

            // Main State Handler
            when (val state = uiState) {
                is PassesUiState.Loading -> {
                    LoadingPanel(progress = state.progress)
                }

                is PassesUiState.Success -> {
                    if (state.totalCount == 0) {
                        // Styled Empty State
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "No passes found antenna",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    modifier = Modifier.size(96.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "NO SATELLITE PASSES DETECTED",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "There are no active satellite passes inside your configured bands (${userSettingsState?.antennaBands?.joinToString(", ") { it.displayName } ?: "none"}) for the current observation site.",
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { passesViewModel.refreshPasses(forceRemoteSync = true) },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("FETCH API DATA")
                                    }
                                    OutlinedButton(
                                        onClick = onNavigateToSettings,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("SELECT BAND")
                                    }
                                }
                            }
                        }
                    } else {
                        var showSatelliteFilter by remember { mutableStateOf(false) }
                        // Sort selector - single row of FilterChips above the list.
                        SortBar(
                            current = state.sortMode,
                            totalCount = state.totalCount,
                            visibleCount = state.passes.size,
                            skippedCount = state.skippedCount,
                            onSelect = passesViewModel::setSortMode,
                            selectedSatelliteCount = state.selectedSatelliteNames.size,
                            availableSatelliteCount = state.availableSatelliteNames.size,
                            filterHiddenCount = state.filterHiddenCount,
                            onOpenSatelliteFilter = { showSatelliteFilter = true },
                            onClearSatelliteFilter = passesViewModel::clearSatelliteNameFilter
                        )

                        if (showSatelliteFilter) {
                            SatelliteFilterDialog(
                                available = state.availableSatelliteNames,
                                selected = state.selectedSatelliteNames,
                                onDismiss = { showSatelliteFilter = false },
                                onConfirm = {
                                    passesViewModel.setSelectedSatelliteNames(it)
                                    showSatelliteFilter = false
                                }
                            )
                        }

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { passesViewModel.refreshPasses(forceRemoteSync = true) },
                            // We render our own thin inline banner higher up the
                            // screen, so suppress the default circular indicator.
                            indicator = {},
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(if (compactMode) 8.dp else 12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.passes, key = { PassesViewModel.passKey(it) }) { pass ->
                                    if (compactMode) {
                                        CompactPassCard(
                                            pass = pass,
                                            onOpenDetail = onOpenDetail,
                                            modifier = Modifier.animateItem()
                                        )
                                    } else {
                                        val leadTime = userSettingsState?.alarmLeadTimeMinutes ?: 5
                                        PassCard(
                                            pass = pass,
                                            alarmLeadTime = leadTime,
                                            onOpenDetail = onOpenDetail,
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                                if (state.canLoadMore) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp, bottom = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            OutlinedButton(
                                                onClick = { passesViewModel.loadMore() },
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("LOAD MORE (${state.totalCount - state.passes.size} more)")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                is PassesUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "ERROR CALCULATING TRAJECTORIES",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = state.message,
                                color = Color.Gray,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = { passesViewModel.refreshPasses() }
                            ) {
                                Text("RETRY calculation")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshStatusBar(isRefreshing: Boolean, stage: String?, idleNotice: String?) {
    val visible = isRefreshing || idleNotice != null
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = Color(0xFF818CF8),
                    trackColor = Color(0xFF1E293B)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = if (isRefreshing) (stage ?: "Syncing…") else (idleNotice ?: ""),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (isRefreshing) Color(0xFF94A3B8) else Color(0xFF64748B),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LoadingPanel(progress: LoadingProgress) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "LOADING",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Color(0xFF818CF8)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = progress.stage,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE2E8F0),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.current.coerceAtMost(progress.total).toFloat() / progress.total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF818CF8),
                    trackColor = Color(0xFF1E293B)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${progress.current} / ${progress.total}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF818CF8),
                    trackColor = Color(0xFF1E293B)
                )
            }

            if (progress.log.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "// SYSTEM LOG",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    progress.log.forEach { line ->
                        Text(
                            text = "› $line",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B),
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBar(
    current: PassSortMode,
    totalCount: Int,
    visibleCount: Int,
    skippedCount: Int,
    onSelect: (PassSortMode) -> Unit,
    selectedSatelliteCount: Int,
    availableSatelliteCount: Int,
    filterHiddenCount: Int,
    onOpenSatelliteFilter: () -> Unit,
    onClearSatelliteFilter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SORT BY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = "$visibleCount / $totalCount PASSES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color(0xFF818CF8),
                fontFamily = FontFamily.Monospace
            )
        }
        if (skippedCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$skippedCount satellite(s) skipped (slow / unreachable orbit)",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFBBF24),
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PassSortMode.values().forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                    label = {
                        Text(
                            text = mode.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("sort_chip_${mode.name}")
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val active = selectedSatelliteCount > 0
            FilterChip(
                selected = active,
                onClick = onOpenSatelliteFilter,
                label = {
                    Text(
                        text = if (active)
                            "Satellites · $selectedSatelliteCount selected"
                        else
                            "Filter by satellite",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.testTag("satellite_filter_chip")
            )
            if (active) {
                AssistChip(
                    onClick = onClearSatelliteFilter,
                    label = {
                        Text("Clear", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    },
                    modifier = Modifier.testTag("satellite_filter_clear")
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (active) {
                Text(
                    text = "$filterHiddenCount hidden / $availableSatelliteCount sats",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    fontFamily = FontFamily.Monospace
                )
            } else if (availableSatelliteCount > 0) {
                Text(
                    text = "$availableSatelliteCount distinct sats",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun SatelliteFilterDialog(
    available: List<String>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(selected) { mutableStateOf(selected) }
    val filtered = remember(query, available) {
        if (query.isBlank()) available
        else available.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Filter by satellite name", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("satellite_filter_search")
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${draft.size} selected · ${available.size} total",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(filtered, key = { it }) { name ->
                        val checked = name in draft
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    draft = if (checked) draft - name else draft + name
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    draft = if (it) draft + name else draft - name
                                }
                            )
                            Text(
                                text = name,
                                fontSize = 13.sp,
                                color = Color(0xFFE2E8F0),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No satellites match \"$query\"",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text("APPLY", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { draft = emptySet() }) { Text("CLEAR") }
                TextButton(onClick = onDismiss) { Text("CANCEL") }
            }
        }
    )
}
