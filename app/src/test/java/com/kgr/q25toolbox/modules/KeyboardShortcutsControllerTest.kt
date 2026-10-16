package com.kgr.q25toolbox.modules

import android.view.KeyEvent
import com.kgr.q25toolbox.modules.KeyboardShortcutsController.ClickType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutsControllerTest {

    @Test
    fun supportedActionsListContainsAllRequiredActions() {
        val actions = KeyboardShortcutsController.SUPPORTED_ACTIONS
        assertTrue(actions.contains(GestureSettings.Action.RECENTS))
        assertTrue(actions.contains(GestureSettings.Action.QR_ENLARGER))
        assertTrue(actions.contains(GestureSettings.Action.ASPECT_RATIO))
        assertTrue(actions.contains(GestureSettings.Action.ROTATION))
        assertTrue(actions.contains(GestureSettings.Action.EXTRA_DIM))
        assertTrue(actions.contains(GestureSettings.Action.GESTURES))
        assertTrue(actions.contains(GestureSettings.Action.CIRCLE_TO_SEARCH))
        assertFalse(actions.contains(GestureSettings.Action.BLUETOOTH))
        assertFalse(actions.contains(GestureSettings.Action.LOCATION))
        assertEquals(7, actions.size)
    }

    @Test
    fun shortcutConfigDefaults() {
        val sc = KeyboardShortcutsController.ShortcutConfig(GestureSettings.Action.RECENTS)
        assertTrue(sc.vibrate)
        assertTrue(sc.tapPass)
        assertFalse(sc.enabled)
        assertEquals(0, sc.keyCode)
        assertEquals(0, sc.modifiers)
        assertEquals(ClickType.SHORT, sc.clickType)
    }

    @Test
    fun clickTypeParsing() {
        assertEquals(ClickType.SHORT, ClickType.parse("SHORT"))
        assertEquals(ClickType.SHORT, ClickType.parse("short"))
        assertEquals(ClickType.LONG, ClickType.parse("LONG"))
        assertEquals(ClickType.LONG, ClickType.parse("long"))
        assertEquals(ClickType.DOUBLE, ClickType.parse("DOUBLE"))
        assertEquals(ClickType.DOUBLE, ClickType.parse("double"))
        assertEquals(ClickType.SHORT, ClickType.parse("unknown"))
        assertEquals(ClickType.SHORT, ClickType.parse(null))
    }

    @Test
    fun modifierKeyDetection() {
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_CTRL_LEFT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_ALT_RIGHT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_META_LEFT))
        assertTrue(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_META_RIGHT))

        assertFalse(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_Q))
        assertFalse(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_PROG_RED))
        assertFalse(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(KeyboardShortcutsController.isModifierKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun recordingLifecycle() {
        assertFalse(KeyboardShortcutsController.isRecording)
        var recordedKey = 0
        var recordedMods = 0

        KeyboardShortcutsController.startRecording { kc, mods ->
            recordedKey = kc
            recordedMods = mods
        }
        assertTrue(KeyboardShortcutsController.isRecording)

        KeyboardShortcutsController.stopRecording()
        assertFalse(KeyboardShortcutsController.isRecording)
    }
}
