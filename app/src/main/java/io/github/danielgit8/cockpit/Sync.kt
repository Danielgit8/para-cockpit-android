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

    /** Gültige Anmeldung, bei Bedarf erneuert. Null, wenn nicht angemeldet. */
    fun session(store: Store, force: Boolean = false): Api.Session? {
        var s = store.session ?: return null
        if (force || s.expiresAt - 120 < System.currentTimeMillis() / 1000) {
            try { s = Api.refresh(s); store.session = s }
            catch (e: Api.HttpError) {
                if (e.code == 400 || e.code == 401) { store.session = null; store.lastError = "Anmeldung abgelaufen. Bitte neu anmelden." }
                throw e
            }
        }
        return s
    }

    /** Holt den aktuellen Stand und zeigt ihn an. Läuft im Hintergrund-Thread. */
    fun run(ctx: Context) {
        val store = Store(ctx)
        if (store.session == null) { Notifier.show(ctx); return }
        try {
            var s = session(store) ?: return
            val rows = try {
                Api.items(s.access)
            } catch (e: Api.HttpError) {
                if (e.code != 401) throw e
                s = session(store, force = true) ?: return
                Api.items(s.access)
            }
            store.summary = Today.build(rows).toJson()
            store.lastSync = System.currentTimeMillis()
            store.lastError = ""
        } catch (e: Api.HttpError) {
            if (store.session != null) store.lastError = e.message.orEmpty()
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
