package com.routerlift.control

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.routerlift.control.lift.LiftCommand
import com.routerlift.control.lift.LiftEvent
import com.routerlift.control.lift.LiftLink
import com.routerlift.control.lift.LiftParser
import com.routerlift.control.lift.LiftUnits
import com.routerlift.control.lift.SavedPosition
import com.routerlift.control.lift.SwitchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class LinkState { Disconnected, Connecting, Connected }

data class UiState(
    val link: LinkState = LinkState.Disconnected,
    val positionSteps: Long? = null,
    val switches: SwitchState = SwitchState(),
    /** Destination of a go-to or move-by that is still in progress. */
    val targetSteps: Long? = null,
    val firmware: String? = null,
    val speed: Int = 45,
    val inches: Boolean = true,
    val stepsPerInch: Float = LiftUnits.DEFAULT_STEPS_PER_INCH,
    val message: String? = null,
    val positions: List<SavedPosition> = emptyList(),
    /** Nudge amounts for the current unit, and the one selected. */
    val presets: List<Double> = emptyList(),
    val nudge: Double? = null,
    /** Height the lift was at when zero was last set, while that can still be undone. */
    val zeroUndo: Long? = null,
) {
    val connected get() = link == LinkState.Connected
    val busy get() = switches.moving || targetSteps != null
    val canMove get() = connected && !busy

    fun format(steps: Long) = LiftUnits.format(steps, inches, stepsPerInch)
    val unit get() = if (inches) "in" else "mm"
}

class LiftViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LiftStore(app.getSharedPreferences("lift", Context.MODE_PRIVATE))
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(
        UiState(
            speed = store.speed,
            inches = store.inches,
            stepsPerInch = store.stepsPerInch,
            positions = store.positions,
            zeroUndo = store.zeroUndo,
        ).withPresets()
    )
    val state = _state.asStateFlow()

    private var session: Job? = null
    private var link: LiftLink? = null

    /** Single queue so commands reach the lift in the order they were issued (jog, then stop). */
    private var outgoing: Channel<String>? = null

    /** True until the lift's first position report of a session has been handled. */
    private var awaitingFirstPosition = false

    private fun UiState.withPresets(): UiState {
        val list = store.presets(inches)
        val selected = store.nudge(inches)?.takeIf { it in list } ?: list.getOrNull(list.size / 2)
        return copy(presets = list, nudge = selected)
    }

    // ---- Connection ----

    fun connect() {
        if (session?.isActive == true) return
        val adapter = adapter
        if (adapter == null) {
            _state.update { it.copy(message = "This device has no Bluetooth.") }
            return
        }
        session = viewModelScope.launch {
            _state.update { it.copy(link = LinkState.Connecting, message = null) }
            val liftLink = LiftLink(adapter).also { link = it }
            val queue = Channel<String>(Channel.UNLIMITED)
            try {
                check(adapter.isEnabled) { "Bluetooth is turned off." }
                val device = checkNotNull(liftLink.findPairedLift()) {
                    "No paired device named \"${LiftLink.DEVICE_NAME}\". Pair the lift in Android's Bluetooth settings first."
                }
                liftLink.connect(device)
                outgoing = queue
                launch { for (command in queue) liftLink.send(command) }
                _state.update { it.copy(link = LinkState.Connected) }

                awaitingFirstPosition = true
                send(LiftCommand.WAKE)
                send(LiftCommand.REQUEST_VERSION)
                send(LiftCommand.setSpeed(_state.value.speed))
                send(LiftCommand.REQUEST_SWITCH_STATE)
                send(LiftCommand.REQUEST_POSITION)

                liftLink.lines().collect { line ->
                    Log.d(TAG, "<- $line")
                    LiftParser.parse(line)?.let(::handle)
                }
                error("The lift closed the connection.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth permission problem", e)
                _state.update { it.copy(message = "Bluetooth permission was not granted.") }
            } catch (e: Exception) {
                Log.e(TAG, "Connection ended", e)
                val wasConnected = _state.value.connected
                _state.update {
                    it.copy(
                        message = when {
                            // Dropping out of range is routine; the status line already says "Not connected".
                            wasConnected -> null
                            e is java.io.IOException -> "Could not reach the lift. Is it powered on and in range?"
                            else -> e.message ?: "Bluetooth connection failed."
                        }
                    )
                }
            } finally {
                outgoing = null
                queue.close()
                liftLink.close()
                link = null
                _state.update { it.copy(link = LinkState.Disconnected, switches = SwitchState(), targetSteps = null) }
            }
        }
    }

    fun disconnect() {
        // Closing the socket is what unblocks the reader; cancelling alone would hang.
        link?.close()
        session?.cancel()
    }

    private fun handle(event: LiftEvent) {
        when (event) {
            is LiftEvent.Position -> onPosition(event.steps)
            is LiftEvent.Switches -> {
                _state.update {
                    it.copy(switches = event.state, targetSteps = if (event.state.moving) it.targetSteps else null)
                }
                send(LiftCommand.REQUEST_POSITION)
            }
            is LiftEvent.MemoryReached -> send(LiftCommand.REQUEST_POSITION)
            is LiftEvent.Version -> _state.update { it.copy(firmware = event.text) }
            is LiftEvent.Error -> _state.update {
                it.copy(message = LiftParser.describeError(event.code), switches = SwitchState(), targetSteps = null)
            }
            is LiftEvent.Unknown -> Unit
        }
    }

    private fun onPosition(steps: Long) {
        if (awaitingFirstPosition) {
            awaitingFirstPosition = false
            // A lift reporting exactly 0 when the phone remembers another height has most likely been
            // power-cycled and lost its place, so give the height back. Any other reading means the lift
            // kept counting (it may have been moved with the foot pedal), so its own value wins.
            val remembered = store.lastPosition
            if (steps == 0L && remembered != null && remembered != 0L) {
                send(LiftCommand.setPosition(remembered))
                send(LiftCommand.REQUEST_POSITION)
                return
            }
        }
        store.lastPosition = steps
        _state.update {
            val arrived = it.targetSteps != null && abs(steps - it.targetSteps) <= ARRIVAL_TOLERANCE_STEPS
            it.copy(positionSteps = steps, targetSteps = if (arrived) null else it.targetSteps)
        }
    }

    private fun send(command: String) {
        if (outgoing?.trySend(command)?.isSuccess == true) Log.d(TAG, "-> $command")
    }

    // ---- Movement ----
    // The lift does not report app-commanded motion, so the running flags are set locally,
    // as the vendor app does. Limit switches and foot-pedal moves come from the lift itself.

    private fun speedFor(fast: Boolean) = if (fast) 100 else _state.value.speed

    fun jogStart(up: Boolean) {
        if (!_state.value.connected) return
        send(LiftCommand.jog(up, _state.value.speed))
        _state.update { it.copy(switches = SwitchState(runningUp = up, runningDown = !up), targetSteps = null) }
    }

    fun runToLimit(up: Boolean, fast: Boolean = false) {
        if (!_state.value.canMove) return
        send(LiftCommand.runToLimit(up, speedFor(fast)))
        _state.update { it.copy(switches = SwitchState(runningUp = up, runningDown = !up), targetSteps = null) }
    }

    fun stop() {
        if (!_state.value.connected) return
        send(LiftCommand.STOP)
        send(LiftCommand.CANCEL)
        send(LiftCommand.REQUEST_POSITION)
        _state.update {
            it.copy(switches = it.switches.copy(runningUp = false, runningDown = false), targetSteps = null)
        }
    }

    /** [value] is in the currently displayed unit. */
    fun goTo(value: Double) {
        val s = _state.value
        goToSteps(LiftUnits.toSteps(value, s.inches, s.stepsPerInch), fast = false)
    }

    fun goToSteps(steps: Long, fast: Boolean) {
        if (!_state.value.canMove) return
        send(LiftCommand.travelTo(steps, speedFor(fast)))
        _state.update { it.copy(targetSteps = steps) }
    }

    /** [value] is in the currently displayed unit; positive is up. */
    fun moveBy(value: Double) {
        val s = _state.value
        if (!s.canMove) return
        val steps = LiftUnits.toSteps(value, s.inches, s.stepsPerInch)
        if (steps == 0L) return
        // A relative move has no speed of its own; it uses whatever was set last.
        send(LiftCommand.setSpeed(s.speed))
        send(LiftCommand.moveBy(steps))
        _state.update { it.copy(targetSteps = s.positionSteps?.plus(steps)) }
    }

    fun nudge(up: Boolean) {
        val amount = _state.value.nudge ?: return
        moveBy(if (up) amount else -amount)
    }

    fun previewSpeed(percent: Int) = _state.update { it.copy(speed = percent) }

    fun commitSpeed() {
        val speed = _state.value.speed
        store.speed = speed
        send(LiftCommand.setSpeed(speed))
    }

    // ---- Zero ----

    fun zero() {
        val s = _state.value
        val before = s.positionSteps ?: return
        if (!s.canMove || before == 0L) return
        send(LiftCommand.ZERO)
        send(LiftCommand.REQUEST_POSITION)
        store.zeroUndo = before
        _state.update { it.copy(zeroUndo = before) }
    }

    /** Puts zero back where it was, wherever the lift has moved to since. */
    fun undoZero() {
        val s = _state.value
        val shift = s.zeroUndo ?: return
        val now = s.positionSteps ?: return
        if (!s.canMove) return
        send(LiftCommand.setPosition(now + shift))
        send(LiftCommand.REQUEST_POSITION)
        dismissZeroUndo()
    }

    fun dismissZeroUndo() {
        store.zeroUndo = null
        _state.update { it.copy(zeroUndo = null) }
    }

    // ---- Saved positions ----

    private fun updatePositions(change: (List<SavedPosition>) -> List<SavedPosition>) {
        val updated = change(_state.value.positions)
        store.positions = updated
        _state.update { it.copy(positions = updated) }
    }

    /** Always adds a new entry; an existing one is only changed through [setPositionSteps]. */
    fun saveCurrentPosition(name: String) {
        val steps = _state.value.positionSteps ?: return
        updatePositions { list ->
            val id = (list.maxOfOrNull { it.id } ?: 0) + 1
            list + SavedPosition(id, name.trim().ifEmpty { "Position $id" }, steps)
        }
    }

    fun renamePosition(id: Long, name: String) {
        if (name.isBlank()) return
        updatePositions { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    fun setPositionSteps(id: Long, steps: Long) =
        updatePositions { list -> list.map { if (it.id == id) it.copy(steps = steps) else it } }

    fun setPositionHeight(id: Long, value: Double) {
        val s = _state.value
        setPositionSteps(id, LiftUnits.toSteps(value, s.inches, s.stepsPerInch))
    }

    fun deletePosition(id: Long) = updatePositions { list -> list.filterNot { it.id == id } }

    // ---- Nudge presets and units ----

    fun selectNudge(value: Double) {
        store.setNudge(_state.value.inches, value)
        _state.update { it.copy(nudge = value) }
    }

    fun addPreset(value: Double) {
        val s = _state.value
        if (value <= 0) return
        store.setPresets(s.inches, (s.presets + value).distinct().sorted())
        store.setNudge(s.inches, value)
        _state.update { it.withPresets() }
    }

    fun removeSelectedPreset() {
        val s = _state.value
        val selected = s.nudge ?: return
        store.setPresets(s.inches, s.presets - selected)
        store.setNudge(s.inches, null)
        _state.update { it.withPresets() }
    }

    fun setInches(inches: Boolean) {
        store.inches = inches
        _state.update { it.copy(inches = inches).withPresets() }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        link?.close()
    }

    private companion object {
        const val TAG = "RouterLift"
        const val ARRIVAL_TOLERANCE_STEPS = 20
    }
}
