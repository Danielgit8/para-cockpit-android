package io.github.danielgit8.cockpit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Notifier.ensureChannel(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (store.session != null) Notifier.show(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false
        setOnClickListener { onClick() }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        root.addView(text("Cockpit Heute", 26f, true))
        root.addView(text("Zeigt deine offenen Aufgaben und Gewohnheiten aus dem PARA Cockpit dauerhaft in der Benachrichtigungsleiste."))

        val s = store.session
        if (s == null) {
            val email = EditText(this).apply { hint = "E-Mail"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT; setText(store.email) }
            val pw = EditText(this).apply { hint = "Passwort"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val status = text("", 14f)
            val go = button("Anmelden") {}
            go.setOnClickListener {
                val e = email.text.toString().trim(); val p = pw.text.toString()
                if (e.isEmpty() || p.isEmpty()) { status.text = "Bitte E-Mail und Passwort eingeben."; return@setOnClickListener }
                go.isEnabled = false; status.text = "Melde an …"
                Thread {
                    try {
                        val sess = Api.login(e, p)
                        store.email = e; store.session = sess; store.lastError = ""
                        runOnUiThread { askPermission(); Sync.schedule(this); Sync.now(this); Notifier.show(this); render() }
                    } catch (ex: Exception) {
                        val msg = if (ex is Api.HttpError && ex.code == 400) "E-Mail oder Passwort stimmt nicht." else "Keine Verbindung: ${ex.message}"
                        runOnUiThread { go.isEnabled = true; status.text = msg }
                    }
                }.start()
            }
            root.addView(text("Mit demselben Konto wie im Cockpit anmelden. Das Passwort wird nicht gespeichert.", 13f))
            root.addView(email); root.addView(pw); root.addView(go); root.addView(status)
        } else {
            root.addView(text("Angemeldet als ${s.email.ifEmpty { store.email }}", 14f))
            val last = if (store.lastSync > 0) SimpleDateFormat("dd.MM., HH:mm", Locale.GERMANY).format(Date(store.lastSync)) else "noch nie"
            root.addView(text("Zuletzt aktualisiert: $last", 14f))
            if (store.lastError.isNotEmpty()) root.addView(text("Hinweis: ${store.lastError}", 13f))
            if (!Notifier.allowed(this)) root.addView(button("Benachrichtigungen erlauben") { askPermission() })

            val sw = Switch(this).apply {
                text = "Benachrichtigung in der Leiste anzeigen"; isChecked = store.enabled; textSize = 15f
                setPadding(0, dp(12), 0, dp(12))
                setOnCheckedChangeListener { _, on ->
                    store.enabled = on
                    if (on) { Sync.schedule(this@MainActivity); Sync.now(this@MainActivity); Notifier.show(this@MainActivity) }
                    else { Sync.stop(this@MainActivity); Notifier.cancel(this@MainActivity) }
                }
            }
            root.addView(sw)
            root.addView(button("Jetzt aktualisieren") { Sync.now(this); root.postDelayed({ render() }, 3000) })
            root.addView(button("Cockpit öffnen") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Api.COCKPIT))) })

            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                root.addView(text("Damit sich die Liste auch im Hintergrund zuverlässig aktualisiert, schalte die Akku-Optimierung für diese App aus.", 13f))
                root.addView(button("Akku-Optimierung ausschalten") {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                })
            }
            root.addView(View(this).apply { minimumHeight = dp(16) })
            root.addView(button("Abmelden") {
                store.clear(); Sync.stop(this); Notifier.cancel(this); render()
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Notifier.show(this); render()
    }
}
