package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import dev.hermeskotlin.designsystem.components.SidebarState
import kotlinx.coroutines.launch

/**
 * Back on a phone, where the chat list is the drawer over the chat (#560). In the chat ([chatShown]: no room, page or
 * Settings over it), Back opens the list, as the swipe right does; with gesture navigation the two swipes are easily
 * mixed up. On the open list nothing here takes Back, so the system leaves the app with its predictive animation:
 * the list is where the app starts from, and closing it again would only send Back round in a loop. Whatever is open
 * in front (a sheet, a dialog, a menu, the sidebar's search, pages and filters, the chat's own modes) is composed
 * after this and takes Back first.
 *
 * Where the sidebar is docked beside the chat, and on a desktop (whose Back is Escape), this does nothing.
 */
@Composable
internal fun ChatListBackHandler(sidebar: SidebarState, chatShown: Boolean, desktop: Boolean = isDesktop) {
    val scope = rememberCoroutineScope()
    PlatformBackHandler(enabled = !desktop && !sidebar.docked && !sidebar.isOpen && chatShown) {
        scope.launch { sidebar.open() }
    }
}
