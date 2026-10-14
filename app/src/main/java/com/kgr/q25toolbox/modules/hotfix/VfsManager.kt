package com.kgr.q25toolbox.modules.hotfix

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import org.json.JSONArray

data class VfsRule(
    val virtualPath: String,
    val realPath: String,
    val uid: Int = 0
)

enum class VfsStatus {
    ACTIVE_VFS,
    FALLBACK_BIND,
    NOT_AVAILABLE
}

object VfsManager {

    private const val HM_PATH = "/data/adb/modules/hybrid_mount/hybrid-mount"

    fun isHybridMountInstalled(): Boolean {
        return RootShell.run("[ -x '$HM_PATH' ] && echo yes || echo no").outString.trim() == "yes"
    }

    /**
     * Checks whether hybrid_mount's VFS module is loaded and responding.
     * If not loaded yet, attempts 'vfs load' explicitly.
     */
    fun isVfsReady(): Boolean {
        if (!isHybridMountInstalled()) return false
        val doctor = RootShell.run("'$HM_PATH' vfs doctor 2>/dev/null").outString
        if (doctor.contains("Responds: true") || doctor.contains("Active rules")) {
            return true
        }
        val load = RootShell.run("'$HM_PATH' vfs load 2>&1").outString
        if (load.contains("ready", ignoreCase = true)) {
            val retry = RootShell.run("'$HM_PATH' vfs doctor 2>/dev/null").outString
            return retry.contains("Responds: true") || retry.contains("Active rules")
        }
        return false
    }

    fun getStatus(): VfsStatus {
        return when {
            isVfsReady() -> VfsStatus.ACTIVE_VFS
            RootShell.isRootAvailable() -> VfsStatus.FALLBACK_BIND
            else -> VfsStatus.NOT_AVAILABLE
        }
    }

    fun getActiveRules(): List<VfsRule> {
        if (!isVfsReady()) return emptyList()
        val jsonStr = RootShell.run("'$HM_PATH' vfs rule list --json 2>/dev/null").outString.trim()
        if (jsonStr.isEmpty() || !jsonStr.startsWith("[")) return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<VfsRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val v = obj.optString("virtual_path")
                val r = obj.optString("real_path")
                val u = obj.optInt("uid", 0)
                if (v.isNotEmpty() && r.isNotEmpty()) {
                    list.add(VfsRule(virtualPath = v, realPath = r, uid = u))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isRedirectActive(virtualPath: String): Boolean {
        if (isVfsReady()) {
            val rules = getActiveRules()
            if (rules.any { it.virtualPath == virtualPath }) return true
        }
        // Also check mount table in case it was mounted via bind-mount
        val mountCheck = RootShell.run("grep -q ' $virtualPath ' /proc/mounts 2>/dev/null && echo yes || echo no").outString.trim()
        return mountCheck == "yes"
    }

    fun addRedirect(virtualPath: String, realPath: String): Boolean {
        if (isVfsReady()) {
            val res = RootShell.run("'$HM_PATH' vfs rule add '$virtualPath' '$realPath' 2>&1")
            if (res.success && !res.outString.contains("Error", ignoreCase = true)) {
                return true
            }
        }
        // Fallback: bind-mount
        val bindRes = RootShell.run("mount --bind '$realPath' '$virtualPath'")
        return bindRes.success
    }

    fun removeRedirect(virtualPath: String): Boolean {
        var removed = false
        if (isVfsReady()) {
            val res = RootShell.run("'$HM_PATH' vfs rule del '$virtualPath' 2>&1")
            if (res.success) removed = true
        }
        // Also unmount in case a bind-mount is active
        RootShell.run("umount -l '$virtualPath' 2>/dev/null")
        return removed || !isRedirectActive(virtualPath)
    }

    fun getPendingBindMountPoints(): List<String> {
        if (!isVfsReady()) return emptyList()
        val activeRules = getActiveRules().map { it.virtualPath }.toSet()
        val cmd = """
            for m in ${'$'}(grep -E ' /(vendor|system|system_ext|product|apex)/' /proc/mounts 2>/dev/null | awk '{print ${'$'}2}' | sort -u); do
                [ -f "${'$'}m" ] && echo "${'$'}m"
            done
        """.trimIndent()
        val res = RootShell.run(cmd)
        if (!res.success || res.out.isEmpty()) return emptyList()
        return res.out.map { it.trim() }.filter { it.isNotEmpty() && it !in activeRules }
    }

    fun getPendingBindMountCount(): Int {
        return getPendingBindMountPoints().size
    }

    fun migrateBindMountsToVfs(): Int {
        if (!isVfsReady()) return 0
        val pendingTargets = getPendingBindMountPoints()
        if (pendingTargets.isEmpty()) return 0

        var migrated = 0
        val mountInfo = RootShell.run("cat /proc/self/mountinfo").outString

        for (mountPath in pendingTargets) {
            val sourceMatch = Regex("([/][^ ]+)\\s+$mountPath").find(mountInfo)
            var source = sourceMatch?.groupValues?.get(1)
            if (source.isNullOrEmpty() || source == mountPath) continue

            // If source doesn't exist directly (e.g. mount root was /data or /adb), resolve full path
            if (RootShell.run("[ -f '$source' ] && echo yes || echo no").outString.trim() != "yes") {
                if (RootShell.run("[ -f '/data$source' ] && echo yes || echo no").outString.trim() == "yes") {
                    source = "/data$source"
                } else if (RootShell.run("[ -f '/data/adb${source.removePrefix("/adb")}' ] && echo yes || echo no").outString.trim() == "yes") {
                    source = "/data/adb${source.removePrefix("/adb")}"
                }
            }

            if (RootShell.run("[ -f '$source' ] && echo yes || echo no").outString.trim() == "yes") {
                if (addRedirect(mountPath, source)) {
                    RootShell.run("umount -l '$mountPath' 2>/dev/null")
                    migrated++
                }
            }
        }
        return migrated
    }

    fun installHybridMountModule(context: Context): Boolean {
        val targetZip = "/data/local/tmp/hybrid_mount.zip"
        val extracted = AssetInstaller.installAsset(
            context,
            "hotfixes/hybrid_mount/hybrid_mount.zip",
            targetZip,
            "644"
        )
        if (!extracted.success) return false

        val installCmd = """
            if [ -x /data/adb/ksud ]; then
                /data/adb/ksud module install '$targetZip'
            elif [ -x /data/adb/magisk/magisk64 ]; then
                /data/adb/magisk/magisk64 --install-module '$targetZip'
            elif command -v magisk >/dev/null 2>&1; then
                magisk --install-module '$targetZip'
            else
                exit 1
            fi
        """.trimIndent()
        val res = RootShell.run(installCmd)
        RootShell.run("rm -f '$targetZip'")
        return res.success
    }
}
