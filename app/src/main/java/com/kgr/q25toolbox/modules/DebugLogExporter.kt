package com.kgr.q25toolbox.modules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kgr.q25toolbox.core.RootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the current logcat buffer (unfiltered - release builds aren't minified, so every
 * module's Log.d/Log.e tag already survives as-is) plus a device/version header to a plain
 * text file in the user's own Documents folder, for sharing in bug reports without needing
 * a separate rooted logcat-reader app installed.
 */
object DebugLogExporter {

    private const val EXPORT_DIR = "/storage/emulated/0/Documents/q25toolbox"

    sealed class Result {
        data class Success(val path: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun export(context: Context): Result {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "debug_$timestamp.log"
        val targetPath = "$EXPORT_DIR/$fileName"

        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = packageInfo?.longVersionCode ?: 0L

        val header = buildString {
            appendLine("Q25 Toolbox debug log - $timestamp")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine("----")
        }

        val staging = File(context.filesDir, "debug_log_header.tmp")
        staging.writeText(header)

        val result = RootShell.run(
            "mkdir -p '$EXPORT_DIR' && cat '${staging.absolutePath}' > '$targetPath' && " +
                "logcat -d >> '$targetPath' && chmod 644 '$targetPath'"
        )
        staging.delete()

        return if (result.success) {
            Result.Success(targetPath)
        } else {
            Result.Failure(result.outString.takeIf { it.isNotBlank() } ?: "logcat export failed")
        }
    }
}
