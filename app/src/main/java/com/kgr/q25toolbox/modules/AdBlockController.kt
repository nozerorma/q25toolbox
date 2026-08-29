package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult
import java.io.File

/**
 * Systemless hosts-based ad blocker, ported from the standalone
 * "systemless-hosts" Magisk/KernelSU/APatch module (upstream: gloeyisk,
 * packaged by kgr-online). Unlike CtrlKeyController's small keylayout patch,
 * this deploys a full module (module.prop + hosts_ctl.sh + post-fs-data.sh)
 * plus a persistent data directory, and drives everything through
 * hosts_ctl.sh rather than editing files directly - the shell script already
 * owns the compile/rebuild/dedupe logic, so Kotlin just shells out to it.
 *
 * Two path roots, both intentionally OUTSIDE the module dir except where the
 * module itself needs to see them:
 *
 *  - MODULE_DIR (/data/adb/modules/q25_adblock): the mounted module itself -
 *    module.prop, hosts_ctl.sh, post-fs-data.sh, and the staged
 *    system/etc/hosts that Magisk/APatch bind-mounts at boot. Wiped and
 *    replaced wholesale on every [install] call and on module updates.
 *
 *  - PERSIST (/data/adb/q25_adblock): blacklist sources, user edits,
 *    whitelist, and enabled/disabled state. Survives module
 *    reinstalls/updates since it's never touched by Magisk's module
 *    replacement. 
 *    Backup & Restore).
 *
 * Module ID kept as the upstream "systemless-hosts" would have suggested,
 * but namespaced to q25_adblock per project convention (matches
 * k2tb_ctrlfix) - see hosts_ctl.sh's MODDIR/PERSIST constants, which were
 * repointed here from the upstream script rather than left as-is.
 *
 * IMPORTANT: hosts_ctl.sh's `rebuild` mirrors the compiled list straight onto
 * the LIVE /system/etc/hosts path, but that only works once the module's
 * bind-mount is actually active - which only happens after a boot with the
 * module present. So content edits (add/remove/whitelist/update) are live
 * with no reboot ONLY after the first post-install reboot; see
 * [requiresReboot].
 */
object AdBlockController {

    private const val MODULE_ID = "q25_adblock"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val PERSIST = "/data/adb/q25_adblock"
    private const val CACHE = "$PERSIST/cache"
    private const val HOSTS_CTL = "$MODULE_DIR/hosts_ctl.sh"
    private const val LIVE_HOSTS = "/system/etc/hosts"

    private const val ASSET_MODULE_PROP = "adblock_module.prop"
    private const val ASSET_HOSTS_CTL = "adblock_hosts_ctl.sh"
    private const val ASSET_POST_FS_DATA = "adblock_post_fs_data.sh"
    private const val ASSET_DEFAULT_HOSTS = "adblock_default_hosts.txt"

    /** Files under PERSIST that Backup & Restore round-trips verbatim. */
    val PERSISTED_DATA_FILES = listOf(
        "sources.txt", "user_added.txt", "wildcard_added.txt",
        "user_removed.txt", "whitelist.txt"
    )

    // ------------------------------------------------------------- Status

    fun isInstalled(): Boolean = AssetInstaller.fileExists(HOSTS_CTL)

    /**
     * Whether the module's overlay onto /system/etc/hosts is actually active
     * right now. NOT detected via the mount table - different root
     * implementations represent the overlay differently (e.g. APatch/FolkPatch
     * can show it as a plain block-device mount with no path referencing the
     * module dir at all, rather than a bind-mount naming MODULE_DIR). Instead
     * this checks for the header line hosts_ctl.sh's rebuild() writes when it
     * successfully mirrors onto the live path - that mirror silently no-ops
     * (see rebuild()'s `2>/dev/null`) if the live path is still genuinely
     * read-only, which is exactly the pre-reboot state we need to detect.
     */
    fun isMounted(): Boolean =
        RootShell.run("grep -q 'Systemless Hosts by the' '$LIVE_HOSTS' 2>/dev/null && echo yes || echo no")
            .outString.trim() == "yes"

