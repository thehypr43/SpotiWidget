package com.burak.spotiwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var nowLabel: TextView
    private lateinit var nowArt: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var notifChip: TextView
    private lateinit var notifHint: TextView
    private lateinit var notifButton: TextView
    private lateinit var widgetChip: TextView
    private lateinit var widgetButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            bindNowPlaying()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nowLabel = findViewById(R.id.nowLabel)
        nowArt = findViewById(R.id.nowArt)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)
        nowProgress = findViewById(R.id.nowProgress)
        notifChip = findViewById(R.id.notifChip)
        notifHint = findViewById(R.id.notifHint)
        notifButton = findViewById(R.id.notifButton)
        widgetChip = findViewById(R.id.widgetChip)
        widgetButton = findViewById(R.id.widgetButton)

        findViewById<View>(R.id.nowCard).setOnClickListener {
            packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { startActivity(it) }
        }
        notifButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        widgetButton.setOnClickListener { pinWidget() }
    }

    override fun onResume() {
        super.onResume()
        bindSetup()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun bindNowPlaying() {
        val c = SpotifyListenerService.controller
        val meta = c?.metadata
        val state = c?.playbackState?.state
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        val prefs = getSharedPreferences("state", MODE_PRIVATE)

        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: prefs.getString("last_title", null)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: prefs.getString("last_artist", null)

        nowLabel.setText(if (playing) R.string.label_now_playing else R.string.label_last_played)
        nowLabel.setTextColor(getColor(if (playing) R.color.green else R.color.text_faint))
        if (title != null) {
            nowTitle.text = title
            nowArtist.text = artist ?: ""
        } else {
            nowTitle.setText(R.string.nothing_played)
            nowArtist.setText(R.string.nothing_played_sub)
        }

        val art = if (title != null) WidgetRenderer.currentArt(this) ?: SpotifyListenerService.notifArt else null
        if (art != null) {
            nowArt.scaleType = ImageView.ScaleType.CENTER_CROP
            nowArt.setImageBitmap(art)
        } else {
            nowArt.scaleType = ImageView.ScaleType.CENTER
            nowArt.setImageResource(R.drawable.ic_music_note)
        }

        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        nowProgress.progress = if (c != null && duration > 0) (WidgetRenderer.position(c) * 1000 / duration).toInt() else 0
    }

    private fun bindSetup() {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val granted = listeners.contains(packageName)
        setChip(notifChip, granted, R.string.chip_on, R.string.chip_off)
        notifHint.visibility = if (granted) View.GONE else View.VISIBLE
        setButton(notifButton, primary = !granted, if (granted) R.string.notif_settings else R.string.notif_grant)

        val widgetIds = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, WidgetProvider::class.java))
        val added = widgetIds.isNotEmpty()
        setChip(widgetChip, added, R.string.chip_added, R.string.chip_not_added)
        setButton(widgetButton, primary = !added, if (added) R.string.widget_add_more else R.string.widget_add)
    }

    private fun setChip(chip: TextView, on: Boolean, onText: Int, offText: Int) {
        chip.setText(if (on) onText else offText)
        chip.setTextColor(getColor(if (on) R.color.green else R.color.warn))
        chip.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
    }

    private fun setButton(button: TextView, primary: Boolean, text: Int) {
        button.setText(text)
        button.setTextColor(if (primary) 0xFF000000.toInt() else getColor(R.color.text))
        button.setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_secondary)
    }

    private fun pinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, WidgetProvider::class.java)
        val requested = mgr.isRequestPinAppWidgetSupported && mgr.requestPinAppWidget(provider, null, null)
        if (!requested) Toast.makeText(this, R.string.widget_add_manual, Toast.LENGTH_LONG).show()
    }
}
