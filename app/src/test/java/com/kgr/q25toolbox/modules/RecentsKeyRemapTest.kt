package com.kgr.q25toolbox.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsKeyRemapTest {
    @Test fun overlayModeStillRemapsTheKey() = assertTrue(KeyRemapController.wantsRecentsRemap(true, false, false))

    @Test fun leavingTheKeyToAnotherAppRemapsItEvenWithoutAnOverlay() =
        assertTrue(KeyRemapController.wantsRecentsRemap(false, true, false))

    @Test fun neitherMeansNoRemap() = assertFalse(KeyRemapController.wantsRecentsRemap(false, false, false))

    @Test fun ctrlOnTheRecentsKeyWinsOverBoth() {
        assertFalse(KeyRemapController.wantsRecentsRemap(true, true, true))
        assertFalse(KeyRemapController.wantsRecentsRemap(false, true, true))
    }

    @Test fun theBootScriptRemapsScancode580ToProgRedWhenAsked() {
        val on = KeyRemapController.generateBootScript(null, rebind = false, recentsRemap = true)
        val off = KeyRemapController.generateBootScript(null, rebind = false, recentsRemap = false)
        assertTrue(on.contains("key 580 PROG_RED"))
        assertFalse(off.contains("PROG_RED"))
        // Once per overlaid layout: Q25_keyboard.kl and Generic.kl (the customizer's
        // virtual keyboard lays out with the Generic fallback).
        assertEquals(2, Regex("key 580 PROG_RED").findAll(on).count())
    }
}
