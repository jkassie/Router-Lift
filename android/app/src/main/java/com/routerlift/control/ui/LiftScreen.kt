package com.routerlift.control.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.routerlift.control.LiftViewModel
import com.routerlift.control.LinkState
import com.routerlift.control.UiState
import com.routerlift.control.lift.Presets
import com.routerlift.control.lift.SavedPosition
import kotlin.math.abs

private sealed interface Dialog {
    data object GoTo : Dialog
    data object MoveBy : Dialog
    data object Zero : Dialog
    data object SavePosition : Dialog
    data object AddPreset : Dialog
    data object RemovePreset : Dialog
    data class Rename(val position: SavedPosition) : Dialog
    data class SetHeight(val position: SavedPosition) : Dialog
    data class Overwrite(val position: SavedPosition) : Dialog
    data class Delete(val position: SavedPosition) : Dialog
}

/** A saved position counts as "here" within this many steps (about a thousandth of an inch). */
private const val AT_POSITION_STEPS = 2

@Composable
fun LiftScreen(state: UiState, actions: LiftViewModel, onConnect: () -> Unit) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val canUp = state.connected && !state.switches.upperLimit
    val canDown = state.connected && !state.switches.lowerLimit

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionRow(state, onConnect, actions::disconnect)

                state.message?.let { Banner(it, "Dismiss", actions::dismissMessage, error = true) }

                Readout(state)

                state.zeroUndo?.let { shift ->
                    Banner(
                        "Zero was moved by ${state.format(shift)} ${state.unit}.",
                        "Undo",
                        actions::undoZero,
                        actionEnabled = state.canMove,
                        onDismiss = actions::dismissZeroUndo,
                    )
                }

                SingleChoiceSegmentedButtonRow(Modifier.align(Alignment.CenterHorizontally)) {
                    SegmentedButton(state.inches, { actions.setInches(true) }, SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text("Inches")
                    }
                    SegmentedButton(!state.inches, { actions.setInches(false) }, SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text("Millimetres")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoldButton("▼  Hold: down", canDown, { actions.jogStart(false) }, actions::stop, Modifier.weight(1f))
                    HoldButton("▲  Hold: up", canUp, { actions.jogStart(true) }, actions::stop, Modifier.weight(1f))
                }

                Column {
                    Text("Speed ${state.speed}%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = state.speed.toFloat(),
                        onValueChange = { actions.previewSpeed(it.toInt()) },
                        onValueChangeFinished = actions::commitSpeed,
                        valueRange = 5f..100f,
                        enabled = !state.busy,
                    )
                }

                HorizontalDivider()
                NudgeSection(state, actions, canUp, canDown, onDialog = { dialog = it })

                HorizontalDivider()
                PositionsSection(state, actions, onDialog = { dialog = it })

                HorizontalDivider()
                SectionTitle("Other moves")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton({ dialog = Dialog.GoTo }, Modifier.weight(1f), enabled = state.canMove) {
                        Text("Go to…")
                    }
                    FilledTonalButton({ dialog = Dialog.MoveBy }, Modifier.weight(1f), enabled = state.canMove) {
                        Text("Move by…")
                    }
                }
                LimitRow("Max down", canDown && !state.busy) { fast -> actions.runToLimit(false, fast) }
                LimitRow("Max up", canUp && !state.busy) { fast -> actions.runToLimit(true, fast) }
                OutlinedButton(
                    { dialog = Dialog.Zero },
                    Modifier.fillMaxWidth(),
                    enabled = state.canMove && state.positionSteps != null && state.positionSteps != 0L,
                ) { Text("Set zero here") }

                state.firmware?.let { Text("Lift firmware $it", style = MaterialTheme.typography.bodySmall) }
            }

            // Outside the scrolling area so it is always on screen.
            Button(
                onClick = actions::stop,
                enabled = state.connected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }

    Dialogs(dialog, state, actions) { dialog = null }
}

