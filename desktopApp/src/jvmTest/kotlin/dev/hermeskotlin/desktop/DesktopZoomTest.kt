package dev.hermeskotlin.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.ui.App
import dev.hermeskotlin.ui.TitleBar
import dev.hermeskotlin.ui.di.sharedModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.math.abs
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DesktopZoomTest {
    @Test
    fun zoomResizesContentWithEitherTitleBar() = runDesktopComposeUiTest(width = 1000, height = 700) {
        var titleBar by mutableStateOf<TitleBar?>(null)
        val owner = object : ViewModelStoreOwner, LifecycleOwner {
            override val viewModelStore = ViewModelStore()
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val store = InMemoryKeyValueStore()
        val settings = SettingsStore(store, scope)
        settings.update { it.copy(language = "en") }
        val testStorage = module {
            single<KeyValueStore> { store }
            single { OfflineCache(InMemoryOfflineDao(), PlainSealer) { 0L } }
            single { scope }
            single { settings }
        }
        val koin = koinApplication { modules(sharedModules + desktopModule + testStorage) }
        try {
            setContent {
                KoinIsolatedContext(koin) {
                    CompositionLocalProvider(
                        LocalLifecycleOwner provides owner,
                        LocalViewModelStoreOwner provides owner,
                    ) {
                        App(locked = true, titleBar = titleBar)
                    }
                }
            }
            fun width() = onNodeWithText("Unlock").fetchSemanticsNode().boundsInRoot.width
            for (decoration in listOf(null, TitleBar(0.dp, 100.dp, 40.dp, Modifier))) {
                runOnIdle { titleBar = decoration }
                val original = width()
                for ((zoom, scale) in listOf(110 to 1.1f, 90 to 0.9f, 100 to 1f)) {
                    runOnIdle { settings.update { it.copy(zoom = zoom) } }
                    waitUntil(conditionDescription = "Unlock button resized at $zoom%", timeoutMillis = 3_000) {
                        abs(width() - original * scale) <= 3f
                    }
                }
            }
        } finally {
            runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                owner.viewModelStore.clear()
            }
            scope.cancel()
            koin.close()
        }
    }
}
