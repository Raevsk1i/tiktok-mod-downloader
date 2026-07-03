package com.tiktok.regionpatcher.core

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Signs patched APKs with a PKCS12 key bundled in assets.
 *
 * AndroidKeyStore keys often lack SHA-1 digest support required for APK v1
 * signing, which causes apksig to fail with "Failed to sign using signer …".
 * A standard software RSA key in PKCS12 works on all devices.
 */
object ApkSignerHelper {

    private const val KEYSTORE_ASSET = "patch.keystore"
    private const val KEYSTORE_FILE = "patch-signing.p12"
    private const val KEY_ALIAS = "patch"
    private const val STORE_PASS = "patchpass"
    private const val KEY_PASS = "patchpass"

    fun sign(context: Context, input: File, output: File) {
        val (privateKey, certs) = loadKey(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(KEY_ALIAS, privateKey, certs).build()
        try {
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(21)
                .build()
                .sign()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Не удалось подписать ${input.name}: ${e.message}",
                e,
            )
        }
    }

    fun signAll(context: Context, inputs: List<File>, outputDir: File): List<File> {
        outputDir.mkdirs()
        return inputs.map { input ->
            val out = File(outputDir, input.name)
            if (out.exists()) out.delete()
            sign(context, input, out)
            out
        }
    }

    private fun loadKey(context: Context): Pair<java.security.PrivateKey, List<X509Certificate>> {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        if (!ksFile.exists()) {
            copyKeystoreFromAssets(context, ksFile)
        }
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { stream ->
            ks.load(stream, STORE_PASS.toCharArray())
        }
        val entry = ks.getEntry(
            KEY_ALIAS,
            KeyStore.PasswordProtection(KEY_PASS.toCharArray()),
        ) as KeyStore.PrivateKeyEntry
        val cert = entry.certificate as X509Certificate
        return entry.privateKey to listOf(cert)
    }

    private fun copyKeystoreFromAssets(context: Context, dest: File) {
        try {
            context.assets.open(KEYSTORE_ASSET).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Не удалось загрузить ключ подписи из assets: ${e.message}",
                e,
            )
        }
    }
}
