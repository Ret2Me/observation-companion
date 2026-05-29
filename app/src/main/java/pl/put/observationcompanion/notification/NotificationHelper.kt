package pl.put.observationcompanion.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import pl.put.observationcompanion.MainActivity

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_TLE_SYNC = "tle_sync_channel"
        const val CHANNEL_PASS_ALERTS = "pass_alerts_channel"
        const val CHANNEL_SPACE_WEATHER = "space_weather_channel"
        const val NOTIFICATION_ID_BASE = 4000
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel 1: TLE Sync
            val tleChannel = NotificationChannel(
                CHANNEL_TLE_SYNC,
                "TLE Synchronization",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when orbital parameters (TLE) are synced in the background."
            }

            // Channel 2: Pass Alerts
            val passChannel = NotificationChannel(
                CHANNEL_PASS_ALERTS,
                "Satellite Pass Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warns of upcoming satellite passes before Acquisition of Signal (AOS)."
                enableVibration(true)
                // Use default notification sound as warning sound
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(soundUri, null)
            }

            // Channel 3: Space Weather
            val spaceChannel = NotificationChannel(
                CHANNEL_SPACE_WEATHER,
                "Space Weather Events",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Updates on space weather indices (K-index)."
            }

            notificationManager.createNotificationChannel(tleChannel)
            notificationManager.createNotificationChannel(passChannel)
            notificationManager.createNotificationChannel(spaceChannel)
        }
    }

    fun showPassAlertNotification(title: String, message: String, passId: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            passId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_PASS_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(NOTIFICATION_ID_BASE + passId, builder.build())
    }

    fun showTleSyncNotification(message: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_TLE_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SatNOGS Sync Completed")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        notificationManager.notify(99, builder.build())
    }
}