    /** True right after [install] (or any time the module exists but hasn't been through a boot yet). */
    fun requiresReboot(): Boolean = isInstalled() && !isMounted()

    fun isEnabled(): Boolean {
        if (!isInstalled()) return false
        return RootShell.run("sh '$HOSTS_CTL' status").outString.trim() != "disabled"
    }

    fun entryCount(): Int =
        RootShell.run("sh '$HOSTS_CTL' count").outString.trim().toIntOrNull() ?: 0

    // ------------------------------------------------------------ Install

    /**
     * First-time (or re-)deployment: seeds PERSIST with the bundled default
     * blacklist and empty edit files if not already present (so re-running
     * this after an uninstall doesn't clobber existing user edits), stages
     * hosts_ctl.sh + post-fs-data.sh into the module dir, compiles an
     * initial blacklist, then writes module.prop LAST so a half-deployed
     * module is never picked up by Magisk/APatch mid-write.
     */
    fun install(context: Context): ShellResult {
        val mkdirs = RootShell.run("mkdir -p '$CACHE'")
        if (!mkdirs.success) return mkdirs

        if (!AssetInstaller.fileExists("$CACHE/default.txt")) {
            val tmp = File(context.filesDir, ASSET_DEFAULT_HOSTS)
            context.assets.open(ASSET_DEFAULT_HOSTS).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val seed = RootShell.run("install -m 644 '${tmp.absolutePath}' '$CACHE/default.txt'")
            tmp.delete()
            if (!seed.success) return seed
        }

        val seedEmpty = RootShell.run(
            PERSISTED_DATA_FILES.joinToString(" ; ") { name ->
                "[ -f '$PERSIST/$name' ] || : > '$PERSIST/$name'"
            } + " ; [ -f '$PERSIST/state' ] || echo enabled > '$PERSIST/state'"
        )
        if (!seedEmpty.success) return seedEmpty

        // install(1) writes into an existing directory but won't create missing
        // parents - MODULE_DIR itself has to exist before hosts_ctl.sh can land in it.
        val mkModuleDir = RootShell.run("mkdir -p '$MODULE_DIR'")
        if (!mkModuleDir.success) return mkModuleDir

        val ctl = AssetInstaller.installFromAsset(context, ASSET_HOSTS_CTL, HOSTS_CTL)
        if (!ctl.success) return ctl

        val pfd = AssetInstaller.installFromAsset(context, ASSET_POST_FS_DATA, "$MODULE_DIR/post-fs-data.sh")
        if (!pfd.success) return pfd

        val compile = RootShell.run("sh '$HOSTS_CTL' compile")
        if (!compile.success) return compile

        // module.prop written last - its presence is what makes root managers treat this as a real module.
        return AssetInstaller.installFromAsset(context, ASSET_MODULE_PROP, "$MODULE_DIR/module.prop")
    }

    /** Removes the module itself. Persisted blacklist/edits under PERSIST are kept - see [wipePersistedData]. */
    fun uninstall(): ShellResult = RootShell.run("rm -rf '$MODULE_DIR'")

    /** Full reset: removes persisted sources/edits/whitelist too. Call [uninstall] first if also removing the module. */
    fun wipePersistedData(): ShellResult = RootShell.run("rm -rf '$PERSIST'")

    // --------------------------------------------------------- Enable/pause

    /** Pauses/resumes filtering live (rebuild() mirrors straight to the live mount) - no reboot needed once mounted. */
    fun setEnabled(enabled: Boolean): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' ${if (enabled) "enable" else "disable"}")

    // ------------------------------------------------------------- Entries

    fun listEntries(offset: Int = 0, limit: Int = 200): List<String> =
        RootShell.run("sh '$HOSTS_CTL' list $offset $limit").out.mapNotNull(::stripHostsPrefix)

