package dev.hermeskotlin.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.MonitorOff
import com.composables.icons.lucide.MonitorPlay
import com.composables.icons.lucide.MonitorX
import com.composables.icons.lucide.MousePointerClick
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.TriangleAlert
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.screen.ScreenKey
import dev.hermeskotlin.core.screen.keysymForKey
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.well
import dev.hermeskotlin.ui.KeepScreenOn
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.sessions.ListSpinner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A bot's screen: the live picture of what the bot is doing, and the user's own hand on it.
 *
 * The gateway decides what reaches the screen, so the app sends what the user does and lets the gateway
 * drop it while the agent holds the screen; the pane says who holds it rather than holding the user's hand
 * back.
 */
@Composable
fun ScreenPane(
    bot: Bot,
    gateway: SavedGateway,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScreenViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(gateway, bot.name) { viewModel.bind(gateway, bot.name) }
    // The stream goes away with the pane, so the gateway isn't left sending pictures nobody watches.
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    // The screen going dark stops the app, so the phone keeps its own awake while the bot's is watched.
    KeepScreenOn(true)
    ScreenView(
        state = state,
        title = bot.label,
        onBack = onBack,
        onStart = viewModel::start,
        onReopen = viewModel::reopen,
        onTakeOver = viewModel::takeOver,
        onHandBack = viewModel::handBack,
        onPointer = viewModel::pointer,
        onKey = viewModel::key,
        onCharacter = viewModel::character,
        modifier = modifier,
    )
}

/**
 * The screen, drawn: who holds it, the picture or the reason there is none, and the keys.
 *
 * The picture is the bot's own desktop, so it is drawn as it is and scaled to fit; the user's taps and keys
 * go to the bot's screen whatever this shows.
 */
@Composable
internal fun ScreenView(
    state: ScreenUiState,
    title: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onReopen: () -> Unit,
    onTakeOver: () -> Unit,
    onHandBack: () -> Unit,
    onPointer: (x: Int, y: Int, mask: Int) -> Unit,
    onKey: (keysym: Int) -> Unit,
    onCharacter: (ch: Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = screenStage(state)
    Column(
        modifier
            .fillMaxSize()
            // The keys that have no character are the pane's own, wherever the focus is.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keysym = screenKey(event.key)?.let { keysymForKey(it) } ?: return@onPreviewKeyEvent false
                onKey(keysym)
                true
            },
    ) {
        SubpageHeader(title, onBack)
        // Who holds the screen is worth knowing even when there is nothing to watch yet.
        if (state.supported && state.status != null) {
            ScreenControl(state, onTakeOver, onHandBack)
        }
        state.error?.let { ScreenError(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (stage) {
                ScreenStage.Unsupported -> EmptyState(
                    Lucide.MonitorX,
                    "This gateway has no Bot Screen",
                    "The gateway is too old to have one. Update Hermes on the gateway host and it will show up here.",
                )
                ScreenStage.Looking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ListSpinner() }
                ScreenStage.NotInstalled -> ScreenNotInstalled(state, onStart)
                ScreenStage.Blocked -> EmptyState(
                    Lucide.TriangleAlert,
                    "The screen can't start",
                    state.status?.blocker ?: "The gateway would not say why.",
                    error = true,
                )
                ScreenStage.Stopped -> EmptyState(
                    Lucide.MonitorOff,
                    "The screen is stopped",
                    "Start it to watch what the bot is doing.",
                    action = { Button("Start the screen", onClick = onStart, leadingIcon = Lucide.MonitorPlay) },
                )
                ScreenStage.Dropped -> EmptyState(
                    Lucide.MonitorOff,
                    "The screen stream dropped",
                    "The gateway is still running the screen. Open it again to watch it.",
                    action = { Button("Open it again", onClick = onReopen, leadingIcon = Lucide.MonitorPlay) },
                )
                ScreenStage.Watching -> ScreenPicture(state, onPointer)
            }
        }
        // The keys are worth having only when there is a screen to type into.
        if (stage == ScreenStage.Watching) {
            ScreenKeys(onKey = onKey, onCharacter = onCharacter)
        }
    }
}

