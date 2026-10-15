package io.github.danielgit8.cockpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.RemoteInput

/** Knöpfe in der Benachrichtigung. Wird sie weggewischt, kommt sie sofort wieder. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            REPOST -> Notifier.show(ctx)
            REFRESH -> { Notifier.show(ctx); Sync.now(ctx) }
            DONE, VALUE, GOAL -> write(ctx, intent)
        }
    }

    private fun write(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val type = intent.getStringExtra("type") ?: "task"
        val mode = intent.getStringExtra("mode") ?: "check"
        val value: Any = when (intent.action) {
            GOAL -> num(intent.getDoubleExtra("goal", 1.0))
            VALUE -> {
                val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.INPUT_KEY)?.toString()?.trim()
                if (raw.isNullOrEmpty()) { Notifier.show(ctx); return }
                if (mode == "number") {
                    val d = raw.replace(',', '.').toDoubleOrNull()
                    if (d == null) { toast(ctx, "Bitte nur eine Zahl eintragen."); Notifier.show(ctx); return }
                    num(d)
                } else raw
            }
            else -> true
        }
        val pending = goAsync()
        Thread {
            try {
                toast(ctx, Write.apply(ctx, id, type, value))
                Sync.now(ctx)
            } catch (e: Exception) {
                toast(ctx, "Konnte nicht speichern. Prüfe die Internetverbindung.")
                Notifier.show(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun num(d: Double): Any = if (d % 1.0 == 0.0) d.toLong() else d

    private fun toast(ctx: Context, msg: String) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val REPOST = "io.github.danielgit8.cockpit.REPOST"
        const val REFRESH = "io.github.danielgit8.cockpit.REFRESH"
        const val DONE = "io.github.danielgit8.cockpit.DONE"
        const val VALUE = "io.github.danielgit8.cockpit.VALUE"
        const val GOAL = "io.github.danielgit8.cockpit.GOAL"
    }
}

/** Nach dem Neustart des Handys oder einem App-Update wieder starten. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Store(ctx).session == null) return
        Notifier.show(ctx)
        Sync.schedule(ctx)
        Sync.now(ctx)
    }
}