@Composable
private fun Readout(state: UiState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                state.positionSteps?.let(state::format) ?: "–.–––",
                fontSize = 64.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(" ${state.unit}", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
        Text(statusText(state), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NudgeSection(
    state: UiState,
    actions: LiftViewModel,
    canUp: Boolean,
    canDown: Boolean,
    onDialog: (Dialog) -> Unit,
) {
    SectionTitle("Nudge by a fixed amount")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.presets.forEach { amount ->
            FilterChip(
                selected = amount == state.nudge,
                onClick = { actions.selectNudge(amount) },
                label = { Text(Presets.label(amount, state.inches)) },
            )
        }
    }
    val label = state.nudge?.let { Presets.label(it, state.inches) } ?: "–"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val enabled = state.canMove && state.nudge != null
        FilledTonalButton({ actions.nudge(false) }, Modifier.weight(1f).height(56.dp), enabled = enabled && canDown) {
            Text("▼  $label")
        }
        FilledTonalButton({ actions.nudge(true) }, Modifier.weight(1f).height(56.dp), enabled = enabled && canUp) {
            Text("▲  $label")
        }
    }
    Row {
        TextButton({ onDialog(Dialog.AddPreset) }) { Text("Add amount…") }
        TextButton({ onDialog(Dialog.RemovePreset) }, enabled = state.nudge != null) { Text("Remove selected") }
    }
}

@Composable
private fun PositionsSection(state: UiState, actions: LiftViewModel, onDialog: (Dialog) -> Unit) {
    SectionTitle("Saved positions")
    if (state.positions.isEmpty()) {
        Text(
            "None yet. Move the lift to a height you use often, then save it.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    state.positions.forEach { position ->
        PositionRow(position, state, actions, onDialog)
    }
    OutlinedButton(
        { onDialog(Dialog.SavePosition) },
        Modifier.fillMaxWidth(),
        enabled = state.positionSteps != null && !state.busy,
    ) { Text("Save current height as new position") }
}

@Composable
private fun PositionRow(position: SavedPosition, state: UiState, actions: LiftViewModel, onDialog: (Dialog) -> Unit) {
    val here = state.positionSteps?.let { abs(it - position.steps) <= AT_POSITION_STEPS } == true
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = if (here) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(position.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${state.format(position.steps)} ${state.unit}" + if (here) "  ·  here" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton({ actions.goToSteps(position.steps, fast = false) }, enabled = state.canMove && !here) {
                Text("Go")
            }
            TextButton({ actions.goToSteps(position.steps, fast = true) }, enabled = state.canMove && !here) {
                Text("⚡ Fast")
            }
            Box {
                TextButton({ menu = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem({ Text("Rename…") }, { menu = false; onDialog(Dialog.Rename(position)) })
                    DropdownMenuItem({ Text("Type a new height…") }, { menu = false; onDialog(Dialog.SetHeight(position)) })
                    DropdownMenuItem(
                        { Text("Replace with current height…") },
                        { menu = false; onDialog(Dialog.Overwrite(position)) },
                        enabled = state.positionSteps != null && !here,
                    )
                    DropdownMenuItem({ Text("Delete…") }, { menu = false; onDialog(Dialog.Delete(position)) })
                }
            }
        }
    }
}

/** Run to a limit switch at the slider speed, or at full speed. */
@Composable
private fun LimitRow(label: String, enabled: Boolean, onRun: (fast: Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton({ onRun(false) }, Modifier.weight(1f), enabled = enabled) { Text(label) }
        OutlinedButton({ onRun(true) }, Modifier.weight(1f), enabled = enabled) { Text("⚡ $label fast") }
    }
}

@Composable
private fun Dialogs(dialog: Dialog?, state: UiState, actions: LiftViewModel, close: () -> Unit) {
    val limit = Presets.maxTravel(state.inches)
    when (dialog) {
        Dialog.GoTo -> NumberDialog(
            title = "Go to height (${state.unit})",
            buttons = listOf("Go" to 1.0),
            range = -limit..limit,
            onDismiss = close,
            onConfirm = { actions.goTo(it); close() },
        )
        Dialog.MoveBy -> NumberDialog(
            title = "Move by (${state.unit})",
            buttons = listOf("Down" to -1.0, "Up" to 1.0),
            range = 0.0..limit,
            onDismiss = close,
            onConfirm = { actions.moveBy(it); close() },
        )
        Dialog.AddPreset -> NumberDialog(
            title = "New nudge amount (${state.unit})",
            buttons = listOf("Add" to 1.0),
            range = 0.0..limit,
            onDismiss = close,
            onConfirm = { actions.addPreset(it); close() },
        )
        Dialog.RemovePreset -> ConfirmDialog(
            title = "Remove ${state.nudge?.let { Presets.label(it, state.inches) }}?",
            text = "It is removed from the nudge amounts. You can add it again later.",
            confirm = "Remove",
            onDismiss = close,
            onConfirm = { actions.removeSelectedPreset(); close() },
        )
        Dialog.Zero -> {
            val here = state.positionSteps?.let(state::format) ?: "?"
            val saved = state.positions.size
            ConfirmDialog(
                title = "Set zero here?",
                text = "The current height, $here ${state.unit}, becomes 0. The lift does not move." +
                    (if (saved > 0) "\n\nYour $saved saved position${if (saved == 1) " is" else "s are"} " +
                        "measured from zero, so ${if (saved == 1) "it" else "they"} will point to different " +
                        "physical heights afterwards." else "") +
                    "\n\nYou can undo this afterwards.",
                confirm = "Set zero",
                onDismiss = close,
                onConfirm = { actions.zero(); close() },
            )
        }
        Dialog.SavePosition -> TextDialog(
            title = "Save ${state.positionSteps?.let(state::format)} ${state.unit} as",
            label = "Name",
            initial = "",
            confirm = "Save",
            allowEmpty = true,
            onDismiss = close,
            onConfirm = { actions.saveCurrentPosition(it); close() },
        )
        is Dialog.Rename -> TextDialog(
            title = "Rename position",
            label = "Name",
            initial = dialog.position.name,
            confirm = "Rename",
            allowEmpty = false,
            onDismiss = close,
            onConfirm = { actions.renamePosition(dialog.position.id, it); close() },
        )
        is Dialog.SetHeight -> NumberDialog(
            title = "Height for \"${dialog.position.name}\" (${state.unit})",
            initial = state.format(dialog.position.steps),
            buttons = listOf("Save" to 1.0),
            range = -limit..limit,
            onDismiss = close,
            onConfirm = { actions.setPositionHeight(dialog.position.id, it); close() },
        )
        is Dialog.Overwrite -> {
            val now = state.positionSteps
            ConfirmDialog(
                title = "Replace \"${dialog.position.name}\"?",
                text = "Its height changes from ${state.format(dialog.position.steps)} to " +
                    "${now?.let(state::format)} ${state.unit}.",
                confirm = "Replace",
                onDismiss = close,
                onConfirm = { now?.let { actions.setPositionSteps(dialog.position.id, it) }; close() },
            )
        }
        is Dialog.Delete -> ConfirmDialog(
            title = "Delete \"${dialog.position.name}\"?",
            text = "The saved height ${state.format(dialog.position.steps)} ${state.unit} will be removed.",
            confirm = "Delete",
            onDismiss = close,
            onConfirm = { actions.deletePosition(dialog.position.id); close() },
        )
        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Banner(
    text: String,
    action: String,
    onAction: () -> Unit,
    error: Boolean = false,
    actionEnabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (error) scheme.errorContainer else scheme.tertiaryContainer,
        contentColor = if (error) scheme.onErrorContainer else scheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            TextButton(onAction, enabled = actionEnabled) { Text(action) }
            onDismiss?.let { TextButton(it) { Text("Keep") } }
        }
    }
}

@Composable
private fun ConnectionRow(state: UiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (state.link) {
                LinkState.Connected -> "Connected"
                LinkState.Connecting -> "Connecting…"
                LinkState.Disconnected -> "Not connected"
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        when (state.link) {
            LinkState.Connected -> OutlinedButton(onDisconnect) { Text("Disconnect") }
            LinkState.Connecting -> TextButton(onDisconnect) { Text("Cancel") }
            LinkState.Disconnected -> Button(onConnect) { Text("Connect") }
        }
    }
}

private fun statusText(state: UiState): String {
    val s = state.switches
    return when {
        !state.connected -> " "
        state.targetSteps != null -> "Moving to ${state.format(state.targetSteps)}"
        s.runningUp -> "Running up"
        s.runningDown -> "Running down"
        s.upperLimit -> "Upper limit reached"
        s.lowerLimit -> "Lower limit reached"
        else -> "Ready"
    }
}

/** Calls [onPress] when touched and [onRelease] when let go, cancelled, or removed from screen. */
@Composable
private fun HoldButton(
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    DisposableEffect(pressed) {
        // Capture the value: by the time onDispose runs, `pressed` already reads false.
        val wasPressed = pressed
        if (wasPressed) press()
        onDispose { if (wasPressed) release() }
    }
    Button(
        onClick = {},
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.height(96.dp),
    ) { Text(label, fontSize = 18.sp) }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton({ onConfirm(text) }, enabled = allowEmpty || text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks for a number within [range]. Each button multiplies the entry by its factor, which is how
 * "Move by" offers Up and Down without needing a minus sign.
 */
@Composable
private fun NumberDialog(
    title: String,
    buttons: List<Pair<String, Double>>,
    range: ClosedFloatingPointRange<Double>,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
    initial: String = "",
) {
    var text by remember { mutableStateOf(initial) }
    val number = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val value = number?.takeIf { it in range }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isNotEmpty() && value == null,
                supportingText = {
                    if (number != null && value == null) {
                        Text("Must be between ${trim(range.start)} and ${trim(range.endInclusive)}")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            Row {
                buttons.forEach { (label, factor) ->
                    // Zero only makes sense as a destination, i.e. when the range allows negatives.
                    val usable = value != null && (value != 0.0 || range.start < 0)
                    TextButton(onClick = { value?.let { onConfirm(it * factor) } }, enabled = usable) {
                        Text(label)
                    }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
