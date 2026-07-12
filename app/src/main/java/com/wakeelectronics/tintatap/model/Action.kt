package com.wakeelectronics.tintatap.model

import com.wakeelectronics.tintatap.nfc.TintaProtocol.Opcode

/** What a home-screen action does, in user terms. Maps 1:1 to a wire opcode. */
enum class ActionType { PAGE, DECISION, MESSAGE, SKETCH, BOOK }

/**
 * One tappable action on the home grid. Built-ins are fixed; presets are user-created
 * MESSAGE/BOOK actions with saved content. Pure Kotlin — host-testable.
 */
data class Action(
    val id: String,
    val name: String,
    val subtitle: String,
    val type: ActionType,
    val builtIn: Boolean = true,
    val presetText: String = "",
    val iconKey: String = ""
) {
    val opcode: Opcode
        get() = when (type) {
            ActionType.PAGE -> Opcode.PAGE_0
            ActionType.DECISION -> Opcode.DECISION
            ActionType.MESSAGE -> Opcode.TEXT
            ActionType.SKETCH -> Opcode.DRAW_IMAGE
            ActionType.BOOK -> Opcode.BOOK_SEAT
        }

    /** Instant actions arm immediately; the rest open a detail with inputs. */
    val isInstant: Boolean get() = type == ActionType.PAGE || type == ActionType.DECISION
}

object DefaultActions {
    val list: List<Action> = listOf(
        Action("home", "Home", "The default screen", ActionType.PAGE),
        Action("decide", "Universal decision maker", "Random pick", ActionType.DECISION),
        Action("message", "Message", "Write text to show", ActionType.MESSAGE),
        Action("sketch", "Sketch", "Draw an image", ActionType.SKETCH),
        Action("book", "Book a desk", "Book a workspace", ActionType.BOOK),
        Action("preset_back15", "Back in 15 min", "Message preset", ActionType.MESSAGE, presetText = "Back in 15 min", iconKey = "clock"),
        Action("preset_video", "In a video call", "Message preset", ActionType.MESSAGE, presetText = "In a video call", iconKey = "video")
    )
}
