package dev.hermeskotlin.ui.gateways

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import org.koin.compose.koinInject

/** Reads the saved display label without changing the gateway bound to the screen. */
@Composable
internal fun SavedGateway.savedLabel(): String {
    val gateways = koinInject<GatewayRepository>().list.collectAsStateWithLifecycle().value
    return gateways.find(url)?.label ?: label
}
