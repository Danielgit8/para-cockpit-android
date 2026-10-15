package io.github.danielgit8.cockpit

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Sync {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Holt den aktuellen Stand und zeigt ihn an. Läuft im Hintergrund-Thread. */
    fun run(ctx: Context) {
        val store = Store(ctx)
        val saved = store.session
        if (saved == null) { Notifier.show(ctx); return }
        var s = saved
        try {
            if (s.expiresAt - 120 < System.currentTimeMillis() / 1000) { s = Api.refresh(s); store.session = s }
            val rows = try {
                Api.items(s.access)
            } catch (e: Api.HttpError) {
                if (e.code != 401) throw e
                s = Api.refresh(s); store.session = s
                Api.items(s.access)
            }
            store.summary = Today.build(rows).toJson()
            store.lastSync = System.currentTimeMillis()
            store.lastError = ""
        } catch (e: Api.HttpError) {
            if (e.code == 400 || e.code == 401) { store.session = null; store.lastError = "Anmeldung abgelaufen. Bitte neu anmelden." }
            else store.lastError = e.message.orEmpty()
            throw e
        } finally {
            Notifier.show(ctx)
        }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequest.Builder(SyncWorker::class.java, 15, TimeUnit.MINUTES).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("heute", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun now(ctx: Context) {
        val req = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("heute-jetzt", ExistingWorkPolicy.REPLACE, req)
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("heute")
        WorkManager.getInstance(ctx).cancelUniqueWork("heute-jetzt")
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Sync.run(applicationContext)
        Result.success()
    } catch (e: Api.HttpError) {
        if (e.code >= 500) Result.retry() else Result.failure()
    } catch (e: Exception) {
        Result.retry()
    }
}
