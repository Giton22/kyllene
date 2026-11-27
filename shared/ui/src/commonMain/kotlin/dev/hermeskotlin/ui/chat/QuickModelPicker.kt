package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledSlider
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.PickerModel
import dev.hermeskotlin.core.models.PickerScope
import dev.hermeskotlin.core.models.ReasoningEffort
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.models.displayProviderName
import dev.hermeskotlin.core.models.pickerModels
import dev.hermeskotlin.core.models.pickerSections
import dev.hermeskotlin.core.models.starKey
import dev.hermeskotlin.core.models.toggleStar
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.arrowFocus
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun QuickEffortPanel(
    state: ChatState, selection: ModelSelection, providerLabel: String?,
    onSelect: (ReasoningEffort) -> Unit,
    onFast: (Boolean) -> Unit, onOpenModels: () -> Unit,
) {
    val option = selection.option
    val efforts = ReasoningEffort.choicesFor(option)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.chat_quick_model_effort), style = Theme[typography][label],
            color = Theme[colors][textColor], modifier = Modifier.semantics { heading() },
        )
        if (efforts.size > 1) {
            key(selection.provider, selection.model) { EffortSlider(efforts, state.effort(option), onSelect) }
        } else if (option != null) {
            Text(stringResource(Res.string.chat_quick_model_unsupported), style = Theme[typography][caption], color = Theme[colors][textSecondary])
        }
        if (option?.fast == true) FastModeCard(state.fast == true, onFast)
        PickerNavigationRow(
            text = selection.model?.let(::displayModelName) ?: stringResource(Res.string.chat_quick_model_title),
            detail = providerLabel,
            onClick = onOpenModels,
        )
    }
}

@Composable
private fun EffortSlider(
    choices: List<ReasoningEffort>, selected: ReasoningEffort?, onSelect: (ReasoningEffort) -> Unit,
) {
    val index = choices.indexOf(selected).takeIf { it >= 0 }
        ?: choices.indexOf(ReasoningEffort.Default).coerceAtLeast(0)
    var value by remember(index, choices) { mutableFloatStateOf(index.toFloat()) }
    val active = value.roundToInt().coerceIn(0, choices.lastIndex)
    val label = stringResource(Res.string.chat_quick_model_effort)
    val choice = effortLabel(choices[active])
    val accentColor = Theme[colors][accent]
    val railColor = Theme[colors][surface3]
    val tickColor = Theme[colors][textTertiary]
    UnstyledSlider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = {
            val picked = value.roundToInt().coerceIn(0, choices.lastIndex)
            if (picked != index) onSelect(choices[picked])
        },
        valueRange = 0f..choices.lastIndex.toFloat(),
        steps = choices.size - 2,
        modifier = Modifier.fillMaxWidth().height(if (isDesktop) 34.dp else 48.dp).handCursor().semantics {
            contentDescription = label
            stateDescription = choice
        },
        track = { slider ->
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val end = size.width * slider.fraction
                val corner = CornerRadius(size.height / 2)
                drawRoundRect(railColor, cornerRadius = corner)
                if (end > 0) {
                    drawRoundRect(accentColor, size = Size(end, size.height), cornerRadius = corner)
                }
                for (stop in choices.indices) {
                    val x = size.width * stop / choices.lastIndex
                    drawCircle(if (stop <= active) Color.White.copy(alpha = .48f) else tickColor, 1.5.dp.toPx(), Offset(x, size.height / 2))
                }
            }
        },
        thumb = { slider ->
            Box(Modifier.size(18.dp).background(Color.White, CircleShape)
                .border(if (slider.isFocused) 3.dp else 1.dp, accentColor, CircleShape))
        },
    )
    val middle = active.takeIf { it in 1 until choices.lastIndex } ?: choices.size / 2
    val labels = if (choices.size <= 4) choices.indices.toList() else listOf(0, middle, choices.lastIndex)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            labels.forEach {
                Text(effortLabel(choices[it]), style = Theme[typography][caption],
                    color = Theme[colors][if (it == active) textColor else textSecondary],
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 4.dp.roundToPx()
        val labelWidth = ((width - gap * (labels.size - 1)) / labels.size).coerceAtLeast(0)
        val measured = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = labelWidth, minHeight = 0)) }
        layout(width, measured.maxOf { it.height }) {
            var nextStart = 0
            var remaining = measured.sumOf { it.width + gap }
            measured.forEachIndexed { i, label ->
                remaining -= label.width + gap
                val centered = (width * labels[i].toFloat() / choices.lastIndex - label.width / 2f).roundToInt()
                val lastStart = (width - label.width - remaining).coerceAtLeast(nextStart)
                val x = centered.coerceIn(nextStart, lastStart)
                label.placeRelative(x, 0)
                nextStart = x + label.width + gap
            }
        }
    }
}

