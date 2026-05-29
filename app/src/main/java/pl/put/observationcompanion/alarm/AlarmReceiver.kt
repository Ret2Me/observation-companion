package pl.put.observationcompanion.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.put.observationcompanion.ObservationCompanionApp
import pl.put.observationcompanion.notification.NotificationHelper
import kotlinx.coroutines.runBlocking

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PASS_ALERT = "pl.put.observationcompanion.alarm.ACTION_PASS_ALERT"
        const val EXTRA_SAT_NAME = "extra_satellite_name"
        const val EXTRA_LEAD_MINUTES = "extra_lead_minutes"
        const val EXTRA_PASS_ID = "extra_pass_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PASS_ALERT) return

        // Global silencer: respect the master "Alarms enabled" toggle in
        // Settings at fire time. Pending alarms scheduled before the user
        // disabled the feature (or by older builds that mass-scheduled
        // every matched satellite) get dropped here without a notification.
        val app = context.applicationContext as? ObservationCompanionApp
        val alarmsEnabled = try {
            runBlocking { app?.container?.settingsRepository?.getUserSettings()?.alarmsEnabled ?: true }
        } catch (e: Exception) {
            Log.w("AlarmReceiver", "Could not read alarmsEnabled, defaulting to enabled", e)
            true
        }
        if (!alarmsEnabled) {
            Log.d("AlarmReceiver", "Alarms globally disabled; skipping notification")
            return
        }

        val satName = intent.getStringExtra(EXTRA_SAT_NAME) ?: "Satellite"
        val leadMinutes = intent.getIntExtra(EXTRA_LEAD_MINUTES, 5)
        val passId = intent.getIntExtra(EXTRA_PASS_ID, 0)

        val title = "Incoming Pass: $satName"
        val message = "Acquisition of Signal (AOS) begins in $leadMinutes minutes! Prepare your antenna."

        val notificationHelper = NotificationHelper(context)
        notificationHelper.createNotificationChannels()
        notificationHelper.showPassAlertNotification(title, message, passId)
    }
}
