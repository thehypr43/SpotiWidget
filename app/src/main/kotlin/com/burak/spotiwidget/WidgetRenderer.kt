package com.burak.spotiwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.URL

object WidgetRenderer {

    private val mainHandler = Handler(Looper.getMainLooper())

    // per-track art cache: only ever caches a non-null bitmap, so a miss retries every update.
    private var cachedTrackKey: String? = null
    private var cachedTrackArt: Bitmap? = null
    private var loadingUri: String? = null
    private var lastPersistedKey: String? = null

    // optimistic play/pause state: set by the provider on user toggle, wins over the real
    // (possibly flickering) MediaSession state until it catches up or the window expires.
    private var pendingPlaying: Boolean? = null
    private var pendingUntil: Long = 0L

    fun setPendingPlaying(ctx: Context, playing: Boolean) {
        pendingPlaying = playing
        pendingUntil = SystemClock.elapsedRealtime() + 1200
        update(ctx)
    }

    // idle art cache: loaded from disk once, invalidated by SpotifyListenerService when ctx changes.
    private var idleCtxLoaded = false
    private var idleCtxArt: Bitmap? = null
    private var idleLastLoaded = false
    private var idleLastArt: Bitmap? = null

    fun invalidateIdleArt() {
        idleCtxLoaded = false
        idleCtxArt = null
    }

    fun position(c: MediaController): Long {
        val state = c.playbackState ?: return 0L
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        if (pos < 0) pos = 0
        if (duration > 0 && pos > duration) pos = duration
        return pos
    }