@Composable
internal fun ColumnScope.QuickModelList(
    picker: ModelPickerState, selection: ModelSelection, query: TextFieldState, note: String?,
    onBack: () -> Unit, onRefresh: () -> Unit, onPick: (ModelOption) -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    currentEffort: ReasoningEffort?,
) {
    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Lucide.ChevronLeft, stringResource(Res.string.common_back), onClick = onBack)
        Text(stringResource(if (isDesktop) Res.string.chat_quick_model_title else Res.string.chat_model_models),
            style = if (isDesktop) Theme[typography][label] else Theme[typography][title],
            color = Theme[colors][textColor], modifier = Modifier.semantics { heading() })
    }
    if (!isDesktop && (selection.model != null || currentEffort != null)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            selection.model?.let {
                Text(displayModelName(it), Modifier.weight(1f), style = Theme[typography][bodySmall],
                    color = Theme[colors][textColor], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            currentEffort?.let {
                Text(
                    "${stringResource(Res.string.chat_model_thinking)} · ${effortLabel(it)}",
                    style = Theme[typography][bodySmall], color = Theme[colors][textSecondary],
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Theme[colors][stroke]))
    }
    val status = if (picker.saving) stringResource(Res.string.chat_model_saving) else picker.saveError ?: note
    status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = Theme[typography][caption], color = Theme[colors][textSecondary]) }
    val catalog = picker.catalog
    if (catalog == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (picker.loading) dev.hermeskotlin.designsystem.components.Spinner()
            else {
                Text(picker.error?.ifEmpty { null } ?: stringResource(Res.string.chat_model_load_failed), color = Theme[colors][textSecondary])
                Button(stringResource(Res.string.chat_try_again), onClick = onRefresh, variant = ButtonVariant.Secondary)
            }
        }
        return
    }
    val stars = LocalAppSettings.current.starredModels
    val needle = query.text.toString().trim()
    val names = remember(catalog) { catalog.providers.associate { it.slug to displayProviderName(it.slug, it.name) } }
    val allRows = remember(catalog) { catalog.providers.associate { it.slug to catalog.pickerModels(it.slug) } }
    val repeatedNames = remember(allRows) {
        allRows.values.flatten().groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    }
    val matches = remember(needle, catalog) {
        if (needle.isEmpty()) emptyList() else catalog.pickerSections(needle, PickerScope.All, emptyList(), emptyList(), null)
            .flatMap { it.models }.flatMap { it.variants }
    }
    val favoriteRows = remember(catalog, stars, needle, matches) {
        val candidates = if (needle.isEmpty()) catalog.providers.flatMap { it.models } else matches
        val byKey = candidates.associateBy { it.starKey }
        stars.distinct().mapNotNull(byKey::get).map { PickerModel(displayModelName(it.id), listOf(it)) }
    }
    val matchingKeys = remember(matches) { matches.map { it.starKey }.toSet() }
    val searchSpansProviders = remember(matches) { matches.map { it.provider }.distinct().size > 1 }
    var expanded by remember(catalog.providers.map { it.slug }) {
        mutableStateOf(setOfNotNull(selection.provider ?: catalog.providers.firstOrNull()?.slug))
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val list = rememberLazyListState()
    if (isDesktop) LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(needle) { list.scrollToItem(0) }
    TextField(
        state = query, placeholder = stringResource(Res.string.chat_model_search), leadingIcon = Lucide.Search,
        clearable = true, focusRequester = focus,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (it.key == Key.DirectionDown) return@onPreviewKeyEvent focusManager.moveFocus(FocusDirection.Down)
            if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && matches.isNotEmpty()) {
                onPick(matches.first())
                true
            } else false
        },
    )

    val rowContent: @Composable (PickerModel, Boolean) -> Unit = { row, showProvider ->
        val option = row.variants.single()
        ModelRow(row, selection, option.starKey in stars, names.takeIf { showProvider }, showId = row.name in repeatedNames,
            onClick = { onPick(option) }, onPickVariant = onPick,
            onToggleStar = { onSettings { it.copy(starredModels = it.starredModels.toggleStar(row, option)) } }, compact = true)
    }
    ScrollbarBox(list, Modifier.fillMaxWidth().weight(1f, fill = !isDesktop)) {
        val listHeight = if (isDesktop) Modifier.heightIn(max = 410.dp) else Modifier.fillMaxHeight()
        LazyColumn(Modifier.fillMaxWidth().then(listHeight).arrowFocus(), state = list) {
            if (favoriteRows.isNotEmpty()) {
                item("favorites") { ModelPickerSectionLabel(stringResource(Res.string.chat_quick_model_favorites)) }
                items(favoriteRows, key = { "favorite-${it.variants.single().starKey}" }) { rowContent(it, true) }
            }
            item("providers") { ModelPickerSectionLabel(stringResource(Res.string.chat_quick_model_providers)) }
            for (provider in catalog.providers) {
                val models = allRows[provider.slug].orEmpty().filter {
                    needle.isEmpty() || it.variants.single().starKey in matchingKeys
                }
                if (models.isEmpty()) continue
                val open = needle.isNotEmpty() || provider.slug in expanded
                item("provider-${provider.slug}") {
                    if (needle.isNotEmpty()) {
                        ModelPickerSectionLabel(names.getValue(provider.slug))
                    } else {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
                            PickerNavigationRow(
                                names.getValue(provider.slug), expanded = open, onClick = {
                                    expanded = if (provider.slug in expanded) expanded - provider.slug else expanded + provider.slug
                                },
                            )
                        }
                    }
                }
                if (open) items(models, key = { "model-${it.variants.single().starKey}" }) {
                    rowContent(it, isDesktop || searchSpansProviders)
                }
            }
            if (if (needle.isEmpty()) allRows.values.all { it.isEmpty() } else matches.isEmpty()) {
                item("empty") {
                    val empty = if (needle.isEmpty()) stringResource(Res.string.chat_model_none)
                        else stringResource(Res.string.chat_model_no_match, needle)
                    Text(empty, Modifier.padding(16.dp), color = Theme[colors][textSecondary])
                }
            }
        }
    }
}

@Composable
private fun PickerNavigationRow(text: String, detail: String? = null, expanded: Boolean? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (isDesktop) 36.dp else 48.dp).clip(shape).background(Theme[colors][surface2])
            .border(1.dp, Theme[colors][strokeStrong], shape).handCursor()
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = Theme[typography][body], color = Theme[colors][textColor],
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][textSecondary], maxLines = 1) }
        UnstyledIcon(if (expanded == true) Lucide.ChevronDown else Lucide.ChevronRight,
            contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
    }
}
