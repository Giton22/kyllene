package dev.hermeskotlin.ui.gateways

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.gateway.GatewayList
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/** Reads the saved display label without changing the gateway bound to the screen. */
@Composable
internal fun SavedGateway.savedLabel(): String {
    val gateways = koinInject<GatewayRepository>()
    fun GatewayList.labelOf() = find(url)?.label ?: label
    // Only the label: picking another gateway or a client certificate mustn't redraw the screen that asks.
    val saved by remember(gateways, this) { gateways.list.map { it.labelOf() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(gateways.list.value.labelOf())
    return saved
}
