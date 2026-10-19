package com.kgr.q25toolbox.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Target resolution for hooks whose framework/launcher symbols moved between ROM generations.
 *
 * Every hook site keeps BOTH targets: the legacy one (what worked on LineageOS <= 23 and the
 * older launchers) and the LineageOS 24 one (SDK 37: `DisplayController$Info` is gone, the
 * hotseat recalc takes an int, `attachBaseContext` is declared on `ContextWrapper`). On ROMs
 * where the legacy target still exists it is tried FIRST and the new one never runs, so
 * behaviour there is identical to before; on LineageOS 24 the new target is tried first, with
 * the legacy one as fallback. First candidate that installs wins; the rest are skipped.
 *
 * Lineage detection is property-based (`ro.lineage.build.version` / `ro.lineage.version`, read
 * through SystemProperties so it works inside a hooked process without a Context). If detection
 * fails the legacy target is tried first and the serial fallback still reaches the new one.
 */
object HookTargets {

    private const val TAG = "Q25Toolbox-Xposed"

    /** One hook candidate: declaring class, method name, parameter types, callback. */
    class Candidate(
        val className: String,
        val methodName: String,
        val paramTypes: Array<Any>,
        val hook: XC_MethodHook
    )

    /** LineageOS major version (22, 23, 24, ...) from build properties, or null when not Lineage / unreadable. */
    fun lineageMajor(): Int? {
        val v = readProp("ro.lineage.build.version")?.ifEmpty { null } ?: readProp("ro.lineage.version")
        return v?.substringBefore('.')?.toIntOrNull()
    }

    /**
     * Reads a system property from inside a hooked process. Primary path is XposedHelpers; some
     * bridges fail its method lookup under hidden-API enforcement, so plain reflection is the
     * fallback. Failures are logged once per call site — never throws.
     */
    private fun readProp(key: String): String? {
        runCatching {
            val sp = XposedHelpers.findClass("android.os.SystemProperties", null)
            return XposedHelpers.callStaticMethod(sp, "get", key) as? String
        }.onFailure { t ->
            XposedBridge.log("[$TAG] SystemProperties read via XposedHelpers failed for $key: ${t.message}")
        }
        return runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java).invoke(null, key) as? String
        }.getOrNull()
    }

    /**
     * True on LineageOS 24+ (Android 17), where the moved symbols need the new targets first.
     * Last-resort fallback: on the Q25, SDK 37 only exists on LineageOS 24 (Lineage 23 is SDK 36,
     * BenOS/ZinwaOS are older) — same SDK mapping RomProfile.classify uses.
     */
    fun isLineage24(): Boolean {
        lineageMajor()?.let { return it >= 24 }
        return try {
            val version = XposedHelpers.findClass("android.os.Build\$VERSION", null)
            XposedHelpers.getStaticIntField(version, "SDK_INT") >= 37
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] isLineage24 SDK fallback failed: ${t.message}")
            false
        }
    }

    /**
     * Try [legacy] and [new] in ROM-appropriate order until one installs. Returns true when at
     * least one did. Never throws into the hooked process.
     */
    fun hookAny(cl: ClassLoader, legacy: Candidate?, new: Candidate?): Boolean {
        val order = if (isLineage24()) listOfNotNull(new, legacy) else listOfNotNull(legacy, new)
        for (c in order) {
            try {
                XposedHelpers.findAndHookMethod(c.className, cl, c.methodName, *c.paramTypes, c.hook)
                XposedBridge.log("[$TAG] hooked ${c.className}#${c.methodName}")
                return true
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] ${c.className}#${c.methodName} hook failed: ${t.message}")
            }
        }
        return false
    }
}
