package com.wakeelectronics.tintatap.data

import android.content.Context
import android.util.Base64
import com.wakeelectronics.tintatap.model.Action
import com.wakeelectronics.tintatap.model.ActionType
import com.wakeelectronics.tintatap.model.DefaultActions
import org.json.JSONArray
import org.json.JSONObject

/** Persists the home action list: user order, last-used, and user-created presets. */
class ActionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("TintaActions", Context.MODE_PRIVATE)

    /** Built-ins + presets, in the user's saved order (unseen items appended in default order). */
    fun actions(): List<Action> {
        val all = DefaultActions.list + loadPresets()
        val byId = all.associateBy { it.id }
        val order = loadOrder()
        val ordered = order.mapNotNull { byId[it] }
        val extra = all.filter { it.id !in order }
        return ordered + extra
    }

    fun saveOrder(ids: List<String>) {
        prefs.edit().putString(KEY_ORDER, ids.joinToString(",")).apply()
    }

    var lastUsedId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    var bookingEmail: String
        get() = prefs.getString("booking_email", "").orEmpty()
        set(value) { prefs.edit().putString("booking_email", value).apply() }

    var bookingDurationMin: Int
        get() = prefs.getInt("booking_duration", 60)
        set(value) { prefs.edit().putInt("booking_duration", value).apply() }

    var messageText: String
        get() = prefs.getString("message_text", "").orEmpty()
        set(value) { prefs.edit().putString("message_text", value).apply() }

    var sketchBytes: ByteArray?
        get() {
            val s = prefs.getString("sketch_b64", null) ?: return null
            return try { Base64.decode(s, Base64.DEFAULT) } catch (e: Exception) { null }
        }
        set(value) {
            prefs.edit().putString("sketch_b64", value?.let { Base64.encodeToString(it, Base64.DEFAULT) }).apply()
        }

    fun addPreset(action: Action) {
        val arr = JSONArray()
        (loadPresets() + action).forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("subtitle", p.subtitle)
                    .put("type", p.type.name).put("text", p.presetText)
            )
        }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    private fun loadOrder(): List<String> =
        prefs.getString(KEY_ORDER, "").orEmpty().split(",").filter { it.isNotEmpty() }

    private fun loadPresets(): List<Action> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Action(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    subtitle = o.getString("subtitle"),
                    type = ActionType.valueOf(o.getString("type")),
                    builtIn = false,
                    presetText = o.optString("text", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_LAST = "last"
        const val KEY_PRESETS = "presets"
    }
}
