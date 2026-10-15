package io.github.danielgit8.cockpit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    private const val CHANNEL = "heute"
    private const val ID = 1

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Heute im Cockpit", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Deine offenen Aufgaben und Gewohnheiten, dauerhaft in der Leiste"
                setShowBadge(false)
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID)

    /** Zeigt den letzten bekannten Stand. Ohne Stand: Hinweis zum Laden oder Anmelden. */
    fun show(ctx: Context) {
        val store = Store(ctx)
        if (!store.enabled || !allowed(ctx)) { cancel(ctx); return }
        ensureChannel(ctx)

        val s = Summary.fromJson(store.summary)
        val loggedIn = store.session != null
        val lines = ArrayList<String>()
        val title: String
        if (!loggedIn) {
            title = "Cockpit Heute: bitte anmelden"
            lines.add("Öffne die App und melde dich mit deinem Cockpit-Konto an.")
        } else if (s == null) {
            title = "Cockpit wird geladen …"
            lines.add("Tippe auf „Aktualisieren“, falls es länger dauert.")
        } else {
            s.one?.let { lines.add("★ Meine eine Sache: $it") }
            s.tasks.forEach { lines.add("○ $it") }
            if (s.next.isNotEmpty()) {
                lines.add("${s.project ?: "Hauptprojekt"}:")
                s.next.forEach { lines.add("   → $it") }
            }
            if (s.habits.isNotEmpty()) {
                lines.add("Gewohnheiten:")
                s.habits.forEach { lines.add("   ◇ $it") }
            }
            title = if (lines.isEmpty()) "Alles erledigt für heute ✓"
            else "Heute: ${s.taskCount} Aufgaben · ${s.habits.size} Gewohnheiten offen"
            if (lines.isEmpty()) lines.add("Stark. Alles Weitere ist Bonus.")
            if (s.day != Today.key()) lines.add(0, "(Stand von gestern, wird gleich aktualisiert)")
        }

        val time = if (store.lastSync > 0) "Stand " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(store.lastSync)) else null
        val open = PendingIntent.getActivity(
            ctx, 10, Intent(Intent.ACTION_VIEW, Uri.parse(Api.COCKPIT)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.take(3).joinToString(" · ") { it.trim() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(if (loggedIn) open else PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(pi(ctx, ActionReceiver.REPOST, 1))
            .addAction(0, "Aktualisieren", pi(ctx, ActionReceiver.REFRESH, 2))
            .addAction(0, "Cockpit öffnen", open)
        if (time != null) b.setSubText(time)
        try {
            NotificationManagerCompat.from(ctx).notify(ID, b.build())
        } catch (e: SecurityException) {
            // Benachrichtigungen nicht erlaubt
        }
    }
}
