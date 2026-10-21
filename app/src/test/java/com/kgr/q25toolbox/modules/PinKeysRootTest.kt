package com.kgr.q25toolbox.modules

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinKeysRootTest {
    private val raw = File("src/main/assets/pin_keys_template.sh").readText()

    @Test fun placeholdersAreAllReplaced() {
        val out = PinKeysRoot.render(raw, pin = true, enterOpens = false)
        assertFalse(out.contains("__"))
        assertTrue(out.contains("PIN=1 "))
        assertTrue(out.contains("ENTER_OPENS=0 "))
    }

    @Test fun neverLogsDigits() {
        // the only log() calls must not interpolate the key or its code
        raw.lines().filter { it.trim().startsWith("log ") }.forEach {
            assertFalse(it, it.contains("\$act") || it.contains("\$c"))
        }
    }

    @Test fun daemonReadsTheSamePrefKeysAsTheApp() {
        // the script follows the toggles by reading these exact names; a rename must not silently break it
        assertTrue(raw.contains("pref_flag ${PinKeysRoot.KEY_PIN_INPUT} "))
        assertTrue(raw.contains("pref_flag ${PinKeysRoot.KEY_ENTER_OPENS} "))
        assertTrue(raw.contains("shared_prefs/q25tweaks.xml"))
    }

    @Test fun daemonEndsAndRemovesItselfWhenBothTogglesAreOff() {
        val fn = raw.substringAfter("shutdown_if_unwanted() {").substringBefore("\n}")
        assertTrue(fn.contains("rm -f \"\$0\""))     // no restart at the next boot
        assertTrue(fn.contains("pgrep -P \$\$"))      // subshell and getevent go too
        assertTrue(fn.contains("kill \$\$"))
    }
}
