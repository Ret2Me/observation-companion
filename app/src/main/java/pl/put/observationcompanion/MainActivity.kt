package pl.put.observationcompanion

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.*
import pl.put.observationcompanion.ui.screen.detail.SatelliteDetailScreen
import pl.put.observationcompanion.ui.screen.detail.SatelliteDetailViewModel
import pl.put.observationcompanion.ui.screen.detail.SatelliteDetailViewModelFactory
import pl.put.observationcompanion.ui.screen.location.LocationScreen
import pl.put.observationcompanion.ui.screen.location.LocationViewModel
import pl.put.observationcompanion.ui.screen.location.LocationViewModelFactory
import pl.put.observationcompanion.ui.screen.passes.PassesScreen
import pl.put.observationcompanion.ui.screen.passes.PassesViewModel
import pl.put.observationcompanion.ui.screen.passes.PassesViewModelFactory
import pl.put.observationcompanion.ui.screen.settings.SettingsScreen
import pl.put.observationcompanion.ui.screen.settings.SettingsViewModel
import pl.put.observationcompanion.ui.screen.settings.SettingsViewModelFactory
import pl.put.observationcompanion.ui.screen.stats.StatsScreen
import pl.put.observationcompanion.ui.screen.stats.StatsViewModel
import pl.put.observationcompanion.ui.screen.stats.StatsViewModelFactory
import pl.put.observationcompanion.ui.theme.ObservationCompanionTheme
import pl.put.observationcompanion.worker.SatnogsSyncWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as ObservationCompanionApp).container

        // Instantiate ViewModels manually using standard factories
        val passesViewModel = ViewModelProvider(
            this,
            PassesViewModelFactory(appContainer)
        )[PassesViewModel::class.java]

        val locationViewModel = ViewModelProvider(
            this,
            LocationViewModelFactory(appContainer, applicationContext)
        )[LocationViewModel::class.java]

        val settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModelFactory(appContainer)
        )[SettingsViewModel::class.java]

        val statsViewModel = ViewModelProvider(
            this,
            StatsViewModelFactory(appContainer, applicationContext)
        )[StatsViewModel::class.java]

        val detailViewModel = ViewModelProvider(
            this,
            SatelliteDetailViewModelFactory(appContainer)
        )[SatelliteDetailViewModel::class.java]

        // Dismiss any pass-alert notifications stacked from previous app runs -
        // earlier builds mass-scheduled alarms for every matched satellite and
        // those notifications can pile up between launches. Cancel before the
        // user sees them.
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancelAll()

        // Enqueue background synchronization tasks
        enqueuePeriodicSync()

        setContent {
            ObservationCompanionTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "passes"
                ) {
                    composable("passes") {
                        PassesScreen(
                            passesViewModel = passesViewModel,
                            appContainer = appContainer,
                            onNavigateToLocation = { navController.navigate("location") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToStats = {
                                statsViewModel.load()
                                navController.navigate("stats")
                            },
                            onOpenDetail = { pass ->
                                detailViewModel.load(pass)
                                navController.navigate("satellite_detail")
                            }
                        )
                    }
                    composable("satellite_detail") {
                        SatelliteDetailScreen(
                            viewModel = detailViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("stats") {
                        StatsScreen(
                            viewModel = statsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("location") {
                        LocationScreen(
                            viewModel = locationViewModel,
                            appContainer = appContainer,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun enqueuePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SatnogsSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "satnogs_network_data_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