    /** Real portrait (smallest-width) height for the widget, so the square art column matches actual height. */
    private fun portraitHeightDp(opts: Bundle): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = opts.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            val portrait = sizes?.minByOrNull { it.width }
            if (portrait != null && portrait.height > 0) return portrait.height.toInt()
        }
        return opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: 72
    }

    fun scaleArtMax(bmp: Bitmap, max: Int = 240): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= max && h <= max) return bmp
        val scale = max.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    fun update(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return

            val c = SpotifyListenerService.controller
            val meta = c?.metadata
            val state = c?.playbackState?.state
            val trackMode = c != null && meta != null && (
                state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                (state == PlaybackState.STATE_PAUSED && SpotifyListenerService.notifIntent != null)
            )
            val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

            val now = SystemClock.elapsedRealtime()
            if (pendingPlaying != null && (playing == pendingPlaying || now >= pendingUntil)) {
                pendingPlaying = null
            }
            val effectivePlaying = pendingPlaying ?: playing

            val views = RemoteViews(ctx.packageName, R.layout.widget)
            val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

            val contentIntent: PendingIntent

            if (trackMode) {
                val title = meta!!.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                views.setTextViewText(R.id.title, title)
                views.setTextViewText(R.id.subtitle, artist)
                if (title.isNotBlank() && prefs.getString("last_title", null) != title) {
                    prefs.edit().putString("last_title", title).putString("last_artist", artist).apply()
                }

                val art = trackArt(ctx, meta)
                if (art != null) views.setImageViewBitmap(R.id.art, art) else views.setImageViewResource(R.id.art, R.drawable.ic_music_note)

                contentIntent = SpotifyListenerService.notifIntent ?: c!!.sessionActivity ?: launchSpotifyIntent(ctx)
            } else {
                val ctxName = prefs.getString("ctx_name", null)
                val name = ctxName ?: prefs.getString("last_title", null) ?: "Spotify"
                val subtitle = if (ctxName != null) "Son çalınan liste" else (prefs.getString("last_artist", null) ?: "")
                views.setTextViewText(R.id.title, name)
                views.setTextViewText(R.id.subtitle, subtitle)

                val idleArt = idleArt(ctx, prefs)
                if (idleArt != null) views.setImageViewBitmap(R.id.art, idleArt) else views.setImageViewResource(R.id.art, R.drawable.ic_music_note)

                val uri = prefs.getString("ctx_uri", null) ?: "spotify:collection"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    setPackage("com.spotify.music")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                contentIntent = PendingIntent.getActivity(ctx, 200, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

            views.setOnClickPendingIntent(R.id.title, contentIntent)
            views.setOnClickPendingIntent(R.id.subtitle, contentIntent)
            views.setOnClickPendingIntent(R.id.root, contentIntent)

            val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val pos = c?.let { position(it) } ?: 0L
            val progress = if (duration > 0) (pos * 1000 / duration).toInt() else 0
            views.setProgressBar(R.id.progress, 1000, progress, false)

            views.setCompoundButtonChecked(R.id.playpause, effectivePlaying)
            val playPausePI = actionPendingIntent(ctx, "com.burak.spotiwidget.PLAYPAUSE", 2)
            views.setOnCheckedChangeResponse(R.id.playpause, RemoteViews.RemoteResponse.fromPendingIntent(playPausePI))

            views.setOnClickPendingIntent(R.id.prev, actionPendingIntent(ctx, "com.burak.spotiwidget.PREV", 1))
            views.setOnClickPendingIntent(R.id.next, actionPendingIntent(ctx, "com.burak.spotiwidget.NEXT", 3))

            val likeAction = c?.playbackState?.let { findLikeAction(it) }
            if (likeAction != null) {
                views.setViewVisibility(R.id.like, View.VISIBLE)
                views.setCompoundButtonChecked(R.id.like, isLiked(likeAction))
                val likePI = actionPendingIntent(ctx, "com.burak.spotiwidget.LIKE", 4)
                views.setOnCheckedChangeResponse(R.id.like, RemoteViews.RemoteResponse.fromPendingIntent(likePI))
            } else {
                views.setViewVisibility(R.id.like, View.GONE)
            }

            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val heightDp = portraitHeightDp(opts)
                val perId = RemoteViews(views)
                perId.setViewLayoutWidth(R.id.art, heightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                mgr.updateAppWidget(id, perId)
            }
        } catch (_: Exception) {
        }
    }

    private val LIKE_KEYWORDS = listOf(
        "collection", "like", "heart", "save", "favorite", "favourite", "library", "beğen", "kaydet"
    )
    private val UNLIKE_KEYWORDS = listOf("remove", "unlike", "kaldır", "unsave")

    fun findLikeAction(state: PlaybackState): PlaybackState.CustomAction? {
        return state.customActions?.firstOrNull { action ->
            val text = ("${action.action} ${action.name}").lowercase()
            LIKE_KEYWORDS.any { text.contains(it) }
        }
    }

    fun isLiked(action: PlaybackState.CustomAction): Boolean {
        val text = ("${action.action} ${action.name}").lowercase()
        return UNLIKE_KEYWORDS.any { text.contains(it) }
    }

    private var lastProgress = -1

    fun updateProgress(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val c = SpotifyListenerService.controller ?: return
            val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (duration <= 0) return
            val progress = (position(c) * 1000 / duration).toInt()
            if (progress == lastProgress) return
            lastProgress = progress
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setProgressBar(R.id.progress, 1000, progress, false)
            mgr.partiallyUpdateAppWidget(ids, views)
        } catch (_: Exception) {
        }
    }

    /** Immediate optimistic progress update to [fraction] (0..1) right after a seek drag, before the real position ticks in. */
    fun setProgressFraction(ctx: Context, fraction: Double) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setProgressBar(R.id.progress, 1000, (fraction * 1000).toInt().coerceIn(0, 1000), false)
            mgr.partiallyUpdateAppWidget(ids, views)
        } catch (_: Exception) {
        }
    }

    /**
     * Resolves art for the current track. Cache key is the media id (or title); only a non-null
     * bitmap is ever cached, so a miss is retried on every call instead of sticking forever.
     * Order: metadata bitmaps -> Spotify's notification large icon (if it matches this track's
     * title) -> art uri loaded async on a background thread.
     */
    private fun trackArt(ctx: Context, meta: MediaMetadata): Bitmap? {
        val key = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: meta.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (key != null && key == cachedTrackKey && cachedTrackArt != null) return cachedTrackArt

        val metaBmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
        val notifBmp = if (metaBmp == null && SpotifyListenerService.notifArtTitle != null && SpotifyListenerService.notifArtTitle == title) {
            SpotifyListenerService.notifArt
        } else null

        val bmp = metaBmp ?: notifBmp
        if (bmp != null) {
            val scaled = scaleArtMax(bmp)
            cachedTrackKey = key
            cachedTrackArt = scaled
            persistLastArt(ctx, key, scaled)
            return scaled
        }

        cachedTrackKey = key
        cachedTrackArt = null

        val uri = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri != null && uri != loadingUri) {
            loadingUri = uri
            loadArtFromUriAsync(ctx, key, uri)
        }
        return null
    }

    private fun loadArtFromUriAsync(ctx: Context, key: String?, uri: String) {
        Thread {
            try {
                val raw = if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    val conn = URL(uri).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    try {
                        conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        conn.disconnect()
                    }
                } else {
                    ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                }
                if (raw != null && cachedTrackKey == key) {
                    val scaled = scaleArtMax(raw)
                    cachedTrackArt = scaled
                    persistLastArt(ctx, key, scaled)
                    mainHandler.post { update(ctx) }
                }
            } catch (_: Exception) {
            } finally {
                if (loadingUri == uri) loadingUri = null
            }
        }.start()
    }

    private fun persistLastArt(ctx: Context, key: String?, bmp: Bitmap) {
        if (key == null || key == lastPersistedKey) return
        lastPersistedKey = key
        idleLastLoaded = true
        idleLastArt = bmp
        try {
            ctx.openFileOutput("last_art.png", Context.MODE_PRIVATE).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
        }
    }

    private fun idleArt(ctx: Context, prefs: SharedPreferences): Bitmap? {
        if (!idleCtxLoaded) {
            idleCtxLoaded = true
            idleCtxArt = loadArtFile(ctx, "ctx_art.png")
        }
        if (idleCtxArt != null) return idleCtxArt

        if (!idleLastLoaded) {
            idleLastLoaded = true
            idleLastArt = loadArtFile(ctx, "last_art.png")
        }
        return idleLastArt
    }

    private fun loadArtFile(ctx: Context, name: String): Bitmap? {
        return try {
            ctx.openFileInput(name).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun actionPendingIntent(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, WidgetProvider::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun launchSpotifyIntent(ctx: Context): PendingIntent {
        val launch = ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("spotify:")).apply { setPackage("com.spotify.music") }
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return PendingIntent.getActivity(ctx, 200, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
