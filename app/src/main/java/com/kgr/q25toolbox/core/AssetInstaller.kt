package com.kgr.q25toolbox.core

import android.content.Context
import java.io.File

/**
 * Installs scripts bundled in app/src/main/assets/ to arbitrary root-owned
 * paths (primarily /data/adb/service.d/).
 *
 * KNOWN RISK: writing to /data/adb/service.d/ from a root shell spawned
 * post-boot has previously failed with "Permission denied" even for `su -c`
 * (a filesystem-encryption-context mismatch with the shell that originally
 * created files there). `install -m 755` from an interactive root session is
 * the method known to work from Termux. This wrapper uses the same approach
 * via libsu's root shell, but ALWAYS verify the result with [fileExists] /
 * [readFile] after writing - if it silently fails, fall back to exporting the
 * file (see MainActivity's "Export scripts" option, if added) and installing
 * it manually via Termux.
 */
object AssetInstaller {

    fun installFromAsset(
        context: Context,
        assetName: String,
        targetPath: String,
        transform: ((String) -> String)? = null
    ): ShellResult {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val content = transform?.invoke(raw) ?: raw

        val tmp = File(context.filesDir, assetName)
        tmp.writeText(content)

        return RootShell.run("install -m 755 '${tmp.absolutePath}' '$targetPath'")
    }

    /**
     * Byte-for-byte copy of a bundled asset to [targetPath] with mode [mode].
     * Binary-safe (unlike [installFromAsset], which is text/templating oriented),
     * so it's suitable for the `com.blackberry.only.jar` and other non-text
     * assets. The staging filename is flattened from any asset subpath.
     */
    fun installAsset(
        context: Context,
        assetName: String,
        targetPath: String,
        mode: String = "755",
    ): ShellResult {
        val staging = File(context.filesDir, assetName.substringAfterLast('/'))
        context.assets.open(assetName).use { input ->
            staging.outputStream().use { input.copyTo(it) }
        }
        return RootShell.run("install -m $mode '${staging.absolutePath}' '$targetPath'")
    }

    /** Installs generated [content] (rather than a bundled asset) to [targetPath]. */
    fun installContent(
        context: Context,
        stagingName: String,
        content: String,
        targetPath: String
    ): ShellResult {
        val tmp = File(context.filesDir, stagingName)
        tmp.writeText(content)
        return RootShell.run("install -m 755 '${tmp.absolutePath}' '$targetPath'")
    }

    fun removeFile(targetPath: String): ShellResult =
        RootShell.run("rm -f '$targetPath'")

    fun fileExists(path: String): Boolean =
        RootShell.run("[ -f '$path' ] && echo yes || echo no").outString.trim() == "yes"

    fun readFile(path: String): String =
        RootShell.run("cat '$path' 2>/dev/null").outString

    /**
     * Whether [targetPath] already contains exactly what [installFromAsset] would
     * write right now for [assetName]/[transform] - i.e. whether a running daemon
     * is executing today's script, not one left behind by an older app build.
     *
     * This matters because a bare "is the process alive" check can't tell the two
     * apart: a stale script loops forever and holds its own PID lock exactly like a
     * healthy one, so it never looks dead and never gets replaced - it just quietly
     * stops doing whatever the newer version was supposed to do. Found in practice
     * on a device where the Extra Dim schedule daemon was a leftover pre-scheduling
     * build (looping with no actual on/off logic) and block_telemetry.sh was a
     * leftover pre-hardening build (weaker PID-reuse check) - both alive, both
     * silently outdated, neither caught by a liveness-only check.
     */
    fun matchesAsset(
        context: Context,
        assetName: String,
        targetPath: String,
        transform: ((String) -> String)? = null
    ): Boolean {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val expected = (transform?.invoke(raw) ?: raw).trimEnd()
        return readFile(targetPath).trimEnd() == expected
    }
}
