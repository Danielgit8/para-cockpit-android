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
import androidx.core.app.RemoteInput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    private const val CHANNEL = "heute"
    private const val GROUP = "io.github.danielgit8.cockpit.HEUTE"
    private const val SUMMARY_ID = 1
    private const val MAX_ITEMS = 15
    const val INPUT_KEY = "wert"

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

    private fun childId(id: String) = 1000 + (id.hashCode() and 0x3fffffff) % 1_000_000

    private fun broadcast(ctx: Context, action: String, code: Int, entry: Entry? = null, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action)
        if (entry != null) i.putExtra("id", entry.id).putExtra("type", entry.type).putExtra("mode", entry.mode).putExtra("title", entry.title).putExtra("goal", entry.goal)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, code, i, flags)
    }

    private fun openCockpit(ctx: Context) = PendingIntent.getActivity(
        ctx, 10, Intent(Intent.ACTION_VIEW, Uri.parse(Api.COCKPIT)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancelAll()

    private fun label(e: Entry, project: String?) = when (e.section) {
        "one" -> "Meine eine Sache heute"
        "due" -> "Aufgabe, heute fällig"
        "next" -> "Nächster Schritt · ${project ?: "Hauptprojekt"}"
        else -> when (e.mode) {
            "number" -> "Gewohnheit · Ziel ${fmt(e.goal)} ${e.unit}".trim()
            "choice" -> "Gewohnheit · Auswahl"
            else -> "Gewohnheit"
        }
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString().replace('.', ',')

    /** Zeigt den letzten bekannten Stand: eine Übersicht und darunter jeden offenen Eintrag mit eigenem Knopf. */
    fun show(ctx: Context) {
        val store = Store(ctx)
        if (!store.enabled || !allowed(ctx)) { cancel(ctx); return }
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)

        val s = Summary.fromJson(store.summary)
        val loggedIn = store.session != null
        val entries = if (loggedIn && s != null) s.entries.take(MAX_ITEMS) else emptyList()

        // Übersicht (Gruppen-Kopf)
        val lines = ArrayList<String>()
        val title: String
        when {
            !loggedIn -> { title = "Cockpit Heute: bitte anmelden"; lines.add("Öffne die App und melde dich mit deinem Cockpit-Konto an.") }
            s == null -> { title = "Cockpit wird geladen …"; lines.add("Tippe auf „Aktualisieren“, falls es länger dauert.") }
            s.entries.isEmpty() -> { title = "Alles erledigt für heute ✓"; lines.add("Stark. Alles Weitere ist Bonus.") }
            else -> {
                title = "Heute: ${s.tasks.size} Aufgaben · ${s.habits.size} Gewohnheiten offen"
                s.entries.forEach { lines.add((if (it.type == "task") "○ " else "◇ ") + it.title) }
                if (s.day != Today.key()) lines.add(0, "(Stand von gestern, wird gleich aktualisiert)")
            }
        }
        val time = if (store.lastSync > 0) "Stand " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(store.lastSync)) else null
        val sum = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.take(3).joinToString(" · ") { it.trim() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(if (loggedIn) openCockpit(ctx) else PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(broadcast(ctx, ActionReceiver.REPOST, 1))
            .addAction(0, "Aktualisieren", broadcast(ctx, ActionReceiver.REFRESH, 2))
            .addAction(0, "Cockpit öffnen", openCockpit(ctx))
            .setGroup(GROUP).setGroupSummary(entries.isNotEmpty())
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey("0")
        if (time != null) sum.setSubText(time)

        try {
            // Einträge, die es nicht mehr gibt, entfernen
            val keep = entries.map { childId(it.id) }.toSet()
            val old = store.shownIds
            old.filter { it !in keep }.forEach { nm.cancel(it) }
            store.shownIds = keep

            entries.forEachIndexed { i, e -> nm.notify(childId(e.id), child(ctx, e, s?.project, i)) }
            nm.notify(SUMMARY_ID, sum.build())
        } catch (e: SecurityException) {
            // Benachrichtigungen nicht erlaubt
        }
    }

    private fun child(ctx: Context, e: Entry, project: String?, index: Int): android.app.Notification {
        val code = childId(e.id)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(e.title)
            .setContentText(label(e, project))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openCockpit(ctx))
            .setDeleteIntent(broadcast(ctx, ActionReceiver.REPOST, 1))
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey(String.format(Locale.ROOT, "%03d", index + 1))

        when {
            e.type == "task" && e.repeat -> b.setContentText(label(e, project) + " · wiederkehrend, im Cockpit abhaken")
            e.type == "task" || e.mode == "check" ->
                b.addAction(0, "✓ Erledigt", broadcast(ctx, ActionReceiver.DONE, code, e))
            e.mode == "number" -> {
                val input = RemoteInput.Builder(INPUT_KEY).setLabel(if (e.unit.isNotEmpty()) "Zahl in ${e.unit}" else "Zahl eintragen").build()
                val action = NotificationCompat.Action.Builder(0, "Eintragen", broadcast(ctx, ActionReceiver.VALUE, code, e, mutable = true))
                    .addRemoteInput(input).setAllowGeneratedReplies(false).build()
                b.addAction(action)
                if (e.goal > 0) b.addAction(0, "✓ ${fmt(e.goal)} geschafft", broadcast(ctx, ActionReceiver.GOAL, code + 7_000_000, e))
            }
            e.mode == "choice" && e.options.isNotEmpty() -> {
                val input = RemoteInput.Builder(INPUT_KEY).setLabel("Was hast du gemacht?").setChoices(e.options.map { it as CharSequence }.toTypedArray()).setAllowFreeFormInput(false).build()
                val action = NotificationCompat.Action.Builder(0, "Auswählen", broadcast(ctx, ActionReceiver.VALUE, code, e, mutable = true))
                    .addRemoteInput(input).setAllowGeneratedReplies(false).build()
                b.addAction(action)
            }
            else -> b.addAction(0, "✓ Erledigt", broadcast(ctx, ActionReceiver.DONE, code, e))
        }
        return b.build()
    }
}
