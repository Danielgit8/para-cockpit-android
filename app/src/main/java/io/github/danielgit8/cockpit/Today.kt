package io.github.danielgit8.cockpit

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Ein offener Eintrag in der Leiste. */
data class Entry(
    val id: String,
    val title: String,
    val type: String,      // "task" oder "habit"
    val section: String,   // "one", "due", "next", "habit"
    val mode: String = "check",
    val goal: Double = 0.0,
    val unit: String = "",
    val options: List<String> = emptyList(),
    val repeat: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("type", type).put("section", section)
        .put("mode", mode).put("goal", goal).put("unit", unit).put("options", JSONArray(options)).put("repeat", repeat)

    companion object {
        fun fromJson(j: JSONObject): Entry {
            val a = j.optJSONArray("options")
            return Entry(
                j.optString("id"), j.optString("title"), j.optString("type"), j.optString("section"),
                j.optString("mode", "check"), j.optDouble("goal", 0.0), j.optString("unit"),
                if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) },
                j.optBoolean("repeat")
            )
        }
    }
}

/** Was heute offen ist. Gleiche Regeln wie im Cockpit: die eine Sache, fällige Aufgaben, nächste Schritte im Hauptprojekt, offene Gewohnheiten. */
data class Summary(val day: String, val project: String?, val entries: List<Entry>) {
    val tasks get() = entries.filter { it.type == "task" }
    val habits get() = entries.filter { it.type == "habit" }

    fun without(id: String) = copy(entries = entries.filter { it.id != id })

    fun toJson(): String = JSONObject().put("day", day).put("project", project ?: JSONObject.NULL)
        .put("entries", JSONArray(entries.map { it.toJson() })).toString()

    companion object {
        fun fromJson(s: String?): Summary? {
            if (s.isNullOrEmpty()) return null
            return try {
                val j = JSONObject(s)
                val a = j.optJSONArray("entries") ?: return null
                Summary(j.optString("day"), if (j.isNull("project")) null else j.optString("project"),
                    (0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) })
            } catch (e: Exception) { null }
        }
    }
}

object Today {
    fun key(c: Calendar = Calendar.getInstance()): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    /** Montag bis Sonntag der aktuellen Woche. */
    private fun weekKeys(): List<String> {
        val c = Calendar.getInstance()
        val back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(Calendar.DAY_OF_MONTH, -back)
        return (0 until 7).map { val k = key(c); c.add(Calendar.DAY_OF_MONTH, 1); k }
    }

    private fun title(o: JSONObject) = o.optString("title").trim().ifEmpty { "Ohne Titel" }
    private fun ord(o: JSONObject): Double = if (o.has("order") && !o.isNull("order")) o.optDouble("order", 0.0) else o.optDouble("created", 0.0)
    private fun rel(o: JSONObject?): Set<String> {
        val a = o?.optJSONArray("rel") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }

    /** Gilt die Gewohnheit an diesem Tag als erledigt? Wie hdone() im Cockpit. */
    fun hdone(h: JSONObject, k: String, workoutDays: Set<String> = emptySet()): Boolean {
        if (h.optString("auto") == "workout" && k in workoutDays) return true
        val log = h.optJSONObject("log") ?: return false
        if (!log.has(k) || log.isNull(k)) return false
        val v = log.get(k)
        if (v == false || v == "") return false
        if (h.optString("mode", "check") == "number" && v is Number) {
            val goal = h.optDouble("goal", 0.0)
            return if (goal > 0) v.toDouble() >= goal else v.toDouble() > 0
        }
        return true
    }

    fun build(rows: List<Pair<String, JSONObject>>): Summary {
        val items = HashMap<String, JSONObject>()
        var settings = JSONObject()
        for ((id, d) in rows) {
            if (id == "__settings") settings = d
            else { if (!d.has("id")) d.put("id", id); items[id] = d }
        }
        val live = items.values.filter { !it.optBoolean("archived") }
        val tk = key()
        fun kind(o: JSONObject) = o.optString("kind")
        fun idOf(o: JSONObject) = o.optString("id")
        fun task(o: JSONObject, section: String) = Entry(idOf(o), title(o), "task", section, repeat = o.optString("repeat").isNotEmpty())

        val out = ArrayList<Entry>()

        // Die eine Sache heute
        val ot = settings.optJSONObject("oneThing")
        val one = if (ot != null && ot.optString("d") == tk) items[ot.optString("id")]?.takeIf { !it.optBoolean("archived") && !it.optBoolean("done") } else null
        one?.let { out.add(task(it, "one")) }

        val open = live.filter { kind(it) == "task" && !it.optBoolean("done") && (one == null || idOf(it) != idOf(one)) }

        // Heute fällig oder überfällig
        val due = open.filter { val d = it.optString("due"); d.isNotEmpty() && d <= tk }
            .sortedWith(compareBy<JSONObject>({ it.optString("due") }, { ord(it) }))
        due.forEach { out.add(task(it, "due")) }
        val dueIds = due.map { idOf(it) }.toSet()

        // Nächste Schritte im Hauptprojekt
        val mp = items[settings.optString("mainProject")]?.takeIf { !it.optBoolean("archived") }
        if (mp != null) {
            val pid = idOf(mp); val prel = rel(mp)
            open.filter { idOf(it) !in dueIds && (pid in rel(it) || idOf(it) in prel) }
                .sortedWith(compareBy<JSONObject>({ it.optString("due").ifEmpty { "9999" } }, { ord(it) }))
                .take(1) // nur der eine nächste Schritt, nicht die ganze Liste
                .forEach { out.add(task(it, "next")) }
        }

        // Gewohnheiten, die heute noch offen sind
        val workoutDays = live.filter { kind(it) == "workout" && it.optBoolean("done") }.map { it.optString("date") }.toSet()
        val week = weekKeys()
        live.filter { kind(it) == "habit" }
            .sortedBy { ord(it) }
            .filter { h ->
                if (hdone(h, tk, workoutDays)) return@filter false
                val target = h.optInt("target", 7)
                !(target in 1..6 && week.count { hdone(h, it, workoutDays) } >= target)
            }
            .forEach { h ->
                val opts = h.optJSONArray("options")
                out.add(Entry(
                    idOf(h), title(h), "habit", "habit", h.optString("mode", "check").ifEmpty { "check" },
                    h.optDouble("goal", 0.0), h.optString("unit"),
                    if (opts == null) emptyList() else (0 until opts.length()).map { opts.optString(it) }.filter { it.isNotBlank() }
                ))
            }

        return Summary(tk, mp?.let { title(it) }, out)
    }
}
