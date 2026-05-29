package pl.put.observationcompanion.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import pl.put.observationcompanion.ObservationCompanionApp
import pl.put.observationcompanion.notification.NotificationHelper

class SatnogsSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("SatnogsSyncWorker", "Starting scheduled background SatNOGS synchronization...")
        val app = applicationContext as? ObservationCompanionApp ?: return Result.failure()
        val container = app.container

        val repository = container.satnogsRepository

        try {
            // 1. Trigger live syncing of SatNOGS databases
            repository.syncFromRemote()

            // 2. Prune obsolete observations (older than 7 days) to limit database size
            repository.pruneOldObservations(olderThanDays = 7)

            // 3. Notify user of a successful sync (low priority channel)
            val notificationHelper = NotificationHelper(applicationContext)
            notificationHelper.createNotificationChannels()
            notificationHelper.showTleSyncNotification("Satellites, transmitters, and TLEs were updated.")

            Log.d("SatnogsSyncWorker", "Background SatNOGS sync completed successfully.")
            return Result.success()
        } catch (e: Exception) {
            Log.e("SatnogsSyncWorker", "Background sync task failed", e)
            return Result.retry()
        }
    }
}
