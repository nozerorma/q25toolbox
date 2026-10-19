package com.kgr.q25toolbox.modules.hotfix

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.Rom
import com.kgr.q25toolbox.core.RomProfile
import com.kgr.q25toolbox.core.RootShell
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object HotfixController {

    private const val PREFS = "q25_hotfixes"
    const val HOTFIXES_DIR = "/data/adb/q25_hotfixes"
    private const val BOOT_SCRIPT = "/data/adb/service.d/q25_hotfixes_boot.sh"

    // Bluetooth Audio constants
    private const val MTK_AUDIO_HAL = "/vendor/lib/hw/android.hardware.audio@7.0-impl-mediatek.so"
    private const val BT_AUDIO_TARGET = "/vendor/lib/hw/audio.bluetooth.default.so"
    private const val BT_AUDIO_PATCHED = "$HOTFIXES_DIR/audio.bluetooth.default.so"

    // KeyMint / TrickyStore constants
    private const val TRUSTKERNEL_KEYMINT_BIN = "/vendor/bin/hw/android.hardware.security.keymint-service.trustkernel"
    const val SUPPORTED_ALWAYS_STRONG_VERSIONS = "v1.0.4 / v1.0.5"
    private val TEE_CANDIDATE_PATHS = listOf(
        "/data/adb/modules/tricky_store/tee_classes.dex",
        "/data/adb/modules/always_strong/tee_classes.dex"
    )

    fun getAlwaysStrongTeePath(): String {
        return TEE_CANDIDATE_PATHS.firstOrNull { AssetInstaller.fileExists(it) }
            ?: "/data/adb/modules/tricky_store/tee_classes.dex"
    }

    private const val TEE_BACKUP = "$HOTFIXES_DIR/tee_classes.stock.dex.bak"
    private const val TEE_PATCHED = "$HOTFIXES_DIR/tee_classes.patched.dex"
    private const val TEE_STOCK = "$HOTFIXES_DIR/tee_classes.stock.dex"
    private const val TEE_PATCHED_MD5 = "a9aaae56ab3ee5a68e23fe5af5728a5d"
    private const val TEE_OLD_PATCHED_MD5 = "57f27e8a33483e94e4088fd2546383ec"
    private val TEE_STOCK_MD5S = setOf(
        "249fdbecbcb478b1602d0b40ca0c2fb5",
        "146ba38a3db4d7db45609aed64dded67"
    )

    private const val TEST_EC_P8_BASE64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgTvItbjAuWQHPHOpeo5sHK78yFxjT3KK9M0WQL8+oYouhRANCAAR+kG/qdyPLITmiEeFgZgrhnlozqPyNS/F95Cyv+RNhRawoLSegfOgD/FyExwsG1oC3vsDxaInKbbIpR5xJ1s9f"

    private const val TEST_EC_CERT_BASE64 =
        "MIIBczCCARmgAwIBAgIUDzTvNKV90lCVwmSRBpusmQiZW7IwCgYIKoZIzj0EAwIwDzENMAsGA1UEAwwEdGVzdDAeFw0yNjEwMTQxMjA5NTVaFw0yNzEwMTQxMjA5NTVaMA8xDTALBgNVBAMMBHRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAR+kG/qdyPLITmiEeFgZgrhnlozqPyNS/F95Cyv+RNhRawoLSegfOgD/FyExwsG1oC3vsDxaInKbbIpR5xJ1s9fo1MwUTAdBgNVHQ4EFgQU3v5C4YiBSwByZGRH9y2F30f6i7cwHwYDVR0jBBgwFoAU3v5C4YiBSwByZGRH9y2F30f6i7cwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiEAqTjT2/9n8GzNanro1p3c0SKG1eeeGJtTLluVP5HweNECIDj9a2GHNG6z5pDVCWG+ehUHLuj5WTx2eG7/hf9wQkqv"

    /**
     * Probes whether the device's KeyMint / KeyStore can import PKCS#8 asymmetric keys.
     * TrustKernel's keymint-service fails with -38 (INVALID_ARGUMENT) when importing
     * PKCS#8 keys, which breaks Curve, Google Wallet, and other apps unless patched.
     *
     * Returns true if import succeeded (or already patched/working natively),
     * false if it failed due to the TrustKernel bug.
     */
    fun probeKeyMintPkcs8Import(): Boolean {
        val alias = "q25_probe_pkcs8"
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val p8Bytes = Base64.decode(TEST_EC_P8_BASE64, Base64.DEFAULT)
            val certBytes = Base64.decode(TEST_EC_CERT_BASE64, Base64.DEFAULT)
            val k = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(p8Bytes))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certBytes))
            ks.setKeyEntry(alias, k, null, arrayOf(cert))
            try { ks.deleteEntry(alias) } catch (_: Throwable) {}
            true
        } catch (e: Throwable) {
            Log.d("HotfixController", "KeyMint PKCS8 import probe failed (TrustKernel -38 or similar): ${e.message}")
            false
        }
    }

    private fun extractKeyStoreExceptionCode(t: Throwable): Int? {
        var curr: Throwable? = t
        while (curr != null) {
            if (curr.javaClass.name == "android.security.KeyStoreException") {
                try {
                    val method = curr.javaClass.getMethod("getNumericErrorCode")
                    return (method.invoke(curr) as? Number)?.toInt()
                } catch (_: Throwable) {}
                try {
                    val method = curr.javaClass.getMethod("getErrorCode")
                    return (method.invoke(curr) as? Number)?.toInt()
                } catch (_: Throwable) {}
            }
            val msg = curr.message
            if (msg != null) {
                val match = Regex("""(?:code\s*=?\s*|error\s*=?\s*|\()(-?\d+)\)?""", RegexOption.IGNORE_CASE).find(msg)
                if (match != null) {
                    val code = match.groupValues[1].toIntOrNull()
                    if (code != null) return code
                }
            }
            curr = curr.cause
        }
        return null
    }

    /**
     * Probes whether TrustKernel KeyMint properly enforces RSA-OAEP MGF1 digests.
     * On unpatched TrustKernel (or with older TEE patches), when a key only authorizes
     * SHA-256 MGF1, default SHA-1 is mistakenly accepted or explicit incompatible digests
     * (e.g. SHA-224) fail with -13 (INCOMPATIBLE_DIGEST) instead of -78 (INCOMPATIBLE_MGF_DIGEST),
     * causing Duck Detector and crypto compliance checks to flag hardware KeyMint failure.
     *
     * Returns true if RSA-OAEP MGF1 handling is compliant / fixed, false if the bug is present.
     */
    fun probeKeyMintRsaOaep(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alias = "q25_probe_oaep_mgf1"
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            try { ks.deleteEntry(alias) } catch (_: Throwable) {}

            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setBlockModes(KeyProperties.BLOCK_MODE_ECB)

            try {
                val method = builder.javaClass.getMethod("setMgf1Digests", Array<String>::class.java)
                method.invoke(builder, arrayOf(KeyProperties.DIGEST_SHA256))
            } catch (_: Throwable) {}

            val spec = builder.build()
            kpg.initialize(spec as java.security.spec.AlgorithmParameterSpec)
            val kp = kpg.generateKeyPair()

            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            val oaepSpec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA224,
                PSource.PSpecified.DEFAULT
            )
            val passed =
                try {
                    cipher.init(Cipher.DECRYPT_MODE, kp.private, oaepSpec)
                    false
                } catch (e: Throwable) {
                    val errorCode = extractKeyStoreExceptionCode(e)
                    errorCode == -78
                } finally {
                    try { ks.deleteEntry(alias) } catch (_: Throwable) {}
                }
            passed
        } catch (e: Throwable) {
            Log.d("HotfixController", "KeyMint RSA-OAEP probe error: ${e.message}")
            false
        }
    }

    fun probeKeyMintHasBugs(): Boolean {
        return !probeKeyMintPkcs8Import() || !probeKeyMintRsaOaep()
    }

    // Vector dex2oat constants
    private const val KSUD = "/data/adb/ksud"
    private const val VECTOR_DIR = "/data/adb/modules/zygisk_vector"
    private const val DEX2OAT_32 = "/apex/com.android.art/bin/dex2oat32"
    private const val DEX2OAT_64 = "/apex/com.android.art/bin/dex2oat64"
    private const val VECTOR_DEX2OAT_32 = "$VECTOR_DIR/bin/dex2oat32"
    private const val VECTOR_DEX2OAT_64 = "$VECTOR_DIR/bin/dex2oat64"

    /**
     * Probes whether Vector's dex2oat bind mounts are actively exposed in /proc/mounts
     * or /proc/self/mountinfo (the failure check, like probeKeyMintPkcs8Import).
     */
    fun probeVectorMountExposure(): Boolean {
        val mounts = RootShell.run("grep -E ' /apex/com.android.art/bin/dex2oat(32|64) ' /proc/mounts 2>/dev/null").outString.trim()
        return mounts.isNotEmpty()
    }

    fun isVectorInstalled(): Boolean {
        return AssetInstaller.fileExists(VECTOR_DIR) ||
                AssetInstaller.fileExists(VECTOR_DEX2OAT_32) ||
                AssetInstaller.fileExists(VECTOR_DEX2OAT_64)
    }

    // Camera 50MP constants
    private const val CAM_DIR = "$HOTFIXES_DIR/camera_50mp"
    private const val CAM_KO_STOCK = "/vendor_dlkm/lib/modules/imgsensor_isp6s.ko"
    private const val CAM_KO_PATCHED = "$CAM_DIR/imgsensor_isp6s.ko"
    private const val CAM_SHIM = "$CAM_DIR/libremosaic_wrapper.so"
    private const val CAM_EDITS = "$CAM_DIR/edits.txt"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isHotfixEnabled(context: Context, id: HotfixId): Boolean {
        return getPrefs(context).getBoolean(id.name, false)
    }

    fun setHotfixEnabled(context: Context, id: HotfixId, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(id.name, enabled).apply()
    }

    // -------------------------------------------------------------------------
    // Bluetooth Audio
    // -------------------------------------------------------------------------

    fun checkBtAudio(context: Context): HotfixItem {
        val rom = RomProfile.get(context).rom
        val enabled = isHotfixEnabled(context, HotfixId.BT_AUDIO)
        val isLive = VfsManager.isRedirectActive(BT_AUDIO_TARGET)

        // 1. ROM check: only applicable on LineageOS
        if (rom != Rom.LINEAGE) {
            return HotfixItem(
                id = HotfixId.BT_AUDIO,
                titleRes = R.string.hotfix_bt_audio_title,
                descRes = R.string.hotfix_bt_audio_desc,
                requiresReboot = false,
                status = HotfixStatus.NOT_APPLICABLE,
                statusDetail = context.getString(R.string.hotfix_bt_audio_not_needed),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        // 2. MTK Audio HAL blob check
        if (!AssetInstaller.fileExists(MTK_AUDIO_HAL)) {
            return HotfixItem(
                id = HotfixId.BT_AUDIO,
                titleRes = R.string.hotfix_bt_audio_title,
                descRes = R.string.hotfix_bt_audio_desc,
                requiresReboot = false,
                status = HotfixStatus.NOT_APPLICABLE,
                statusDetail = context.getString(R.string.hotfix_bt_audio_not_needed),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        // 3. Bluetooth target file check
        if (!AssetInstaller.fileExists(BT_AUDIO_TARGET)) {
            return HotfixItem(
                id = HotfixId.BT_AUDIO,
                titleRes = R.string.hotfix_bt_audio_title,
                descRes = R.string.hotfix_bt_audio_desc,
                requiresReboot = false,
                status = HotfixStatus.INCOMPATIBLE,
                statusDetail = context.getString(R.string.hotfix_file_missing, BT_AUDIO_TARGET),
                isEnabled = enabled,
                isLiveApplied = isLive
            )
        }

        // 4. ABI Mismatch check: MTK uses legacy 68-byte struct while BT stack uses 84-byte struct
        val hasMismatch = RootShell.run(
            "grep -q 'playback_track_metadata' '$MTK_AUDIO_HAL' && grep -q 'source_metadata_v7' '$BT_AUDIO_TARGET' && echo yes || echo no"
        ).outString.trim() == "yes"

        if (!hasMismatch && !isLive) {
            return HotfixItem(
                id = HotfixId.BT_AUDIO,
                titleRes = R.string.hotfix_bt_audio_title,
                descRes = R.string.hotfix_bt_audio_desc,
                requiresReboot = false,
                status = HotfixStatus.NOT_APPLICABLE,
                statusDetail = context.getString(R.string.hotfix_bt_audio_no_mismatch),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        return HotfixItem(
            id = HotfixId.BT_AUDIO,
            titleRes = R.string.hotfix_bt_audio_title,
            descRes = R.string.hotfix_bt_audio_desc,
            requiresReboot = false,
            status = if (isLive) HotfixStatus.ACTIVE else HotfixStatus.AVAILABLE,
            statusDetail = if (isLive) context.getString(R.string.hotfix_status_active_live) else null,
            isEnabled = enabled,
            isLiveApplied = isLive
        )
    }

    fun applyBtAudio(): Boolean {
        val prepareCmd = """
            mkdir -p '$HOTFIXES_DIR' &&
            cp '$BT_AUDIO_TARGET' '$BT_AUDIO_PATCHED' &&
            printf '\x01\x20\x70\x47' | dd of='$BT_AUDIO_PATCHED' bs=1 seek=$((0xf534)) conv=notrunc &&
            chmod 755 '$BT_AUDIO_PATCHED' &&
            chcon u:object_r:vendor_file:s0 '$BT_AUDIO_PATCHED'
        """.trimIndent()

        val prepRes = RootShell.run(prepareCmd)
        if (!prepRes.success) return false

        val redirected = VfsManager.addRedirect(BT_AUDIO_TARGET, BT_AUDIO_PATCHED)
        if (redirected) {
            RootShell.run("killall android.hardware.audio.service audioserver 2>/dev/null")
            return true
        }
        return false
    }

    fun revertBtAudio(): Boolean {
        VfsManager.removeRedirect(BT_AUDIO_TARGET)
        RootShell.run("killall android.hardware.audio.service audioserver 2>/dev/null")
        return !VfsManager.isRedirectActive(BT_AUDIO_TARGET)
    }

    // -------------------------------------------------------------------------
    // KeyMint / TEE Software Import (TrickyStore)
    // -------------------------------------------------------------------------

    fun checkKeymintTee(context: Context): HotfixItem {
        val teePath = getAlwaysStrongTeePath()
        val moduleDir = File(teePath).parent ?: "/data/adb/modules/tricky_store"

        // 1. Module check
        if (!AssetInstaller.fileExists(teePath)) {
            return HotfixItem(
                id = HotfixId.KEYMINT_TEE,
                titleRes = R.string.hotfix_keymint_title,
                descRes = R.string.hotfix_keymint_desc,
                requiresReboot = false,
                status = HotfixStatus.PREREQUISITE_MISSING,
                statusDetail = context.getString(R.string.hotfix_keymint_missing_tricky),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        // 2. Module disabled check (in KernelSU / Magisk / APatch)
        val isModuleDisabled = RootShell.run("[ -f '$moduleDir/disable' ] && echo yes || echo no").outString.trim() == "yes"
        if (isModuleDisabled) {
            return HotfixItem(
                id = HotfixId.KEYMINT_TEE,
                titleRes = R.string.hotfix_keymint_title,
                descRes = R.string.hotfix_keymint_desc,
                requiresReboot = false,
                status = HotfixStatus.PREREQUISITE_MISSING,
                statusDetail = context.getString(R.string.hotfix_keymint_module_disabled),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        // 3. Hardware HAL check: is KeyMint provided by TrustKernel?
        val isTrustKernel = RootShell.run(
            "[ -x '$TRUSTKERNEL_KEYMINT_BIN' ] || pgrep -f keymint-service.trustkernel >/dev/null 2>&1 && echo yes || echo no"
        ).outString.trim() == "yes"

        if (!isTrustKernel) {
            return HotfixItem(
                id = HotfixId.KEYMINT_TEE,
                titleRes = R.string.hotfix_keymint_title,
                descRes = R.string.hotfix_keymint_desc,
                requiresReboot = false,
                status = HotfixStatus.NOT_APPLICABLE,
                statusDetail = context.getString(R.string.hotfix_keymint_not_trustkernel),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        // 4. Version / MD5 check & live probe
        val curMd5 = RootShell.run("md5sum '$teePath' 2>/dev/null | cut -d' ' -f1").outString.trim()
        val isPatched = curMd5 == TEE_PATCHED_MD5
        val isDaemonRunning = RootShell.run("pgrep -f TEESimulator >/dev/null && echo yes || echo no").outString.trim() == "yes"

        val status: HotfixStatus
        val detail: String?

        if (isPatched) {
            status = HotfixStatus.ACTIVE
            detail = if (isDaemonRunning) {
                context.getString(R.string.hotfix_status_active_live)
            } else {
                context.getString(R.string.hotfix_keymint_daemon_stopped)
            }
        } else if (TEE_STOCK_MD5S.contains(curMd5) || curMd5 == TEE_OLD_PATCHED_MD5) {
            val hasBugs = probeKeyMintHasBugs()
            if (!hasBugs) {
                status = HotfixStatus.NOT_APPLICABLE
                detail = context.getString(R.string.hotfix_keymint_not_needed)
            } else {
                status = HotfixStatus.AVAILABLE
                detail = if (curMd5 == TEE_OLD_PATCHED_MD5) {
                    context.getString(R.string.hotfix_keymint_outdated_patch)
                } else {
                    null
                }
            }
        } else {
            status = HotfixStatus.INCOMPATIBLE
            detail = context.getString(R.string.hotfix_keymint_unknown_version, curMd5, SUPPORTED_ALWAYS_STRONG_VERSIONS)
        }

        return HotfixItem(
            id = HotfixId.KEYMINT_TEE,
            titleRes = R.string.hotfix_keymint_title,
            descRes = R.string.hotfix_keymint_desc,
            requiresReboot = false,
            status = status,
            statusDetail = detail,
            isEnabled = isPatched,
            isLiveApplied = isPatched && isDaemonRunning
        )
    }

    fun applyKeymintTee(context: Context): Boolean {
        val teePath = getAlwaysStrongTeePath()
        if (!AssetInstaller.fileExists(teePath)) return false

        val moduleDir = File(teePath).parent ?: "/data/adb/modules/tricky_store"

        // If module was marked disabled, unmark it so it can function
        RootShell.run("rm -f '$moduleDir/disable'")

        // Extract patched asset to HOTFIXES_DIR
        RootShell.run("mkdir -p '$HOTFIXES_DIR'")
        AssetInstaller.installAsset(context, "hotfixes/keymint/tee_classes.patched.dex", TEE_PATCHED, "644")

        val curMd5 = RootShell.run("md5sum '$teePath' 2>/dev/null | cut -d' ' -f1").outString.trim()
        if (TEE_STOCK_MD5S.contains(curMd5) && !AssetInstaller.fileExists(TEE_BACKUP)) {
            RootShell.run("cp '$teePath' '$TEE_BACKUP'")
        } else if (!AssetInstaller.fileExists(TEE_BACKUP)) {
            // Extract the stock asset as backup
            AssetInstaller.installAsset(context, "hotfixes/keymint/tee_classes.stock.dex", TEE_BACKUP, "644")
        }

        val copyCmd = """
            cp '$TEE_PATCHED' '$teePath.tmp' &&
            chmod 644 '$teePath.tmp' &&
            { ctx=${'$'}(ls -Zd '$moduleDir/module.prop' 2>/dev/null | awk '{print ${'$'}1}'); [ -n "${'$'}ctx" ] && chcon "${'$'}ctx" '$teePath.tmp' 2>/dev/null || true; } &&
            mv -f '$teePath.tmp' '$teePath' &&
            killall TEESimulator 2>/dev/null
        """.trimIndent()

        val res = RootShell.run(copyCmd)
        return res.success
    }

    fun revertKeymintTee(context: Context): Boolean {
        val teePath = getAlwaysStrongTeePath()
        val moduleDir = File(teePath).parent ?: "/data/adb/modules/tricky_store"

        RootShell.run("mkdir -p '$HOTFIXES_DIR'")
        if (!AssetInstaller.fileExists(TEE_BACKUP)) {
            AssetInstaller.installAsset(context, "hotfixes/keymint/tee_classes.stock.dex", TEE_STOCK, "644")
            if (AssetInstaller.fileExists(TEE_STOCK)) {
                RootShell.run("cp '$TEE_STOCK' '$TEE_BACKUP'")
            }
        }
        if (!AssetInstaller.fileExists(TEE_BACKUP)) return false

        val restoreCmd = """
            cp '$TEE_BACKUP' '$teePath.tmp' &&
            chmod 644 '$teePath.tmp' &&
            { ctx=${'$'}(ls -Zd '$moduleDir/module.prop' 2>/dev/null | awk '{print ${'$'}1}'); [ -n "${'$'}ctx" ] && chcon "${'$'}ctx" '$teePath.tmp' 2>/dev/null || true; } &&
            mv -f '$teePath.tmp' '$teePath' &&
            killall TEESimulator 2>/dev/null
        """.trimIndent()
        val res = RootShell.run(restoreCmd)
        return res.success
    }

    // -------------------------------------------------------------------------
    // Vector dex2oat Bind Mount Hide (Hybrid Mount VFS + KSU kernel_umount)
    // -------------------------------------------------------------------------

    fun checkVectorDex2oat(context: Context): HotfixItem {
        val enabled = isHotfixEnabled(context, HotfixId.VECTOR_DEX2OAT)
        val vectorInstalled = isVectorInstalled()
        val isExposed = probeVectorMountExposure()
        val vfsReady = VfsManager.isVfsReady()
        val hasKsu = AssetInstaller.fileExists(KSUD)

        // 1. Module check: If Vector is not installed and no dex2oat mounts exist, not applicable
        if (!vectorInstalled && !isExposed) {
            return HotfixItem(
                id = HotfixId.VECTOR_DEX2OAT,
                titleRes = R.string.hotfix_vector_title,
                descRes = R.string.hotfix_vector_desc,
                requiresReboot = false,
                status = HotfixStatus.NOT_APPLICABLE,
                statusDetail = context.getString(R.string.hotfix_vector_not_installed),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        // 2. Prerequisite check: either VFS (Hybrid Mount) or KernelSU kernel_umount needed
        if (!vfsReady && !hasKsu) {
            return HotfixItem(
                id = HotfixId.VECTOR_DEX2OAT,
                titleRes = R.string.hotfix_vector_title,
                descRes = R.string.hotfix_vector_desc,
                requiresReboot = false,
                status = HotfixStatus.PREREQUISITE_MISSING,
                statusDetail = context.getString(R.string.hotfix_vector_missing_ksu),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        val isVfsActive = VfsManager.isRedirectActive(DEX2OAT_32) && VfsManager.isRedirectActive(DEX2OAT_64)
        val ksuHidden = if (hasKsu) {
            val listOutput = RootShell.run("'$KSUD' kernel umount list 2>/dev/null").outString
            listOutput.contains(DEX2OAT_32) && listOutput.contains(DEX2OAT_64)
        } else false

        val isLive = (!isExposed && isVfsActive) || (enabled && (isVfsActive || ksuHidden))

        val status: HotfixStatus
        val detail: String?

        if (isLive) {
            status = HotfixStatus.ACTIVE
            detail = if (isVfsActive && !isExposed) {
                context.getString(R.string.hotfix_status_active_live)
            } else {
                context.getString(R.string.hotfix_vector_hidden)
            }
        } else if (isExposed) {
            // Failure detected! Live probe shows dex2oat bind mounts exposed in /proc/mounts
            status = HotfixStatus.AVAILABLE
            detail = context.getString(R.string.hotfix_vector_mount_exposed)
        } else {
            status = HotfixStatus.NOT_APPLICABLE
            detail = context.getString(R.string.hotfix_vector_not_needed)
        }

        return HotfixItem(
            id = HotfixId.VECTOR_DEX2OAT,
            titleRes = R.string.hotfix_vector_title,
            descRes = R.string.hotfix_vector_desc,
            requiresReboot = !vfsReady,
            status = status,
            statusDetail = detail,
            isEnabled = isLive || enabled,
            isLiveApplied = isLive && !isExposed
        )
    }

    fun applyVectorDex2oat(): Boolean {
        var success = false

        // 1. Primary path: Hybrid Mount VFS (redirects kernel path and unmounts bind mount cleanly,
        // leaving 0 mount records in /proc/*/mountinfo in ALL namespaces including Android 17 zygote_next)
        if (VfsManager.isVfsReady() && AssetInstaller.fileExists(VECTOR_DEX2OAT_32)) {
            val r32 = VfsManager.addRedirect(DEX2OAT_32, VECTOR_DEX2OAT_32)
            val r64 = VfsManager.addRedirect(DEX2OAT_64, VECTOR_DEX2OAT_64)
            if (r32 && r64) {
                // Lazily unmount bind mount so it disappears from mountinfo everywhere
                RootShell.run("umount -l '$DEX2OAT_32' 2>/dev/null; umount -l '$DEX2OAT_64' 2>/dev/null")
                success = true
            }
        }

        // 2. Secondary / defense-in-depth: KernelSU kernel_umount
        if (AssetInstaller.fileExists(KSUD)) {
            val cmd = """
                '$KSUD' feature set kernel_umount 1 >/dev/null 2>&1 &&
                '$KSUD' kernel umount add '$DEX2OAT_32' >/dev/null 2>&1 &&
                '$KSUD' kernel umount add '$DEX2OAT_64' >/dev/null 2>&1
            """.trimIndent()
            val ksuRes = RootShell.run(cmd).success
            if (ksuRes) success = true
        }

        return success
    }

    fun revertVectorDex2oat(): Boolean {
        var reverted = false
        if (VfsManager.isVfsReady()) {
            VfsManager.removeRedirect(DEX2OAT_32)
            VfsManager.removeRedirect(DEX2OAT_64)
            reverted = true
        }
        if (AssetInstaller.fileExists(KSUD)) {
            val cmd = """
                '$KSUD' kernel umount del '$DEX2OAT_32' >/dev/null 2>&1 &&
                '$KSUD' kernel umount del '$DEX2OAT_64' >/dev/null 2>&1
            """.trimIndent()
            if (RootShell.run(cmd).success) reverted = true
        }
        return reverted
    }

    // -------------------------------------------------------------------------
    // Camera 50 MP (S5KJN1)
    // -------------------------------------------------------------------------

    fun checkCamera50mp(context: Context): HotfixItem {
        val rom = RomProfile.get(context).rom
        val enabled = isHotfixEnabled(context, HotfixId.CAMERA_50MP)

        if (!AssetInstaller.fileExists(CAM_KO_STOCK)) {
            return HotfixItem(
                id = HotfixId.CAMERA_50MP,
                titleRes = R.string.hotfix_camera_title,
                descRes = R.string.hotfix_camera_desc,
                requiresReboot = false,
                status = HotfixStatus.NOT_APPLICABLE,
                statusDetail = context.getString(R.string.hotfix_camera_sensor_missing),
                isEnabled = false,
                isLiveApplied = false
            )
        }

        if (rom != Rom.LINEAGE) {
            val detail = if (rom == Rom.ZINWAOS) {
                context.getString(R.string.hotfix_camera_zinwaos_coming_soon)
            } else {
                context.getString(R.string.hotfix_camera_incompatible_rom)
            }
            return HotfixItem(
                id = HotfixId.CAMERA_50MP,
                titleRes = R.string.hotfix_camera_title,
                descRes = R.string.hotfix_camera_desc,
                requiresReboot = false,
                status = HotfixStatus.INCOMPATIBLE,
                statusDetail = detail,
                isEnabled = false,
                isLiveApplied = false
            )
        }

        val isLive = VfsManager.isRedirectActive("/vendor/lib64/libmtkcam_3rdparty.mtk.so")

        return HotfixItem(
            id = HotfixId.CAMERA_50MP,
            titleRes = R.string.hotfix_camera_title,
            descRes = R.string.hotfix_camera_desc,
            requiresReboot = false,
            status = if (isLive) HotfixStatus.ACTIVE else HotfixStatus.AVAILABLE,
            statusDetail = if (isLive) context.getString(R.string.hotfix_status_active_live) else null,
            isEnabled = enabled,
            isLiveApplied = isLive
        )
    }

    fun applyCamera50mp(context: Context): Boolean {
        if (RomProfile.get(context).rom != Rom.LINEAGE) return false
        RootShell.run("mkdir -p '$CAM_DIR/lib'")
        AssetInstaller.installAsset(context, "hotfixes/camera_50mp/edits.txt", CAM_EDITS, "644")
        AssetInstaller.installAsset(context, "hotfixes/camera_50mp/imgsensor_isp6s.ko", CAM_KO_PATCHED, "644")
        AssetInstaller.installAsset(context, "hotfixes/camera_50mp/libremosaic_wrapper.so", CAM_SHIM, "644")

        // Build patched HAL libs using edits.txt if not built yet.
        // We verify the old bytes at the specified offset before patching;
        // this safely applies patches to vendor libs on both LineageOS and ZinwaOS,
        // and skips patching cameraserver on ZinwaOS (which is a 19KB launcher where Camera2 doesn't use CaptureSequencer).
        val buildScript = """
            build_lib() {
                name=${'$'}1; src=${'$'}2/${'$'}1; dst='$CAM_DIR/lib/'${'$'}1; label=${'$'}3
                [ -f "${'$'}src" ] || return 0
                [ -f "${'$'}dst" ] && return 0
                cp "${'$'}src" "${'$'}dst" || return 1
                patched=0
                while read -r n off old new; do
                    [ "${'$'}n" = "${'$'}name" ] || continue
                    byte_len=${'$'}(( ${'$'}{#old} / 2 ))
                    cur=${'$'}(dd if="${'$'}dst" bs=1 skip="${'$'}off" count="${'$'}byte_len" 2>/dev/null | xxd -p | tr -d '\n')
                    if [ "${'$'}cur" = "${'$'}old" ]; then
                        echo -n "${'$'}new" | xxd -r -p | dd of="${'$'}dst" bs=1 seek="${'$'}off" conv=notrunc 2>/dev/null
                        patched=1
                    fi
                done < '$CAM_EDITS'
                if [ "${'$'}patched" -eq 0 ]; then
                    rm -f "${'$'}dst"
                    return 0
                fi
                chmod 755 "${'$'}dst"; chcon "u:object_r:${'$'}label:s0" "${'$'}dst" 2>/dev/null
            }
            build_lib libmtkcam_metastore.so /vendor/lib64 vendor_file
            build_lib libmtkcam_3rdparty.mtk.so /vendor/lib64 vendor_file
            build_lib libmtkcam_featurepolicy.so /vendor/lib64 vendor_file
            build_lib cameraserver /system/bin cameraserver_exec
            chcon u:object_r:vendor_file:s0 '$CAM_SHIM' 2>/dev/null
        """.trimIndent()
        RootShell.run(buildScript)

        // Apply VFS redirects for vendor HAL libraries
        VfsManager.addRedirect("/vendor/lib64/libmtkcam_metastore.so", "$CAM_DIR/lib/libmtkcam_metastore.so")
        VfsManager.addRedirect("/vendor/lib64/libmtkcam_3rdparty.mtk.so", "$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so")
        VfsManager.addRedirect("/vendor/lib64/libmtkcam_featurepolicy.so", "$CAM_DIR/lib/libmtkcam_featurepolicy.so")
        VfsManager.addRedirect("/vendor/lib64/libremosaic_wrapper.so", CAM_SHIM)

        // On ZinwaOS stock, vendor libraries reside in /vendor/lib64/mt6789/
        val hasMt6789 = RootShell.run("[ -d /vendor/lib64/mt6789 ] && echo yes || echo no").outString.trim() == "yes"
        if (hasMt6789) {
            VfsManager.addRedirect("/vendor/lib64/mt6789/libmtkcam_metastore.so", "$CAM_DIR/lib/libmtkcam_metastore.so")
            VfsManager.addRedirect("/vendor/lib64/mt6789/libmtkcam_3rdparty.mtk.so", "$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so")
            VfsManager.addRedirect("/vendor/lib64/mt6789/libmtkcam_featurepolicy.so", "$CAM_DIR/lib/libmtkcam_featurepolicy.so")
            VfsManager.addRedirect("/vendor/lib64/mt6789/libremosaic_wrapper.so", CAM_SHIM)
        }

        // Only redirect cameraserver if it was actually patched
        if (AssetInstaller.fileExists("$CAM_DIR/lib/cameraserver")) {
            VfsManager.addRedirect("/system/bin/cameraserver", "$CAM_DIR/lib/cameraserver")
        }

        // Swap driver
        val swapDriverCmd = """
            stop camerahalserver 2>/dev/null
            stop cameraserver 2>/dev/null
            while pidof camerahalserver >/dev/null; do killall -9 camerahalserver 2>/dev/null; sleep 0.2; done
            while pidof cameraserver >/dev/null; do killall -9 cameraserver 2>/dev/null; sleep 0.2; done
            i=0; while [ "${'$'}(cat /sys/module/imgsensor_isp6s/refcnt 2>/dev/null)" != 0 ] && [ ${'$'}i -lt 20 ]; do sleep 0.2; i=${'$'}((i+1)); done
            rmmod imgsensor_isp6s 2>/dev/null
            insmod '$CAM_KO_PATCHED' 2>/dev/null
            for i in ${'$'}(seq 1 40); do
                [ -e /dev/seninf ] && [ -e /dev/kd_camera_hw ] && break
                sleep 0.1
            done
            sleep 0.5
            NODES="kd_camera_hw seninf seninf_n3d camera-isp camera-mem camera-dip camera-fdvt camera-rsc camera_eeprom0 camera_eeprom1 camera_eeprom2"
            for d in ${'$'}NODES; do chown system:camera /dev/${'$'}d 2>/dev/null; chmod 0660 /dev/${'$'}d 2>/dev/null; done
            { for i in ${'$'}(seq 1 10); do sleep 1; for d in kd_camera_hw seninf seninf_n3d; do chown system:camera /dev/${'$'}d 2>/dev/null; chmod 0660 /dev/${'$'}d 2>/dev/null; done; done; } &
            start camerahalserver 2>/dev/null
            sleep 1
            start cameraserver 2>/dev/null
        """.trimIndent()
        RootShell.run(swapDriverCmd)

        return VfsManager.isRedirectActive("/vendor/lib64/libmtkcam_3rdparty.mtk.so") ||
                (hasMt6789 && VfsManager.isRedirectActive("/vendor/lib64/mt6789/libmtkcam_3rdparty.mtk.so"))
    }

    fun revertCamera50mp(): Boolean {
        VfsManager.removeRedirect("/vendor/lib64/libmtkcam_metastore.so")
        VfsManager.removeRedirect("/vendor/lib64/libmtkcam_3rdparty.mtk.so")
        VfsManager.removeRedirect("/vendor/lib64/libmtkcam_featurepolicy.so")
        VfsManager.removeRedirect("/vendor/lib64/libremosaic_wrapper.so")
        VfsManager.removeRedirect("/vendor/lib64/mt6789/libmtkcam_metastore.so")
        VfsManager.removeRedirect("/vendor/lib64/mt6789/libmtkcam_3rdparty.mtk.so")
        VfsManager.removeRedirect("/vendor/lib64/mt6789/libmtkcam_featurepolicy.so")
        VfsManager.removeRedirect("/vendor/lib64/mt6789/libremosaic_wrapper.so")
        VfsManager.removeRedirect("/system/bin/cameraserver")

        val revertDriverCmd = """
            stop camerahalserver 2>/dev/null
            stop cameraserver 2>/dev/null
            while pidof camerahalserver >/dev/null; do killall -9 camerahalserver 2>/dev/null; sleep 0.2; done
            while pidof cameraserver >/dev/null; do killall -9 cameraserver 2>/dev/null; sleep 0.2; done
            i=0; while [ "${'$'}(cat /sys/module/imgsensor_isp6s/refcnt 2>/dev/null)" != 0 ] && [ ${'$'}i -lt 20 ]; do sleep 0.2; i=${'$'}((i+1)); done
            rmmod imgsensor_isp6s 2>/dev/null
            insmod '$CAM_KO_STOCK' 2>/dev/null
            for i in ${'$'}(seq 1 40); do
                [ -e /dev/seninf ] && [ -e /dev/kd_camera_hw ] && break
                sleep 0.1
            done
            sleep 0.5
            NODES="kd_camera_hw seninf seninf_n3d camera-isp camera-mem camera-dip camera-fdvt camera-rsc camera_eeprom0 camera_eeprom1 camera_eeprom2"
            for d in ${'$'}NODES; do chown system:camera /dev/${'$'}d 2>/dev/null; chmod 0660 /dev/${'$'}d 2>/dev/null; done
            { for i in ${'$'}(seq 1 10); do sleep 1; for d in kd_camera_hw seninf seninf_n3d; do chown system:camera /dev/${'$'}d 2>/dev/null; chmod 0660 /dev/${'$'}d 2>/dev/null; done; done; } &
            start camerahalserver 2>/dev/null
            sleep 1
            start cameraserver 2>/dev/null
        """.trimIndent()
        RootShell.run(revertDriverCmd)

        return !VfsManager.isRedirectActive("/vendor/lib64/libmtkcam_3rdparty.mtk.so") &&
                !VfsManager.isRedirectActive("/vendor/lib64/mt6789/libmtkcam_3rdparty.mtk.so")
    }

    // -------------------------------------------------------------------------
    // Universal Controller API
    // -------------------------------------------------------------------------

    fun getAllHotfixes(context: Context): List<HotfixItem> {
        return listOf(
            checkBtAudio(context),
            checkCamera50mp(context),
            checkKeymintTee(context),
            checkVectorDex2oat(context)
        )
    }

    fun toggleHotfix(context: Context, id: HotfixId, enable: Boolean): Boolean {
        if (id == HotfixId.CAMERA_50MP && enable && RomProfile.get(context).rom != Rom.LINEAGE) {
            setHotfixEnabled(context, id, false)
            return false
        }
        setHotfixEnabled(context, id, enable)
        val success = if (enable) {
            when (id) {
                HotfixId.BT_AUDIO -> applyBtAudio()
                HotfixId.KEYMINT_TEE -> applyKeymintTee(context)
                HotfixId.VECTOR_DEX2OAT -> applyVectorDex2oat()
                HotfixId.CAMERA_50MP -> applyCamera50mp(context)
            }
        } else {
            when (id) {
                HotfixId.BT_AUDIO -> revertBtAudio()
                HotfixId.KEYMINT_TEE -> revertKeymintTee(context)
                HotfixId.VECTOR_DEX2OAT -> revertVectorDex2oat()
                HotfixId.CAMERA_50MP -> revertCamera50mp()
            }
        }
        reconcileBootScript(context)
        return success
    }

    /**
     * Reconciles the boot script in /data/adb/service.d so that enabled hotfixes
     * are persistently and cleanly injected on every boot without standalone modules.
     */
    fun reconcileBootScript(context: Context) {
        val anyEnabled = HotfixId.entries.any { isHotfixEnabled(context, it) }
        if (!anyEnabled) {
            RootShell.run("rm -f '$BOOT_SCRIPT'")
            return
        }

        val rom = RomProfile.get(context).rom
        val btAudio = isHotfixEnabled(context, HotfixId.BT_AUDIO) && rom == Rom.LINEAGE
        val cam = isHotfixEnabled(context, HotfixId.CAMERA_50MP) && rom == Rom.LINEAGE
        val keymint = isHotfixEnabled(context, HotfixId.KEYMINT_TEE)
        val vector = isHotfixEnabled(context, HotfixId.VECTOR_DEX2OAT)

        val script = StringBuilder()
        script.appendLine("#!/system/bin/sh")
        script.appendLine("# Generated by Q25 Toolbox - System Hotfixes Boot Service")
        script.appendLine("HM=/data/adb/modules/hybrid_mount/hybrid-mount")
        script.appendLine("[ -x \"\$HM\" ] && \"\$HM\" vfs load >/dev/null 2>&1")
        script.appendLine()

        if (vector) {
            script.appendLine("# Vector dex2oat VFS redirect & unmount (hidden from all namespaces including zygote_next)")
            script.appendLine("if [ -f '$VECTOR_DEX2OAT_32' ]; then")
            script.appendLine("    if [ -x \"\$HM\" ]; then")
            script.appendLine("        \"\$HM\" vfs rule add '$DEX2OAT_32' '$VECTOR_DEX2OAT_32' >/dev/null 2>&1")
            script.appendLine("        \"\$HM\" vfs rule add '$DEX2OAT_64' '$VECTOR_DEX2OAT_64' >/dev/null 2>&1")
            script.appendLine("        umount -l '$DEX2OAT_32' 2>/dev/null")
            script.appendLine("        umount -l '$DEX2OAT_64' 2>/dev/null")
            script.appendLine("        { for i in ${'$'}(seq 1 30); do sleep 2; umount -l '$DEX2OAT_32' 2>/dev/null; umount -l '$DEX2OAT_64' 2>/dev/null; ! grep -q dex2oat /proc/mounts && break; done; } &")
            script.appendLine("    fi")
            script.appendLine("fi")
            script.appendLine("KSUD=/data/adb/ksud")
            script.appendLine("if [ -x \"\$KSUD\" ]; then")
            script.appendLine("    \"\$KSUD\" feature set kernel_umount 1 >/dev/null 2>&1")
            script.appendLine("    \"\$KSUD\" kernel umount add '$DEX2OAT_32' >/dev/null 2>&1")
            script.appendLine("    \"\$KSUD\" kernel umount add '$DEX2OAT_64' >/dev/null 2>&1")
            script.appendLine("fi")
            script.appendLine()
        }

        if (btAudio) {
            script.appendLine("# Bluetooth Audio Fix")
            script.appendLine("if [ -f '$BT_AUDIO_PATCHED' ]; then")
            script.appendLine("    if [ -x \"\$HM\" ]; then")
            script.appendLine("        \"\$HM\" vfs rule add '$BT_AUDIO_TARGET' '$BT_AUDIO_PATCHED' >/dev/null 2>&1")
            script.appendLine("    else")
            script.appendLine("        mount --bind '$BT_AUDIO_PATCHED' '$BT_AUDIO_TARGET'")
            script.appendLine("    fi")
            script.appendLine("fi")
            script.appendLine()
        }

        if (cam) {
            script.appendLine("# Camera 50 MP Fix")
            script.appendLine("if [ -f '$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so' ]; then")
            script.appendLine("    if [ -x \"\$HM\" ]; then")
            script.appendLine("        \"\$HM\" vfs rule add /vendor/lib64/libmtkcam_metastore.so '$CAM_DIR/lib/libmtkcam_metastore.so' >/dev/null 2>&1")
            script.appendLine("        \"\$HM\" vfs rule add /vendor/lib64/libmtkcam_3rdparty.mtk.so '$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so' >/dev/null 2>&1")
            script.appendLine("        \"\$HM\" vfs rule add /vendor/lib64/libmtkcam_featurepolicy.so '$CAM_DIR/lib/libmtkcam_featurepolicy.so' >/dev/null 2>&1")
            script.appendLine("        \"\$HM\" vfs rule add /vendor/lib64/libremosaic_wrapper.so '$CAM_SHIM' >/dev/null 2>&1")
            script.appendLine("        if [ -d /vendor/lib64/mt6789 ]; then")
            script.appendLine("            \"\$HM\" vfs rule add /vendor/lib64/mt6789/libmtkcam_metastore.so '$CAM_DIR/lib/libmtkcam_metastore.so' >/dev/null 2>&1")
            script.appendLine("            \"\$HM\" vfs rule add /vendor/lib64/mt6789/libmtkcam_3rdparty.mtk.so '$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so' >/dev/null 2>&1")
            script.appendLine("            \"\$HM\" vfs rule add /vendor/lib64/mt6789/libmtkcam_featurepolicy.so '$CAM_DIR/lib/libmtkcam_featurepolicy.so' >/dev/null 2>&1")
            script.appendLine("            \"\$HM\" vfs rule add /vendor/lib64/mt6789/libremosaic_wrapper.so '$CAM_SHIM' >/dev/null 2>&1")
            script.appendLine("        fi")
            script.appendLine("        [ -f '$CAM_DIR/lib/cameraserver' ] && \"\$HM\" vfs rule add /system/bin/cameraserver '$CAM_DIR/lib/cameraserver' >/dev/null 2>&1")
            script.appendLine("    else")
            script.appendLine("        mount --bind '$CAM_DIR/lib/libmtkcam_metastore.so' /vendor/lib64/libmtkcam_metastore.so")
            script.appendLine("        mount --bind '$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so' /vendor/lib64/libmtkcam_3rdparty.mtk.so")
            script.appendLine("        mount --bind '$CAM_DIR/lib/libmtkcam_featurepolicy.so' /vendor/lib64/libmtkcam_featurepolicy.so")
            script.appendLine("        mount --bind '$CAM_SHIM' /vendor/lib64/libremosaic_wrapper.so")
            script.appendLine("        if [ -d /vendor/lib64/mt6789 ]; then")
            script.appendLine("            mount --bind '$CAM_DIR/lib/libmtkcam_metastore.so' /vendor/lib64/mt6789/libmtkcam_metastore.so")
            script.appendLine("            mount --bind '$CAM_DIR/lib/libmtkcam_3rdparty.mtk.so' /vendor/lib64/mt6789/libmtkcam_3rdparty.mtk.so")
            script.appendLine("            mount --bind '$CAM_DIR/lib/libmtkcam_featurepolicy.so' /vendor/lib64/mt6789/libmtkcam_featurepolicy.so")
            script.appendLine("            mount --bind '$CAM_SHIM' /vendor/lib64/mt6789/libremosaic_wrapper.so")
            script.appendLine("        fi")
            script.appendLine("        [ -f '$CAM_DIR/lib/cameraserver' ] && mount --bind '$CAM_DIR/lib/cameraserver' /system/bin/cameraserver")
            script.appendLine("    fi")
            script.appendLine("    stop camerahalserver 2>/dev/null")
            script.appendLine("    stop cameraserver 2>/dev/null")
            script.appendLine("    while pidof camerahalserver >/dev/null; do killall -9 camerahalserver 2>/dev/null; sleep 0.2; done")
            script.appendLine("    while pidof cameraserver >/dev/null; do killall -9 cameraserver 2>/dev/null; sleep 0.2; done")
            script.appendLine("    i=0; while [ \"\$(cat /sys/module/imgsensor_isp6s/refcnt 2>/dev/null)\" != 0 ] && [ \$i -lt 20 ]; do sleep 0.2; i=\$((i+1)); done")
            script.appendLine("    rmmod imgsensor_isp6s 2>/dev/null")
            script.appendLine("    insmod '$CAM_KO_PATCHED' 2>/dev/null")
            script.appendLine("    for i in \$(seq 1 40); do")
            script.appendLine("        [ -e /dev/seninf ] && [ -e /dev/kd_camera_hw ] && break")
            script.appendLine("        sleep 0.1")
            script.appendLine("    done")
            script.appendLine("    sleep 0.5")
            script.appendLine("    NODES=\"kd_camera_hw seninf seninf_n3d camera-isp camera-mem camera-dip camera-fdvt camera-rsc camera_eeprom0 camera_eeprom1 camera_eeprom2\"")
            script.appendLine("    for d in \$NODES; do chown system:camera /dev/\$d 2>/dev/null; chmod 0660 /dev/\$d 2>/dev/null; done")
            script.appendLine("    { for i in \$(seq 1 10); do sleep 1; for d in kd_camera_hw seninf seninf_n3d; do chown system:camera /dev/\$d 2>/dev/null; chmod 0660 /dev/\$d 2>/dev/null; done; done; } &")
            script.appendLine("    start camerahalserver 2>/dev/null")
            script.appendLine("    sleep 1")
            script.appendLine("    start cameraserver 2>/dev/null")
            script.appendLine("fi")
            script.appendLine()
        }

        if (keymint) {
            val teePath = getAlwaysStrongTeePath()
            script.appendLine("# AlwaysStrong / KeyMint Software Import")
            script.appendLine("if [ -f '$TEE_PATCHED' ] && [ -f '$teePath' ]; then")
            script.appendLine("    cur=\$(md5sum '$teePath' 2>/dev/null | cut -d' ' -f1)")
            script.appendLine("    if [ \"\$cur\" != '$TEE_PATCHED_MD5' ]; then")
            script.appendLine("        cp '$TEE_PATCHED' '$teePath'")
            script.appendLine("        chmod 644 '$teePath'")
            script.appendLine("        killall TEESimulator 2>/dev/null")
            script.appendLine("    fi")
            script.appendLine("fi")
            script.appendLine()
        }

        val tmpFile = File(context.filesDir, "q25_hotfixes_boot.sh")
        tmpFile.writeText(script.toString())
        RootShell.run("mkdir -p /data/adb/service.d && cp '${tmpFile.absolutePath}' '$BOOT_SCRIPT' && chmod 755 '$BOOT_SCRIPT'")
    }
}
