package com.househunt.app.data

import android.content.Context
import androidx.work.*
import com.househunt.app.HouseHuntApp
import com.househunt.shared.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background sync (OSI L1-L5 behaviour, docs/09-osi-layer-analysis.md):
 *  - only runs with a network (WorkManager constraint), survives process death and Doze (deferred to a window),
 *  - behind a captive portal it stops early instead of sending the API key to a sign-in page,
 *  - on metered networks, photos wait for Wi-Fi when the user asked for that; a separate UNMETERED job then
 *    transfers them as soon as Wi-Fi is available,
 *  - transient failures retry with WorkManager's exponential backoff (30 s, 60 s, 120 s, ...); an auth failure
 *    does not retry (it needs the user to fix the key),
 *  - when WorkManager stops the worker (constraint lost, e.g. the network went away, or the work was replaced or
 *    cancelled) the coroutine is cancelled; that is not a failed sync, so nothing is recorded (WorkManager runs
 *    stopped work again once its constraints are met).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as HouseHuntApp).container.repository
        val net = NetworkState.current(applicationContext)
        if (net.captivePortal) {
            repo.settings.saveSyncResult(SyncOutcome(SyncOutcome.Kind.CAPTIVE_PORTAL))
            return Result.retry()
        }
        val settings = repo.settings.current()
        val photosAllowed = !net.metered || !settings.photosOnWifiOnly
        return try {
            val outcome = repo.sync(photosAllowed)
            repo.settings.saveSyncResult(outcome)
            if (outcome.photosWaiting > 0) syncPhotosOnWifi(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            // Since Sprint 3.5 every HTTP call is a cancellable Ktor suspend call, so a stop now surfaces here as a
            // CancellationException. Rethrow it (as in ReverseGeocoder) instead of saving it as a sync error.
            throw e
        } catch (e: Exception) {
            val outcome = SyncOutcome.fromError(e)
            repo.settings.saveSyncResult(outcome)
            when {
                outcome.kind == SyncOutcome.Kind.AUTH -> Result.failure()
                runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun syncSoon(context: Context) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, work)
        }

        /** Runs once the phone is on Wi-Fi (or another unmetered network) to move waiting photos. */
        fun syncPhotosOnWifi(context: Context) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(unmetered)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-photos-wifi", ExistingWorkPolicy.KEEP, work)
        }

        fun schedulePeriodic(context: Context) {
            val work = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
