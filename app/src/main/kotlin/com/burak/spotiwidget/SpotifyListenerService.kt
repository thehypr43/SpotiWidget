package com.burak.spotiwidget

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SpotifyListenerService : NotificationListenerService() {

    companion object {
        @Volatile var controller: MediaController? = null
        // Spotify's own notification tap target: opens the now-playing screen.
        @Volatile var notifIntent: android.app.PendingIntent? = null
        // Spotify's notification large icon art, only trusted when it matches the current track title.
        @Volatile var notifArt: Bitmap? = null
        @Volatile var notifArtTitle: String? = null
        // Raw extras of Spotify's last notification, for context candidates and MainActivity diagnostics.
        @Volatile var notifExtras: Bundle? = null

        private const val SPOTIFY_PKG = "com.spotify.music"
        private val COLLECTION_RE = Regex("spotify:(user:[^:\\s]+:)?collection")
        private val TYPED_URI_RE = Regex("spotify:(?:user:[^:\\s]+:)?(playlist|album|artist|show):([A-Za-z0-9]+)")
        private val NOTIF_TEXT_KEYS = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT
        )
    }

    private lateinit var msm: MediaSessionManager
    private lateinit var cn: ComponentName
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onSessionDestroyed() {
            controller = null
            onChange()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onChange()
                Intent.ACTION_SCREEN_OFF -> stopTicker()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (controller?.playbackState?.state != PlaybackState.STATE_PLAYING) return
            WidgetRenderer.updateProgress(this@SpotifyListenerService)
            // One progress unit (1/1000) per tick: smooth line, no wasted updates.
            val d = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            mainHandler.postDelayed(this, (d / 1000).coerceIn(100L, 1000L))
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try { activeNotifications?.firstOrNull { it.packageName == SPOTIFY_PKG && it.notification.contentIntent != null }?.let { notifIntent = it.notification.contentIntent } } catch (_: Exception) {}
        msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        cn = ComponentName(this, SpotifyListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, mainHandler)
            pick(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
            pick(emptyList())
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PKG) return
        var changed = false

        sbn.notification.contentIntent?.let { if (it != notifIntent) { notifIntent = it; changed = true } }
        notifExtras = sbn.notification.extras

        val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        try {
            val drawable = sbn.notification.getLargeIcon()?.loadDrawable(this)
            if (drawable != null && (notifArt == null || title != notifArtTitle)) {
                notifArt = WidgetRenderer.scaleArtMax(drawableToBitmap(drawable))
                notifArtTitle = title
                changed = true
            }
        } catch (_: Exception) {
        }

        if (changed) WidgetRenderer.update(this)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName == SPOTIFY_PKG) notifIntent = null
    }

    override fun onListenerDisconnected() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        try { controller?.unregisterCallback(mediaCallback) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        controller = null
        stopTicker()
        WidgetRenderer.update(this)
        requestRebind(ComponentName(this, SpotifyListenerService::class.java))
    }

    private fun pick(list: List<MediaController>?) {
        val found = list?.firstOrNull { it.packageName == SPOTIFY_PKG }
        val current = controller
        if (found?.sessionToken != current?.sessionToken) {
            try { current?.unregisterCallback(mediaCallback) } catch (_: Exception) {}
            controller = found
            try { found?.registerCallback(mediaCallback, mainHandler) } catch (_: Exception) {}
        }
        onChange()
    }

    private fun onChange() {
        captureContext()
        WidgetRenderer.update(this)
        val state = controller?.playbackState?.state
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (state == PlaybackState.STATE_PLAYING && pm.isInteractive) startTicker() else stopTicker()
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
    }

    private fun captureContext() {
        val c = controller ?: return
        val meta = c.metadata ?: return

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val prefs = getSharedPreferences("state", Context.MODE_PRIVATE)
        if (title.isNotBlank() && prefs.getString("last_title", null) != title) {
            prefs.edit().putString("last_title", title).putString("last_artist", artist).apply()
        }

        val candidates = mutableListOf<String>()
        for (key in meta.keySet()) {
            try {
                meta.getString(key)?.let { candidates.add(it) }
            } catch (_: Exception) {
            }
        }
        collectBundleStrings(c.extras, candidates)
        collectBundleStrings(c.playbackState?.extras, candidates)

        try {
            c.queue?.take(3)?.forEach { item ->
                val d = item.description
                d.mediaId?.let { candidates.add(it) }
                d.title?.toString()?.let { candidates.add(it) }
                d.subtitle?.toString()?.let { candidates.add(it) }
                collectBundleStrings(d.extras, candidates)
            }
        } catch (_: Exception) {
        }

        notifExtras?.let { extras ->
            for (key in NOTIF_TEXT_KEYS) {
                try {
                    extras.getCharSequence(key)?.toString()?.let { candidates.add(it) }
                } catch (_: Exception) {
                }
            }
        }

        // Playlist wins over album: track metadata may carry its own album URI.
        val all = candidates.joinToString(" ")
        val uri = listOf(
            "spotify:(user:[^:\\s]+:)?playlist:[A-Za-z0-9]+",
            "spotify:(show|artist):[A-Za-z0-9]+",
            "spotify:(user:[^:\\s]+:)?collection",
            "spotify:album:[A-Za-z0-9]+"
        ).firstNotNullOfOrNull { Regex(it).find(all)?.value }

        if (uri != null && prefs.getString("ctx_uri", null) != uri) {
            prefs.edit().putString("ctx_uri", uri).apply()
            resolveContext(uri)
        }
    }

    private fun collectBundleStrings(b: Bundle?, out: MutableList<String>) {
        if (b == null) return
        for (key in b.keySet()) {
            try {
                (b.get(key) as? String)?.let { out.add(it) }
            } catch (_: Exception) {
            }
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, canvas.width, canvas.height)
        d.draw(canvas)
        return bmp
    }

    private fun resolveContext(uri: String) {
        Thread {
            try {
                val prefs = getSharedPreferences("state", Context.MODE_PRIVATE)
                if (COLLECTION_RE.matches(uri)) {
                    prefs.edit().putString("ctx_name", "Beğenilen Şarkılar").apply()
                    deleteFile("ctx_art.png")
                } else {
                    val m = TYPED_URI_RE.find(uri) ?: return@Thread
                    val path = "${m.groupValues[1]}/${m.groupValues[2]}"
                    val url = "https://open.spotify.com/oembed?url=" + Uri.encode("https://open.spotify.com/$path")
                    val json = httpGet(url) ?: return@Thread
                    val obj = JSONObject(json)

                    if (obj.has("title")) {
                        prefs.edit().putString("ctx_name", obj.getString("title")).apply()
                    }
                    if (obj.has("thumbnail_url")) {
                        val raw = downloadBitmap(obj.getString("thumbnail_url"))
                        if (raw != null) {
                            val scaled = WidgetRenderer.scaleArtMax(raw)
                            openFileOutput("ctx_art.png", Context.MODE_PRIVATE).use {
                                scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                    }
                }
                WidgetRenderer.invalidateIdleArt()
                mainHandler.post { WidgetRenderer.update(this) }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun httpGet(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }
}
