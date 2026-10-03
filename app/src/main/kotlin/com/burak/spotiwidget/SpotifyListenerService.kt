package com.burak.spotiwidget

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService

class SpotifyListenerService : NotificationListenerService() {

    companion object {
        @Volatile var controller: MediaController? = null
        // True while Spotify's notification is showing.
        @Volatile var notifPresent: Boolean = false
        // Spotify's notification large icon art, only trusted when it matches the current track title.
        @Volatile var notifArt: Bitmap? = null
        @Volatile var notifArtTitle: String? = null
        private const val SPOTIFY_PKG = "com.spotify.music"
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
        try { notifPresent = activeNotifications?.any { it.packageName == SPOTIFY_PKG } == true } catch (_: Exception) {}
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
        var changed = !notifPresent
        notifPresent = true

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
        if (sbn.packageName == SPOTIFY_PKG) notifPresent = false
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
        saveLast()
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

    private fun saveLast() {
        val c = controller ?: return
        val meta = c.metadata ?: return

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val prefs = getSharedPreferences("state", Context.MODE_PRIVATE)
        if (title.isNotBlank() && prefs.getString("last_title", null) != title) {
            prefs.edit().putString("last_title", title).putString("last_artist", artist).apply()
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
}
