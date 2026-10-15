package io.github.danielgit8.cockpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Knöpfe in der Benachrichtigung. Wird sie weggewischt, kommt sie sofort wieder. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            REPOST -> Notifier.show(ctx)
            REFRESH -> { Notifier.show(ctx); Sync.now(ctx) }
        }
    }

    companion object {
        const val REPOST = "io.github.danielgit8.cockpit.REPOST"
        const val REFRESH = "io.github.danielgit8.cockpit.REFRESH"
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
