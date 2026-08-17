package com.kgr.q25toolbox.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signs a realigned APK with v2/v3 (mandatory for anything targeting API 30+ -
 * see RecentsTweaksController.repairRecentsProvider) using a throwaway key that
 * lives in AndroidKeyStore and never leaves it. This is safe specifically
 * *because* TARGET_APK is installed via priv-app folder placement, not `pm
 * install` - permission grants there are folder-based (privapp-permissions
 * allowlist), not signature-based, so who signed the apk doesn't matter, only
 * that it's validly signed at all.
 *
 * AndroidKeyStore key generation attaches a self-signed X.509 cert to the key
 * automatically - no need to hand-build one (which on stock Android, without
 * Bouncy Castle, isn't otherwise possible).
 */
object OnDeviceApkSigner {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "q25toolbox_recents_repair_signer"

    private fun getOrCreateKeyEntry(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val notBefore = Date()
            val notAfter = Date(notBefore.time + 100L * 365 * 24 * 60 * 60 * 1000)
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=q25toolbox Recents Repair"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
                .apply { initialize(spec) }
                .generateKeyPair()
        }
        return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    /** Returns true on success; [output] is left untouched (not partially written) on failure. */
    fun sign(input: File, output: File): Boolean = try {
        val entry = getOrCreateKeyEntry()
        val cert = entry.certificate as X509Certificate
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "q25toolbox", entry.privateKey, listOf(cert)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(28)
            .build()
            .sign()
        true
    } catch (e: Exception) {
        output.delete()
        false
    }
}
