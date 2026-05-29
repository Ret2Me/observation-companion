package pl.put.observationcompanion.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import pl.put.observationcompanion.worker.AlarmRescheduleWorker

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("BootReceiver", "Boot or update completed. Launching AlarmRescheduleWorker...")
            try {
                val rescheduleRequest = OneTimeWorkRequestBuilder<AlarmRescheduleWorker>().build()
                WorkManager.getInstance(context).enqueue(rescheduleRequest)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed enqueueing AlarmRescheduleWorker", e)
            }
        }
    }
}
