package pl.put.observationcompanion.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters

// Alarms are set only through the toggle in PassCard. Worker kept as a no-op
// so BootReceiver stays compatible with older versions.
class AlarmRescheduleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("AlarmRescheduleWorker", "No-op: per-pass alarms are user-toggled only.")
        return Result.success()
    }
}
