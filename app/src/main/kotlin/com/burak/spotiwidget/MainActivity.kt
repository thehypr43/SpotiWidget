package com.burak.spotiwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var a11yStatusText: TextView
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusText = TextView(this).apply { textSize = 14f }
        val button = Button(this).apply {
            text = "Bildirim erişimini aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        a11yStatusText = TextView(this).apply { textSize = 14f }
        val a11yButton = Button(this).apply {
            text = "Kaydırmalı slider için Erişilebilirlik'i aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val diagLabel = TextView(this).apply {
            text = "Tanı"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        diagText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        root.addView(statusText)
        root.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)))
        root.addView(button)
        root.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        root.addView(a11yStatusText)
        root.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)))
        root.addView(a11yButton)
        root.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        root.addView(diagLabel)
        root.addView(diagText)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val granted = enabled.contains(packageName)
        statusText.text = buildString {
            append(if (granted) "Bildirim erişimi: VAR" else "Bildirim erişimi: YOK")
            append("\n\n1) Aşağıdaki butondan SpotiWidget için bildirim erişimini açın.")
            append("\n2) Ana ekrana dönüp widget'ı ekleyin (uzun bas > Widget'lar > SpotiWidget).")
            append("\n\nAndroid 13+ : anahtar gri/pasifse Ayarlar > Uygulamalar > SpotiWidget > sağ üst ⋮ > \"Kısıtlanmış ayarlara izin ver\".")
        }
        val a11yServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val a11yGranted = a11yServices.contains("$packageName/")
        a11yStatusText.text = buildString {
            append(if (a11yGranted) "Kaydırmalı slider (Erişilebilirlik): VAR" else "Kaydırmalı slider (Erişilebilirlik): YOK")
            append("\n\nWidget'taki şarkı çubuğunu parmakla kaydırabilmek için gerekli. Dokunma ile atlamayı önler.")
            append("\n\nAndroid 13+ : anahtar gri/pasifse Ayarlar > Uygulamalar > SpotiWidget > sağ üst ⋮ > \"Kısıtlanmış ayarlara izin ver\", sonra tekrar deneyin.")
        }
        diagText.text = buildDiagnostics()
    }

    private fun buildDiagnostics(): String = buildString {
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        append("ctx_uri=").append(prefs.getString("ctx_uri", "null")).append('\n')
        append("ctx_name=").append(prefs.getString("ctx_name", "null")).append('\n')
        append("last_title=").append(prefs.getString("last_title", "null")).append('\n')
        append("last_artist=").append(prefs.getString("last_artist", "null")).append('\n')

        val c = SpotifyListenerService.controller
        if (c == null) {
            append("controller=null\n")
            return@buildString
        }
        append("queueTitle=").append(c.queueTitle ?: "null").append('\n')

        val meta = c.metadata
        if (meta != null) {
            append("--- metadata ---\n")
            for (key in meta.keySet()) {
                val s = try { meta.getString(key) } catch (_: Exception) { null }
                if (s != null) {
                    append(key).append('=').append(s).append('\n')
                } else {
                    val bmp = try { meta.getBitmap(key) } catch (_: Exception) { null }
                    if (bmp != null) append(key).append("=bitmap\n")
                }
            }
        }

        append("--- controller.extras ---\n")
        c.extras?.let { b -> for (key in b.keySet()) append(key).append('=').append(safeGet(b, key)).append('\n') }
        append("--- playbackState.extras ---\n")
        c.playbackState?.extras?.let { b -> for (key in b.keySet()) append(key).append('=').append(safeGet(b, key)).append('\n') }

        append("--- queue (first 3) ---\n")
        try {
            c.queue?.take(3)?.forEachIndexed { i, item ->
                val d = item.description
                append(i).append(": mediaId=").append(d.mediaId).append(" title=").append(d.title)
                    .append(" subtitle=").append(d.subtitle).append('\n')
                d.extras?.let { b -> for (key in b.keySet()) append("  ").append(key).append('=').append(safeGet(b, key)).append('\n') }
            }
        } catch (_: Exception) {
        }

        append("--- custom actions ---\n")
        c.playbackState?.customActions?.forEach { a ->
            append(a.action).append(" | ").append(a.name).append('\n')
        }

        append("--- spotify notification extras ---\n")
        SpotifyListenerService.notifExtras?.let { b ->
            for (key in b.keySet()) {
                val v = try { b.get(key) } catch (_: Exception) { null }
                if (v is String || v is CharSequence) append(key).append('=').append(v).append('\n')
            }
        }
    }

    private fun safeGet(b: Bundle, key: String): Any? = try { b.get(key) } catch (_: Exception) { null }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
