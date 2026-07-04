package com.kgr.q25toolbox.core

import com.topjohnwu.superuser.Shell

/**
 * Thin wrapper around libsu.
 *
 * Notes specific to FolkPatch/APatch's `su`:
 *  - It does not support `su -s`. Don't rely on flags being forwarded.
 *  - Treat each [run] call as one self-contained command. If you need a
 *    sequence of steps, chain them with `&&` / `;` inside a single string
 *    rather than issuing multiple separate [run] calls that depend on each
 *    other's shell state (cwd, exported vars, etc.) - each exec() may run in
 *    a fresh shell invocation depending on libsu's pooling.
 */
object RootShell {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    fun run(cmd: String): ShellResult {
        val result = Shell.cmd(cmd).exec()
        return ShellResult(result.isSuccess, result.out)
    }

    /**
     * Blocks until the root shell has actually finished initializing
     * (including any grant prompt), then reports whether it's a root
     * shell. Unlike Shell.isAppGrantedRoot() alone, this doesn't race
     * with shell startup - it's safe to call from a cold launch.
     */
    fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        false
    }
}

data class ShellResult(
    val success: Boolean,
    val out: List<String>
) {
    val outString: String get() = out.joinToString("\n")
}
