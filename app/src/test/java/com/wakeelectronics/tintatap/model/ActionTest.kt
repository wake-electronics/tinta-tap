package com.wakeelectronics.tintatap.model

import com.wakeelectronics.tintatap.nfc.TintaProtocol.Opcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks on the action model and the built-in list. */
class ActionTest {

    @Test
    fun every_type_maps_to_its_wire_opcode() {
        assertEquals(Opcode.PAGE_0, action(ActionType.PAGE).opcode)
        assertEquals(Opcode.PAGE_2, action(ActionType.TRACKER).opcode)
        assertEquals(Opcode.TEXT, action(ActionType.MESSAGE).opcode)
        assertEquals(Opcode.DRAW_IMAGE, action(ActionType.SKETCH).opcode)
    }

    @Test
    fun only_page_and_decision_are_instant() {
        assertTrue(action(ActionType.PAGE).isInstant)
        assertTrue(action(ActionType.TRACKER).isInstant)
        assertFalse(action(ActionType.MESSAGE).isInstant)
        assertFalse(action(ActionType.SKETCH).isInstant)
    }

    @Test
    fun default_action_ids_are_unique() {
        // ActionStore.actions() does associateBy { id }; a duplicate id would silently drop an action.
        val ids = DefaultActions.list.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    private fun action(type: ActionType) =
        Action(id = "x", name = "x", subtitle = "", type = type)
}
