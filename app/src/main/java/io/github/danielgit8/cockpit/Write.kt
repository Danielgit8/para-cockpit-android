package io.github.danielgit8.cockpit

import android.content.Context
import org.json.JSONObject

/** Abhaken und Eintragen aus der Leiste. Schreibt wie das Cockpit: frischen Stand holen, ändern, "updated" setzen, speichern. */
object Write {
    fun apply(ctx: Context, id: String, type: String, value: Any): String {
        val store = Store(ctx)
        var s = Sync.session(store) ?: throw IllegalStateException("Nicht angemeldet")
        fun <T> authed(f: (String) -> T): T = try {
            f(s.access)
        } catch (e: Api.HttpError) {
            if (e.code != 401) throw e
            s = Sync.session(store, force = true) ?: throw IllegalStateException("Nicht angemeldet")
            f(s.access)
        }

        val data = authed { Api.item(it, id) } ?: return "Diesen Eintrag gibt es nicht mehr."
        val now = System.currentTimeMillis()
        val tk = Today.key()
        val title = data.optString("title").ifEmpty { "Eintrag" }
        val msg: String

        if (type == "task") {
            if (data.optBoolean("done")) return "War schon erledigt: $title"
            data.put("done", true).put("doneAt", now).put("updated", now)
            authed { Api.save(it, id, data, now) }
            // War es „Meine eine Sache heute“? Dann wie im Cockpit im Wochenverlauf vermerken.
            val set = authed { Api.item(it, "__settings") }
            val ot = set?.optJSONObject("oneThing")
            if (set != null && ot != null && ot.optString("id") == id && ot.optString("d") == tk) {
                val lg = set.optJSONObject("oneLog") ?: JSONObject()
                lg.put(tk, true)
                set.put("oneLog", lg).put("updated", now)
                authed { Api.save(it, "__settings", set, now) }
            }
            msg = "Erledigt: $title"
        } else {
            val log = data.optJSONObject("log") ?: JSONObject()
            log.put(tk, value)
            data.put("log", log).put("updated", now)
            authed { Api.save(it, id, data, now) }
            msg = if (value == true) "Erledigt: $title" else "Eingetragen: $title · $value"
        }

        // Sofort aus der Leiste nehmen, wenn erledigt. Der volle Abgleich folgt danach.
        val sum = Summary.fromJson(store.summary)
        if (sum != null && (type == "task" || Today.hdone(data, tk))) store.summary = sum.without(id).toJson()
        Notifier.show(ctx)
        return msg
    }
}
