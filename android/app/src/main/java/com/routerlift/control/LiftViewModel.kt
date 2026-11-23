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
) {
    val connected get() = link == LinkState.Connected
    val busy get() = switches.moving || targetSteps != null
}

class LiftViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("lift", Context.MODE_PRIVATE)
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(
        UiState(
            speed = prefs.getInt(KEY_SPEED, 45),
            inches = prefs.getBoolean(KEY_INCHES, true),
            stepsPerInch = prefs.getFloat(KEY_STEPS_PER_INCH, LiftUnits.DEFAULT_STEPS_PER_INCH),
        )
    )
    val state = _state.asStateFlow()

    private var session: Job? = null
    private var link: LiftLink? = null

    /** Single queue so commands reach the lift in the order they were issued (jog, then stop). */
    private var outgoing: Channel<String>? = null

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

                send(LiftCommand.WAKE)
                send(LiftCommand.REQUEST_VERSION)
                send(LiftCommand.setSpeed(_state.value.speed))
                // The lift forgets its position when powered off; the phone is the memory.
                if (prefs.contains(KEY_POSITION)) send(LiftCommand.setPosition(prefs.getLong(KEY_POSITION, 0)))
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
            is LiftEvent.Position -> {
                prefs.edit().putLong(KEY_POSITION, event.steps).apply()
                _state.update {
                    val arrived = it.targetSteps != null && abs(event.steps - it.targetSteps) <= ARRIVAL_TOLERANCE_STEPS
                    it.copy(positionSteps = event.steps, targetSteps = if (arrived) null else it.targetSteps)
                }
            }
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

    private fun send(command: String) {
        if (outgoing?.trySend(command)?.isSuccess == true) Log.d(TAG, "-> $command")
    }

    // The lift does not report app-commanded motion, so the running flags are set locally,
    // as the vendor app does. Limit switches and foot-pedal moves come from the lift itself.

    fun jogStart(up: Boolean) {
        if (!_state.value.connected) return
        send(LiftCommand.jog(up, _state.value.speed))
        _state.update { it.copy(switches = SwitchState(runningUp = up, runningDown = !up), targetSteps = null) }
    }

    fun runToLimit(up: Boolean) {
        if (!_state.value.connected) return
        send(LiftCommand.runToLimit(up, _state.value.speed))
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
        if (!s.connected) return
        val steps = LiftUnits.toSteps(value, s.inches, s.stepsPerInch)
        send(LiftCommand.travelTo(steps, s.speed))
        _state.update { it.copy(targetSteps = steps) }
    }

    /** [value] is in the currently displayed unit; positive is up. */
    fun moveBy(value: Double) {
        val s = _state.value
        if (!s.connected) return
        val steps = LiftUnits.toSteps(value, s.inches, s.stepsPerInch)
        if (steps == 0L) return
        send(LiftCommand.setSpeed(s.speed))
        send(LiftCommand.moveBy(steps))
        _state.update { it.copy(targetSteps = s.positionSteps?.plus(steps)) }
    }

    fun zero() {
        if (!_state.value.connected) return
        send(LiftCommand.ZERO)
        send(LiftCommand.REQUEST_POSITION)
    }

    fun previewSpeed(percent: Int) = _state.update { it.copy(speed = percent) }

    fun commitSpeed() {
        val speed = _state.value.speed
        prefs.edit().putInt(KEY_SPEED, speed).apply()
        send(LiftCommand.setSpeed(speed))
    }

    fun setInches(inches: Boolean) {
        prefs.edit().putBoolean(KEY_INCHES, inches).apply()
        _state.update { it.copy(inches = inches) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        link?.close()
    }

    private companion object {
        const val TAG = "RouterLift"
        const val KEY_SPEED = "speed"
        const val KEY_INCHES = "inches"
        const val KEY_STEPS_PER_INCH = "stepsPerInch"
        const val KEY_POSITION = "positionSteps"
        const val ARRIVAL_TOLERANCE_STEPS = 20
    }
}
