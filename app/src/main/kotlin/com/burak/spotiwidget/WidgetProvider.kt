package com.burak.spotiwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.KeyEvent
import android.widget.RemoteViews

class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.update(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetRenderer.update(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        try {
            when (intent.action) {
                "com.burak.spotiwidget.PLAYPAUSE" -> {
                    val c = SpotifyListenerService.controller
                    if (c != null) {
                        val st = c.playbackState?.state
                        val currentlyPlaying = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                        val wantPlay = if (intent.hasExtra(RemoteViews.EXTRA_CHECKED)) {
                            intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, currentlyPlaying)
                        } else {
                            !currentlyPlaying
                        }
                        WidgetRenderer.setPendingPlaying(context, wantPlay)
                        if (wantPlay) c.transportControls.play() else c.transportControls.pause()
                    } else {
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                    }
                }
                "com.burak.spotiwidget.NEXT" -> {
                    val c = SpotifyListenerService.controller
                    if (c != null) c.transportControls.skipToNext() else sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                }
                "com.burak.spotiwidget.PREV" -> {
                    val c = SpotifyListenerService.controller
                    if (c != null) c.transportControls.skipToPrevious() else sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
                "com.burak.spotiwidget.LIKE" -> {
                    val c = SpotifyListenerService.controller
                    val action = c?.playbackState?.let { WidgetRenderer.findLikeAction(it) }
                    if (c != null && action != null) {
                        c.transportControls.sendCustomAction(action.action, action.extras)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sendMediaKey(ctx: Context, code: Int) {
        try {
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage("com.spotify.music")
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            ctx.sendBroadcast(down)
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage("com.spotify.music")
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, code))
            ctx.sendBroadcast(up)
        } catch (_: Exception) {
        }
    }
}
