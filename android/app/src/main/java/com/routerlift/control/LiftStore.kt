package com.routerlift.control

import android.content.SharedPreferences
import com.routerlift.control.lift.LiftUnits
import com.routerlift.control.lift.Presets
import com.routerlift.control.lift.SavedPosition
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers between runs. */
class LiftStore(private val prefs: SharedPreferences) {
    var speed: Int
        get() = prefs.getInt("speed", 45)
        set(value) = prefs.edit().putInt("speed", value).apply()

    var inches: Boolean
        get() = prefs.getBoolean("inches", true)
        set(value) = prefs.edit().putBoolean("inches", value).apply()

    val stepsPerInch: Float
        get() = prefs.getFloat("stepsPerInch", LiftUnits.DEFAULT_STEPS_PER_INCH)

    /** Last height the lift reported, so it can be given back after the lift is power-cycled. */
    var lastPosition: Long?
        get() = if (prefs.contains("positionSteps")) prefs.getLong("positionSteps", 0) else null
        set(value) = prefs.edit().apply { if (value == null) remove("positionSteps") else putLong("positionSteps", value) }.apply()

    /** Height the lift was at when zero was last set; lets that zero be undone. */
    var zeroUndo: Long?
        get() = if (prefs.contains("zeroUndo")) prefs.getLong("zeroUndo", 0) else null
        set(value) = prefs.edit().apply { if (value == null) remove("zeroUndo") else putLong("zeroUndo", value) }.apply()

    var positions: List<SavedPosition>
        get() = runCatching {
            val array = JSONArray(prefs.getString("positions", "[]"))
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                SavedPosition(o.getLong("id"), o.getString("name"), o.getLong("steps"))
            }
        }.getOrDefault(emptyList())
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("steps", it.steps)) }
            prefs.edit().putString("positions", array.toString()).apply()
        }

    fun presets(inches: Boolean): List<Double> =
        Presets.decode(prefs.getString(presetKey(inches), null))
            ?: if (inches) Presets.DEFAULT_INCHES else Presets.DEFAULT_MM

    fun setPresets(inches: Boolean, values: List<Double>) =
        prefs.edit().putString(presetKey(inches), Presets.encode(values)).apply()

    fun nudge(inches: Boolean): Double? =
        prefs.getString(nudgeKey(inches), null)?.toDoubleOrNull()

    fun setNudge(inches: Boolean, value: Double?) =
        prefs.edit().putString(nudgeKey(inches), value?.toString()).apply()

    private fun presetKey(inches: Boolean) = if (inches) "presetsInches" else "presetsMm"
    private fun nudgeKey(inches: Boolean) = if (inches) "nudgeInches" else "nudgeMm"
}