/** Who holds the screen, and the button that changes it. */
@Composable
private fun ScreenControl(state: ScreenUiState, onTakeOver: () -> Unit, onHandBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                controlLabel(state.lease, state.holding),
                style = Theme[typography][body],
                color = Theme[colors][text],
            )
            Text(
                if (state.holding) {
                    "Your taps and keys go to the bot's screen."
                } else {
                    "Your taps and keys are ignored until you take over."
                },
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
            )
        }
        if (state.holding) {
            Button("Hand back", onClick = onHandBack, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = Lucide.RotateCcw)
        } else {
            Button("Take over", onClick = onTakeOver, size = ButtonSize.Small, leadingIcon = Lucide.MousePointerClick)
        }
    }
}

/** What went wrong, in the gateway's own words. */
@Composable
private fun ScreenError(message: String) {
    Row(
        Modifier.fillMaxWidth().background(Theme[colors][surface]).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = Theme[typography][bodySmall], color = Theme[colors][danger])
    }
}

/** The screen isn't installed on the gateway host: the command to run there, and the way to copy it. */
@Composable
private fun ScreenNotInstalled(state: ScreenUiState, onStart: () -> Unit) {
    val status = state.status
    val command = status?.installCommand
    val missing = status?.missing.orEmpty()
    EmptyState(
        Lucide.Monitor,
        "The Bot Screen isn't installed",
        command ?: missing.joinToString("\n").ifEmpty { "Install the Bot Screen on the gateway host." },
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (command != null) CopyCommand(command)
                Button("Start the screen", onClick = onStart, leadingIcon = Lucide.MonitorPlay)
            }
        },
    )
}

/** The command to run on the gateway host, and a button that copies it. */
@Composable
private fun CopyCommand(command: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Button(
        if (copied) "Copied" else "Copy the command",
        onClick = {
            scope.launch {
                clipboard.setClipEntry(plainTextClipEntry(command))
                copied = true
            }
        },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = Lucide.Copy,
    )
}

/**
 * The bot's screen, drawn to fit the space it has and centred in it.
 *
 * The picture is scaled to fit, so the space around it is the letterbox: a tap outside the picture is
 * nowhere on the bot's screen and is dropped.
 */
@Composable
private fun ScreenPicture(state: ScreenUiState, onPointer: (x: Int, y: Int, mask: Int) -> Unit) {
    val frame = state.frame
    val image = rememberScreenImage(frame)
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(frame?.width, frame?.height, box) {
        fitFrame(frame?.width ?: 0, frame?.height ?: 0, box.width, box.height)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][well])
            .onSizeChanged { box = it }
            .pointerInput(fit, frame?.width, frame?.height) {
                if (fit == null || frame == null) return@pointerInput
                awaitPointerEventScope {
                    var pressed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val point = screenPoint(change.position.x, change.position.y, fit, frame.width, frame.height) ?: continue
                        when (event.type) {
                            // A press and a release make the click; the drag between them is the motion.
                            PointerEventType.Press -> {
                                pressed = true
                                onPointer(point.first, point.second, 1)
                            }
                            PointerEventType.Move -> onPointer(point.first, point.second, if (pressed) 1 else 0)
                            PointerEventType.Release -> {
                                onPointer(point.first, point.second, 0)
                                pressed = false
                            }
                            else -> Unit
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            ListSpinner()
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val fit = fit ?: return@Canvas
                drawImage(
                    image = image,
                    dstOffset = IntOffset(fit.offsetX, fit.offsetY),
                    dstSize = IntSize(fit.width, fit.height),
                )
            }
        }
    }
}

/**
 * The keys, as one field.
 *
 * The phone's software keyboard and the desktop's own keys both type into it, so a character is a character
 * on the bot's screen; the keys with no character are the pane's own.
 */
@Composable
private fun ScreenKeys(onKey: (keysym: Int) -> Unit, onCharacter: (ch: Char) -> Unit) {
    val state = remember { TextFieldState() }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            if (typed.isNotEmpty()) {
                typed.forEach { onCharacter(it) }
                // The field is a key, not a message: what was typed is gone once it is sent.
                state.clearText()
            }
        }
    }
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = "Type to send keys to the screen",
        // On the desktop the field takes the focus as it shows, so the keys work straight away.
        autofocus = true,
    )
}

/** What the pane shows for the screen's state. */
internal enum class ScreenStage {
    /** The gateway has no Bot Screen at all. */
    Unsupported,

