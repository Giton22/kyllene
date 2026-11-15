package dev.hermeskotlin.android.widget

import android.content.BroadcastReceiver
import android.content.Context
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.widget.WidgetAlertDismissals
import dev.hermeskotlin.core.widget.WidgetDataAssembler
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * How a provider answers the system and its refresh button (docs/WIDGETS-SPEC.md D26): hand the
 * work to the app's [WidgetUpdater] and let the broadcast's `goAsync()` window cover the refresh.
 */
internal fun BroadcastReceiver.goAsyncRefresh(context: Context) {
    val updater = GlobalContext.get().get<WidgetUpdater>()
    val pending = goAsync()
    updater.scope.launch {
        try {
            updater.refreshAndRender(cold = true)
        } finally {
            pending.finish()
        }
    }
}

/** A removed instance's scope goes with it (D23); snapshots are shared, so only the last instance's removal deletes. */
internal fun BroadcastReceiver.goAsyncDeleted(context: Context, appWidgetId: Int) {
    val updater = GlobalContext.get().get<WidgetUpdater>()
    updater.onDeleted(appWidgetId)
}

/**
 * The Tasks header's switch button (D28): pin the next board, then refresh so every widget instance
 * shows it. The pin is the Kanban page's own, so the app follows the widget's choice.
 */
internal fun BroadcastReceiver.goAsyncSwitchBoard(context: Context) {
    val koin = GlobalContext.get()
    val updater = koin.get<WidgetUpdater>()
    val pending = goAsync()
    updater.scope.launch {
        try {
            val gateways = koin.get<GatewayRepository>()
            val url = gateways.all().let { it.current ?: it.primary }?.gatewayUrl
            if (url != null) {
                koin.get<WidgetDataAssembler>().switchToNextBoard(url)
                updater.refreshAndRender(cold = true)
            }
        } finally {
            pending.finish()
        }
    }
}

/**
 * A Now row's dismiss button (D27): record the dismissal, then refresh so the alert goes away. The
 * key is a failure fingerprint, so a later failed run or a task status change raises a fresh alert.
 */
internal fun BroadcastReceiver.goAsyncDismissAlert(context: Context, gateway: String?, key: String?) {
    if (gateway == null || key == null) return
    val koin = GlobalContext.get()
    val updater = koin.get<WidgetUpdater>()
    val pending = goAsync()
    updater.scope.launch {
        try {
            koin.get<WidgetAlertDismissals>().dismiss(gateway, key)
            updater.refreshAndRender(cold = true)
        } finally {
            pending.finish()
        }
    }
}
