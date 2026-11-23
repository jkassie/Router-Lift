package com.routerlift.control.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.routerlift.control.LiftViewModel
import com.routerlift.control.LinkState
import com.routerlift.control.UiState
import com.routerlift.control.lift.LiftUnits

private enum class Dialog { GoTo, MoveBy, Zero }

@Composable
fun LiftScreen(state: UiState, actions: LiftViewModel, onConnect: () -> Unit) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val unit = if (state.inches) "in" else "mm"
    val canUp = state.connected && !state.switches.upperLimit
    val canDown = state.connected && !state.switches.lowerLimit

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionRow(state, onConnect, actions::disconnect)

            state.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = actions::dismissMessage) { Text("Dismiss") }
                    }
                }
            }

            // Position readout
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        state.positionSteps?.let { LiftUnits.format(it, state.inches, state.stepsPerInch) } ?: "–.–––",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(" $unit", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
                Text(statusText(state), style = MaterialTheme.typography.titleMedium)
            }

            SingleChoiceSegmentedButtonRow(Modifier.align(Alignment.CenterHorizontally)) {
                SegmentedButton(state.inches, { actions.setInches(true) }, SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("Inches")
                }
                SegmentedButton(!state.inches, { actions.setInches(false) }, SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Millimetres")
                }
            }

            // Hold-to-run jog buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldButton("▼  Hold: down", canDown, { actions.jogStart(false) }, actions::stop, Modifier.weight(1f))
                HoldButton("▲  Hold: up", canUp, { actions.jogStart(true) }, actions::stop, Modifier.weight(1f))
            }

            Button(
                onClick = actions::stop,
                enabled = state.connected,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold) }

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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton({ dialog = Dialog.GoTo }, Modifier.weight(1f), enabled = state.connected && !state.busy) {
                    Text("Go to…")
                }
                FilledTonalButton({ dialog = Dialog.MoveBy }, Modifier.weight(1f), enabled = state.connected && !state.busy) {
                    Text("Move by…")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ actions.runToLimit(false) }, Modifier.weight(1f), enabled = canDown && !state.busy) {
                    Text("Max down")
                }
                OutlinedButton({ actions.runToLimit(true) }, Modifier.weight(1f), enabled = canUp && !state.busy) {
                    Text("Max up")
                }
            }
            OutlinedButton({ dialog = Dialog.Zero }, Modifier.fillMaxWidth(), enabled = state.connected && !state.busy) {
                Text("Set zero here")
            }

            Spacer(Modifier.height(4.dp))
            state.firmware?.let {
                Text("Lift firmware $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (dialog) {
        Dialog.GoTo -> NumberDialog(
            title = "Go to height ($unit)",
            confirmLabels = listOf("Go" to 1.0),
            allowNegative = true,
            onDismiss = { dialog = null },
            onConfirm = { actions.goTo(it); dialog = null },
        )
        Dialog.MoveBy -> NumberDialog(
            title = "Move by ($unit)",
            confirmLabels = listOf("Down" to -1.0, "Up" to 1.0),
            allowNegative = false,
            onDismiss = { dialog = null },
            onConfirm = { actions.moveBy(it); dialog = null },
        )
        Dialog.Zero -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Set zero here?") },
            text = { Text("The current height becomes 0. The lift does not move.") },
            confirmButton = { TextButton({ actions.zero(); dialog = null }) { Text("Set zero") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
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
        state.targetSteps != null ->
            "Moving to ${LiftUnits.format(state.targetSteps, state.inches, state.stepsPerInch)}"
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

/** Each confirm button multiplies the entered number by its factor (used for Up / Down). */
@Composable
private fun NumberDialog(
    title: String,
    confirmLabels: List<Pair<String, Double>>,
    allowNegative: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && (allowNegative || it >= 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isNotEmpty() && value == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            Row {
                confirmLabels.forEach { (label, factor) ->
                    TextButton(onClick = { value?.let { onConfirm(it * factor) } }, enabled = value != null) {
                        Text(label)
                    }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
