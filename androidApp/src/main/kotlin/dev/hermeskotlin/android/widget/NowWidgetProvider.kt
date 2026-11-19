package dev.hermeskotlin.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Now: who needs you, what is running, and when everything is quiet (docs/WIDGETS-DESIGN.md §6). */
class NowWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        goAsyncRefresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) goAsyncDeleted(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetIntents.ACTION_REFRESH_NOW -> goAsyncRefresh(context)
            WidgetIntents.ACTION_DISMISS_NOW_ALERT -> goAsyncDismissAlert(
                context,
                intent.getStringExtra(WidgetIntents.EXTRA_DISMISS_GATEWAY),
                intent.getStringExtra(WidgetIntents.EXTRA_DISMISS_KEY),
            )
        }
    }
}
