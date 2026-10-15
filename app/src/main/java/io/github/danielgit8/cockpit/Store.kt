package io.github.danielgit8.cockpit

import android.content.Context

/** Kleine Ablage auf dem Handy. Das Passwort wird nie gespeichert, nur der Anmelde-Schlüssel. */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    var session: Api.Session?
        get() {
            val r = p.getString("refresh", null) ?: return null
            return Api.Session(p.getString("access", "").orEmpty(), r, p.getLong("exp", 0), email)
        }
        set(s) {
            val e = p.edit()
            if (s == null) {
                e.remove("access").remove("refresh").remove("exp")
            } else {
                e.putString("access", s.access).putString("refresh", s.refresh).putLong("exp", s.expiresAt)
                if (s.email.isNotEmpty()) e.putString("email", s.email)
            }
            e.commit()
        }

    var email: String
        get() = p.getString("email", "").orEmpty()
        set(v) { p.edit().putString("email", v).commit() }

    var summary: String?
        get() = p.getString("summary", null)
        set(v) { p.edit().putString("summary", v).commit() }

    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) { p.edit().putLong("lastSync", v).commit() }

    var lastError: String
        get() = p.getString("lastError", "").orEmpty()
        set(v) { p.edit().putString("lastError", v).commit() }

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).commit() }

    fun clear() {
        p.edit().remove("access").remove("refresh").remove("exp").remove("summary").remove("lastSync").remove("lastError").commit()
    }
}
