package pl.put.observationcompanion.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.put.observationcompanion.domain.model.Pass
import java.time.Instant

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    fun schedulePassAlarm(
        context: Context,
        pass: Pass,
        leadTimeMinutes: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // Calculate target alarm trigger epoch millisecond
        val alarmTriggerMillis = pass.aos.toEpochMilli() - (leadTimeMinutes * 60L * 1000L)
        
        if (alarmTriggerMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Skipped scheduling alarm for ${pass.satelliteName} because the lead trigger time is in the past.")
            return
        }

        // Unique request code per pass based on timestamp hash code
        val requestCode = pass.aos.hashCode()

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_PASS_ALERT
            putExtra(AlarmReceiver.EXTRA_SAT_NAME, pass.satelliteName)
            putExtra(AlarmReceiver.EXTRA_LEAD_MINUTES, leadTimeMinutes)
            putExtra(AlarmReceiver.EXTRA_PASS_ID, requestCode)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            // Check alarm permissions on SDK 31+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.w(TAG, "Cannot schedule exact alarm: Exact alarm permission not granted by user.")
                    return
                }
            }

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarmTriggerMillis,
                pendingIntent
            )
            Log.d(TAG, "Successfully scheduled alarm for ${pass.satelliteName} at ${Instant.ofEpochMilli(alarmTriggerMillis)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed scheduling exact alarm", e)
        }
    }

    fun cancelPassAlarm(context: Context, pass: Pass) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val requestCode = pass.aos.hashCode()

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_PASS_ALERT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Canceled alarm for request code: $requestCode")
        }
    }
}
