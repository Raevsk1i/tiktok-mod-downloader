package com.tiktok.regionpatcher.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * Signs patched APKs with a persistent key in AndroidKeyStore.
 */
object ApkSignerHelper {

    private const val KEY_ALIAS = "tiktok_region_patch_key"

    fun sign(input: File, output: File) {
        val (privateKey, certs) = loadOrCreateKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(KEY_ALIAS, privateKey, certs).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    fun signAll(inputs: List<File>, outputDir: File): List<File> {
        outputDir.mkdirs()
        return inputs.map { input ->
            val out = File(outputDir, signedName(input.name))
            sign(input, out)
            out
        }
    }

    private fun signedName(original: String): String {
        val base = original.removeSuffix(".apk")
        return "${base}-patched-signed.apk"
    }

    private fun loadOrCreateKey(): Pair<java.security.PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(KEY_ALIAS)) {
            createKey()
        }
        val entry = ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val cert = entry.certificate as X509Certificate
        return entry.privateKey to listOf(cert)
    }

    private fun createKey() {
        val start = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.time
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=TikTok Region Patcher"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(start)
            .setCertificateNotAfter(end)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(spec)
        }.generateKeyPair()
    }
}