    fun searchEntries(term: String, limit: Int = 200): List<String> {
        if (term.isBlank()) return emptyList()
        return RootShell.run("sh '$HOSTS_CTL' search '${shellEscape(term)}' $limit").out
            .mapNotNull(::stripHostsPrefix)
    }

    /** Accepts an exact domain (ads.example.com) or a glob (*.doubleclick.net). */
    fun addEntry(domainOrGlob: String): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' add '${shellEscape(domainOrGlob)}'")

    fun removeEntry(domain: String): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' remove '${shellEscape(domain)}'")

    // ----------------------------------------------------------- Whitelist

    fun whitelistList(): List<String> =
        RootShell.run("sh '$HOSTS_CTL' whitelist_list").out.filter { it.isNotBlank() }

    fun whitelistAdd(domainOrGlob: String): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' whitelist_add '${shellEscape(domainOrGlob)}'")

    fun whitelistRemove(domainOrGlob: String): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' whitelist_remove '${shellEscape(domainOrGlob)}'")

    // ------------------------------------------------------------- Sources

    /** Returns (1-indexed line number, url) pairs, matching hosts_ctl.sh's src_remove numbering. */
    fun sourceList(): List<Pair<Int, String>> =
        RootShell.run("sh '$HOSTS_CTL' src_list").out.mapNotNull { line ->
            val trimmed = line.trim()
            val spaceIdx = trimmed.indexOf(' ')
            if (spaceIdx <= 0) return@mapNotNull null
            trimmed.substring(0, spaceIdx).toIntOrNull()?.let { it to trimmed.substring(spaceIdx + 1) }
        }

    fun sourceAdd(url: String): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' src_add '${shellEscape(url)}'")

    fun sourceRemove(lineNumber: Int): ShellResult =
        RootShell.run("sh '$HOSTS_CTL' src_remove $lineNumber")

    /** Kicks off an async fetch+recompile of all sources; poll [updateStatus] for progress. */
    fun triggerUpdate(): ShellResult = RootShell.run("sh '$HOSTS_CTL' update")

    /** One of: "none", "running:<ts>", "done:<ts>", "error:<msg>:<ts>". */
    fun updateStatus(): String = RootShell.run("sh '$HOSTS_CTL' update_status").outString.trim()

    /** Wipes sources + all manual edits, keeps only the bundled default list. */
    fun resetToDefaults(): ShellResult = RootShell.run("sh '$HOSTS_CTL' reset")

    // -------------------------------------------------- Backup/restore I/O

    /** Raw contents of one PERSIST file (see [PERSISTED_DATA_FILES]), for export. */
    fun readPersistedFile(name: String): String = AssetInstaller.readFile("$PERSIST/$name")

    /**
     * Writes one PERSIST file verbatim WITHOUT recompiling - callers restoring
     * multiple files should write them all first, then call [recompile] once.
     */
    fun writePersistedFileRaw(context: Context, name: String, content: String): ShellResult {
        val tmp = File(context.filesDir, "adblock_restore_$name")
        tmp.writeText(content)
        val result = RootShell.run("install -m 644 '${tmp.absolutePath}' '$PERSIST/$name'")
        tmp.delete()
        return result
    }

    fun recompile(): ShellResult = RootShell.run("sh '$HOSTS_CTL' compile")

    // --------------------------------------------------------------- Utils

    private fun stripHostsPrefix(line: String): String? {
        val stripped = line.removePrefix("127.0.0.1 ").removePrefix("0.0.0.0 ").trim()
        return stripped.ifBlank { null }
    }

    /** hosts_ctl.sh receives domains/URLs inside single-quoted shell args - close and re-open the quote for any embedded ' rather than trusting input is safe. */
    private fun shellEscape(s: String): String = s.replace("'", "'\\''")
}
