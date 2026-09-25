package com.wakeelectronics.tintatap.model

import com.wakeelectronics.tintatap.nfc.TintaProtocol.Opcode

/** What a home-screen action does, in user terms. Maps 1:1 to a wire opcode. */
enum class ActionType { PAGE, TRACKER, MESSAGE, SKETCH, VALUE }

/**
 * One tappable action on the home grid. Built-ins are fixed; presets are user-created
 * MESSAGE actions with saved content. Pure Kotlin — host-testable.
 */
data class Action(
    val id: String,
    val name: String,
    val subtitle: String,
    val type: ActionType,
    val builtIn: Boolean = true,
    val presetText: String = "",
    val iconKey: String = "",
    val placeholder: Boolean = false
) {
    val opcode: Opcode
        get() = when (type) {
            ActionType.PAGE -> Opcode.PAGE_0
            ActionType.TRACKER -> Opcode.PAGE_2
            ActionType.MESSAGE -> Opcode.TEXT
            ActionType.SKETCH -> Opcode.DRAW_IMAGE
            ActionType.VALUE -> Opcode.TRACK_VALUE
        }

    /** Instant actions arm immediately; the rest open a detail with inputs. */
    val isInstant: Boolean get() = type == ActionType.PAGE || type == ActionType.TRACKER
}

object DefaultActions {
    val list: List<Action> = listOf(
        Action("calendar", "Calendar", "The day from Nextcloud", ActionType.PAGE),
        Action("tracker", "Ronja", "Weight over time", ActionType.TRACKER, iconKey = "chart"),
        Action("value", "Add a value", "Weigh in, tap, done", ActionType.VALUE, iconKey = "chart"),
        Action("message", "Message", "Write text to show", ActionType.MESSAGE),
        Action("sketch", "Sketch", "Draw an image", ActionType.SKETCH),
        Action("preset_back15", "Back in 15 min", "Message preset", ActionType.MESSAGE, presetText = "Back in 15 min", iconKey = "clock"),
        Action("preset_video", "In a video call", "Message preset", ActionType.MESSAGE, presetText = "In a video call", iconKey = "video"),
        Action("webui", "Start web interface", "Wi-Fi setup (button 3)", ActionType.PAGE, iconKey = "wifi", placeholder = true),
        Action("properties", "Show Tinta properties", "Device info to the screen", ActionType.PAGE, iconKey = "info", placeholder = true)
    )
}
