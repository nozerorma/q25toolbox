package com.kgr.q25toolbox.modules

import com.kgr.q25toolbox.modules.GestureSettings.Action
import com.kgr.q25toolbox.modules.GestureSettings.Zone
import com.kgr.q25toolbox.service.EdgeSwipe.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureSettingsTest {

    @Test fun defaultsForSideZones() {
        for (z in listOf(Zone.LEFT, Zone.RIGHT)) {
            assertEquals(Action.BACK, GestureSettings.defaultBinding(z, Dir.STRAIGHT, hold = false))
            assertEquals(Action.PREVIOUS_APP, GestureSettings.defaultBinding(z, Dir.STRAIGHT, hold = true))
            assertEquals(Action.NOTIFICATIONS, GestureSettings.defaultBinding(z, Dir.DIAG_B, hold = false))
            assertEquals(Action.QUICK_SETTINGS, GestureSettings.defaultBinding(z, Dir.DIAG_A, hold = false))
            assertEquals(Action.NONE, GestureSettings.defaultBinding(z, Dir.DIAG_A, hold = true))
        }
    }

    @Test fun defaultsForTheBottomZone() {
        assertEquals(Action.HOME, GestureSettings.defaultBinding(Zone.BOTTOM, Dir.STRAIGHT, hold = false))
        assertEquals(Action.RECENTS, GestureSettings.defaultBinding(Zone.BOTTOM, Dir.STRAIGHT, hold = true))
    }

    @Test fun ownKeyWinsOverTheLegacySideKey() {
        val saved = mapOf("gest_left_mode" to "CUSTOM", "gest_lat_mode" to "OFF")
        assertEquals("CUSTOM", GestureSettings.pick(Zone.LEFT, "mode", saved::get, "OFF"))
    }

    @Test fun legacySideKeyIsUsedByBothSidesUntilTheyAreSaved() {
        val saved = mapOf("gest_lat_thickness_dp" to 11)
        assertEquals(11, GestureSettings.pick(Zone.LEFT, "thickness_dp", saved::get, 14))
        assertEquals(11, GestureSettings.pick(Zone.RIGHT, "thickness_dp", saved::get, 14))
    }

    @Test fun bottomNeverReadsTheLegacySideKey() {
        val saved = mapOf("gest_lat_thickness_dp" to 11)
        assertEquals(12, GestureSettings.pick(Zone.BOTTOM, "thickness_dp", saved::get, 12))
    }

    @Test fun paletteReferencesParseOnlyKnownFamiliesAndTones() {
        assertEquals("accent1" to 500, GestureSettings.parseMyRef("accent1:500"))
        assertEquals("neutral2" to 0, GestureSettings.parseMyRef(GestureSettings.myRef("neutral2", 0)))
        listOf(null, "", "accent1", "accent1:", "accent9:500", "accent1:555", "accent1:500:1", "x").forEach {
            assertEquals(null, GestureSettings.parseMyRef(it))
        }
    }

    @Test fun everyPaletteReferenceKeyIsBackedUp() {
        val keys = GestureSettings.allKeys().toSet()
        GestureSettings.COLOR_SLOTS.forEach { assertTrue("gest_arrow_${it}_ref" in keys) }
    }

    @Test fun missingEverywhereGivesTheDefault() {
        assertEquals(28, GestureSettings.pick(Zone.RIGHT, "distance_dp", { _: String -> null as Int? }, 28))
    }

    @Test fun backupKeysCoverNewAndLegacySideKeys() {
        val keys = GestureSettings.allKeys().toSet()
        assertTrue("gest_left_mode" in keys && "gest_right_act_straight_hold" in keys)
        assertTrue("gest_lat_mode" in keys && "gest_lat_act_diag_a_swipe" in keys) // old backups still restore
        assertTrue(GestureSettings.KEY_NATIVE_BOTTOM_OFF in keys)
    }

    @Test fun hookStateFileIsReadFromTheLauncherFilesDir() {
        // Regression: the path once lacked "files/", so the proof file was never found.
        assertEquals("/data/user/0/com.android.launcher3/files/q25toolbox_gesture_hook.state",
            NativeBottomGesture.statePath("com.android.launcher3"))
    }

    @Test fun parseActionAcceptsOurNamesCaseInsensitively() {
        assertEquals(Action.PREVIOUS_APP, GestureSettings.parseAction("previous_app"))
        assertEquals(Action.RECENTS, GestureSettings.parseAction(" RECENTS "))
        assertEquals(Action.CIRCLE_TO_SEARCH, GestureSettings.parseAction("circle_to_search"))
        assertEquals(Action.CIRCLE_TO_SEARCH, GestureSettings.parseAction("cts"))
        assertEquals(Action.QR_ENLARGER, GestureSettings.parseAction("qr"))
        assertEquals(Action.ASPECT_RATIO, GestureSettings.parseAction("resolution"))
        assertEquals(Action.ASPECT_RATIO, GestureSettings.parseAction("res"))
        assertEquals(Action.ASPECT_RATIO, GestureSettings.parseAction("aspect_ratio"))
        assertEquals(Action.ROTATION, GestureSettings.parseAction("rotate"))
        assertEquals(Action.ROTATION, GestureSettings.parseAction("rotation"))
        assertEquals(Action.EXTRA_DIM, GestureSettings.parseAction("dim"))
        assertEquals(Action.EXTRA_DIM, GestureSettings.parseAction("extra_dim"))
        assertEquals(Action.GESTURES, GestureSettings.parseAction("edge_gestures"))
        assertEquals(Action.GESTURES, GestureSettings.parseAction("gestures"))
        assertEquals(Action.BLUETOOTH, GestureSettings.parseAction("bt"))
        assertEquals(Action.BLUETOOTH, GestureSettings.parseAction("bluetooth"))
        assertEquals(Action.LOCATION, GestureSettings.parseAction("gps"))
        assertEquals(Action.LOCATION, GestureSettings.parseAction("location"))
        assertEquals(null, GestureSettings.parseAction("reboot"))
        assertEquals(null, GestureSettings.parseAction(null))
    }
}
