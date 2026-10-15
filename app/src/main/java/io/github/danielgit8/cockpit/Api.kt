package io.github.danielgit8.cockpit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Zugriff auf dasselbe Supabase-Projekt wie das PARA Cockpit (nur lesen). */
object Api {
    const val BASE = "https://yzntbxbdxtdqgddyicdn.supabase.co"
    // Öffentlicher Schlüssel wie in der Web-App. Die Daten schützt die Zugriffsregel in der Datenbank.
    const val KEY = "sb_publishable_0hu09OCTj9H1dnam_ztXYQ_0OfPEk1R"
    const val COCKPIT = "https://danielgit8.github.io/para-cockpit/"

    class HttpError(val code: Int, body: String) : Exception("HTTP $code: ${body.take(200)}")

    data class Session(val access: String, val refresh: String, val expiresAt: Long, val email: String)

    private fun call(method: String, path: String, body: String?, token: String?, headers: Map<String, String> = emptyMap()): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun session(text: String, fallbackEmail: String): Session {
        val j = JSONObject(text)
        val email = j.optJSONObject("user")?.optString("email").orEmpty().ifEmpty { fallbackEmail }
        return Session(
            j.getString("access_token"),
            j.getString("refresh_token"),
            System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600),
            email
        )
    }

    fun login(email: String, password: String): Session {
        val body = JSONObject().put("email", email).put("password", password).toString()
        return session(call("POST", "/auth/v1/token?grant_type=password", body, null), email)
    }

    fun refresh(old: Session): Session {
        val body = JSONObject().put("refresh_token", old.refresh).toString()
        return session(call("POST", "/auth/v1/token?grant_type=refresh_token", body, null), old.email)
    }

    /** Konto-ID aus dem Anmelde-Schlüssel (JWT, Feld "sub"). */
    fun userId(token: String): String {
        val part = token.split(".").getOrNull(1) ?: return ""
        val json = String(android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP), Charsets.UTF_8)
        return JSONObject(json).optString("sub")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Ein einzelner Eintrag, frisch vom Server. */
    fun item(token: String, id: String): JSONObject? {
        val arr = JSONArray(call("GET", "/rest/v1/items?select=data,deleted&id=eq.${enc(id)}", null, token))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        if (o.optBoolean("deleted")) return null
        return o.optJSONObject("data")
    }

    /** Speichert einen Eintrag genau wie das Cockpit (upsert mit updated_at). */
    fun save(token: String, id: String, data: JSONObject, updated: Long) {
        val row = JSONObject().put("user_id", userId(token)).put("id", id).put("data", data)
            .put("updated_at", updated).put("deleted", false)
        call(
            "POST", "/rest/v1/items?on_conflict=user_id,id", JSONArray().put(row).toString(), token,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    /** Alle nicht gelöschten Einträge, als (id, data). Die Einstellungen stehen unter der id "__settings". */
    fun items(token: String): List<Pair<String, JSONObject>> {
        val out = ArrayList<Pair<String, JSONObject>>()
        val page = 1000
        var from = 0
        while (true) {
            val text = call(
                "GET", "/rest/v1/items?select=id,data&deleted=eq.false&order=id", null, token,
                mapOf("Range-Unit" to "items", "Range" to "$from-${from + page - 1}")
            )
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.getString("id") to (o.optJSONObject("data") ?: JSONObject()))
            }
            if (arr.length() < page) break
            from += page
        }
        return out
    }
}