    /** The gateway hasn't answered yet. */
    Looking,

    /** The screen isn't installed on the gateway host. */
    NotInstalled,

    /** The gateway says the screen can't run. */
    Blocked,

    /** The screen is stopped. */
    Stopped,

    /** The screen runs, but the stream to it isn't open. */
    Dropped,

    /** The screen is being watched. */
    Watching,
}

/** What the pane shows, given what the gateway last said. */
internal fun screenStage(state: ScreenUiState): ScreenStage = when {
    !state.supported -> ScreenStage.Unsupported
    state.status == null -> ScreenStage.Looking
    !state.status.installed -> ScreenStage.NotInstalled
    state.status.blocker != null -> ScreenStage.Blocked
    !state.status.running -> ScreenStage.Stopped
    !state.connected -> ScreenStage.Dropped
    else -> ScreenStage.Watching
}

/** Who holds the screen, in the user's words. */
internal fun controlLabel(lease: dev.hermeskotlin.core.screen.DisplayLease?, holding: Boolean): String = when {
    holding -> "You are in control"
    lease?.heldByHuman == true -> "Someone else is in control"
    else -> "The bot is in control"
}

/** Where the picture sits in the space it is drawn in: the size it is drawn at and where it starts. */
internal data class ScreenFit(
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
)

/**
 * How the picture is drawn in a box [boxWidth] by [boxHeight]: scaled to fit, centred, and never bigger
 * than the box. Null when there is nothing to draw yet.
 */
internal fun fitFrame(frameWidth: Int, frameHeight: Int, boxWidth: Int, boxHeight: Int): ScreenFit? {
    if (frameWidth <= 0 || frameHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return null
    val scale = min(boxWidth.toFloat() / frameWidth, boxHeight.toFloat() / frameHeight)
    val width = (frameWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (frameHeight * scale).roundToInt().coerceAtLeast(1)
    return ScreenFit(
        offsetX = (boxWidth - width) / 2,
        offsetY = (boxHeight - height) / 2,
        width = width,
        height = height,
    )
}

/**
 * The point on the bot's screen that a tap at [x], [y] in the picture's box means, or null when the tap is
 * in the letterbox and so nowhere on the screen.
 */
internal fun screenPoint(x: Float, y: Float, fit: ScreenFit, frameWidth: Int, frameHeight: Int): Pair<Int, Int>? {
    val sx = (x - fit.offsetX) * frameWidth / fit.width
    val sy = (y - fit.offsetY) * frameHeight / fit.height
    if (sx < 0 || sy < 0) return null
    val px = sx.toInt()
    val py = sy.toInt()
    if (px >= frameWidth || py >= frameHeight) return null
    return px to py
}

/** The named key a key event means, or null for a key that is only a character. */
internal fun screenKey(key: Key): ScreenKey? = when (key) {
    Key.Enter, Key.NumPadEnter -> ScreenKey.Enter
    Key.Backspace -> ScreenKey.Backspace
    Key.Tab -> ScreenKey.Tab
    Key.Escape -> ScreenKey.Escape
    Key.DirectionLeft -> ScreenKey.Left
    Key.DirectionRight -> ScreenKey.Right
    Key.DirectionUp -> ScreenKey.Up
    Key.DirectionDown -> ScreenKey.Down
    Key.MoveHome -> ScreenKey.Home
    Key.MoveEnd -> ScreenKey.End
    Key.PageUp -> ScreenKey.PageUp
    Key.PageDown -> ScreenKey.PageDown
    Key.Delete -> ScreenKey.Delete
    Key.Insert -> ScreenKey.Insert
    Key.F1 -> ScreenKey.F1
    Key.F2 -> ScreenKey.F2
    Key.F3 -> ScreenKey.F3
    Key.F4 -> ScreenKey.F4
    Key.F5 -> ScreenKey.F5
    Key.F6 -> ScreenKey.F6
    Key.F7 -> ScreenKey.F7
    Key.F8 -> ScreenKey.F8
    Key.F9 -> ScreenKey.F9
    Key.F10 -> ScreenKey.F10
    Key.F11 -> ScreenKey.F11
    Key.F12 -> ScreenKey.F12
    else -> null
}
