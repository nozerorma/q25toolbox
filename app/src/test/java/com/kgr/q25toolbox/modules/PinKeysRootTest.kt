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
}
