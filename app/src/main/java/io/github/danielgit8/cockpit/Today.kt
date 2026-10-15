package io.github.danielgit8.cockpit

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Was heute offen ist. Gleiche Regeln wie im Cockpit: fällige Aufgaben, die eine Sache, die nächsten Schritte im Hauptprojekt, offene Gewohnheiten. */
data class Summary(
    val day: String,
    val one: String?,
    val tasks: List<String>,
    val project: String?,
    val next: List<String>,
    val habits: List<String>
) {
    val taskCount get() = (if (one != null) 1 else 0) + tasks.size + next.size

    fun toJson(): String = JSONObject()
        .put("day", day).put("one", one ?: JSONObject.NULL)
        .put("tasks", JSONArray(tasks)).put("project", project ?: JSONObject.NULL)
        .put("next", JSONArray(next)).put("habits", JSONArray(habits)).toString()

    companion object {
        fun fromJson(s: String?): Summary? {
            if (s.isNullOrEmpty()) return null
            return try {
                val j = JSONObject(s)
                fun list(k: String): List<String> { val a = j.optJSONArray(k) ?: return emptyList(); return (0 until a.length()).map { a.optString(it) } }
                fun str(k: String): String? = if (j.isNull(k)) null else j.optString(k).ifEmpty { null }
                Summary(j.optString("day"), str("one"), list("tasks"), str("project"), list("next"), list("habits"))
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

        // Die eine Sache heute
        val ot = settings.optJSONObject("oneThing")
        val oneItem = if (ot != null && ot.optString("d") == tk) items[ot.optString("id")]?.takeIf { !it.optBoolean("archived") && !it.optBoolean("done") } else null

        val open = live.filter { kind(it) == "task" && !it.optBoolean("done") && (oneItem == null || idOf(it) != idOf(oneItem)) }

        // Heute fällig oder überfällig
        val due = open.filter { val d = it.optString("due"); d.isNotEmpty() && d <= tk }
            .sortedWith(compareBy<JSONObject>({ it.optString("due") }, { ord(it) }))
        val dueIds = due.map { idOf(it) }.toSet()

        // Nächste Schritte im Hauptprojekt
        val mp = items[settings.optString("mainProject")]?.takeIf { !it.optBoolean("archived") }
        val next = if (mp == null) emptyList() else {
            val pid = idOf(mp); val prel = rel(mp)
            open.filter { idOf(it) !in dueIds && (pid in rel(it) || idOf(it) in prel) }
                .sortedWith(compareBy<JSONObject>({ it.optString("due").ifEmpty { "9999" } }, { ord(it) }))
                .take(3)
        }

        // Gewohnheiten, die heute noch offen sind
        val workoutDays = live.filter { kind(it) == "workout" && it.optBoolean("done") }.map { it.optString("date") }.toSet()
        fun hdone(h: JSONObject, k: String): Boolean {
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
        val week = weekKeys()
        val habits = live.filter { kind(it) == "habit" }
            .sortedBy { ord(it) }
            .filter { h ->
                if (hdone(h, tk)) return@filter false
                val target = h.optInt("target", 7)
                !(target in 1..6 && week.count { hdone(h, it) } >= target)
            }
            .map { title(it) }

        return Summary(tk, oneItem?.let { title(it) }, due.map { title(it) }, mp?.let { title(it) }, next.map { title(it) }, habits)
    }
}
