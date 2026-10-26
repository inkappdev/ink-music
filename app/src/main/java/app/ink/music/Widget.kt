package app.ink.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews

/**
 * The widget: the mini player on the home screen (the cover, the title, the artist, play / pause
 * and next as words; a tap elsewhere opens ink music). In the player's process, which tells it
 * what's playing as it changes ([show]); kept in prefs, so a widget added later shows it at once.
 * Its words reach the player as a headset's would (media buttons), so they work with the app closed.
 */
class Widget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) = draw(c)

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

        /** What plays now (from the player): kept, and every widget drawn anew. */
        fun show(c: Context, id: String?, title: String, artist: String, playing: Boolean) {
            prefs(c).edit().putString("id", id).putString("title", title).putString("artist", artist).putBoolean("playing", playing).apply()
            draw(c)
        }

        fun draw(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, Widget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(c))
        }

        /** The widget as it is now. */
        fun views(c: Context): RemoteViews {
            val p = prefs(c)
            val id = p.getString("id", null)
            val v = RemoteViews(c.packageName, R.layout.widget)
            if (id == null) {
                v.setTextViewText(R.id.title, "INK MUSIC")
                v.setTextViewText(R.id.artist, "play something")
                v.setImageViewResource(R.id.cover, android.R.color.black)
            } else {
                v.setTextViewText(R.id.title, p.getString("title", "").orEmpty())
                v.setTextViewText(R.id.artist, p.getString("artist", "").orEmpty().lowercase())
                // the cover from ink music's own covers (a song's record's, or YouTube's); none, black
                v.setImageViewUri(R.id.cover, CoverProvider.of(id))
            }
            v.setTextViewText(R.id.toggle, if (p.getBoolean("playing", false)) "pause" else "play")
            v.setOnClickPendingIntent(R.id.toggle, button(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.next, button(c, KeyEvent.KEYCODE_MEDIA_NEXT))
            v.setOnClickPendingIntent(R.id.widget, PendingIntent.getActivity(
                c, 0, Intent(c, Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            return v
        }

        /** A media button for the player (Media3's service takes them, starting it if it isn't running). */
        private fun button(c: Context, key: Int): PendingIntent = PendingIntent.getForegroundService(
            c, key,
            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(c, PlayerService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
