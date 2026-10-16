package com.kgr.q25toolbox.modules

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRemapScriptTest {
    private val src = KeyRemapController.SourceKey.GRAVE

    @Test fun lineageScriptNeverTouchesDriverBinding() {
        val s = KeyRemapController.generateBootScript(src, rebind = false)
        assertFalse(s.contains("/unbind")); assertFalse(s.contains("/bind"))
        assertTrue(s.contains("echo remove >")); assertTrue(s.contains("vendor_keylayout_file"))
        assertTrue(s.contains("Custom Q25 Keyboard"))  // virtual keyboard reloaded too
        System.getenv("KEYREMAP_DUMP")?.let { File(it, "lineage.sh").writeText(s); File(it, "benos.sh").writeText(KeyRemapController.generateBootScript(src, true)) }
    }

    @Test fun benosScriptStillRebinds() {
        val s = KeyRemapController.generateBootScript(src, rebind = true)
        assertTrue(s.contains("Q25_keyboard/unbind")); assertTrue(s.contains("Q25_keyboard/bind"))
        // Physical keyboard reloads via driver rebind; only the customizer's virtual
        // keyboard is uevent-reloaded (it has no driver to rebind).
        assertFalse(s.contains("[ \"\$N\" = \"Q25_keyboard\" ]"))
        assertTrue(s.contains("Custom Q25 Keyboard"))
    }

    @Test fun recentsOverlayRemapAloneAndCombined() {
        val only = KeyRemapController.generateBootScript(null, rebind = false, recentsRemap = true)
        assertTrue(only.contains("key 580 PROG_RED")); assertFalse(only.contains("CTRL_RIGHT"))
        val both = KeyRemapController.generateBootScript(src, rebind = false, recentsRemap = true)
        assertTrue(both.contains("CTRL_RIGHT")); assertTrue(both.contains("key 580 PROG_RED"))
        System.getenv("KEYREMAP_DUMP")?.let { File(it, "both.sh").writeText(both) }
    }

    @Test fun scriptOverlaysGenericLayoutAndLocksToOneInstance() {
        val s = KeyRemapController.generateBootScript(src, rebind = false)
        // The customizer's virtual keyboard lays out with Generic.kl: the same remaps
        // must be overlaid there, and the script must not run two instances at once.
        assertTrue(s.contains("/system/usr/keylayout/Generic.kl"))
        assertTrue(s.contains("/data/local/tmp/Q25_Generic.kl"))
        assertTrue(s.contains(".key_remap.lock"))
    }
}
