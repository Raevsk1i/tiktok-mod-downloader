package com.tiktok.regionpatcher.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Patches dex entries inside an APK zip without a full apktool decode/rebuild.
 */
object ApkDexPatcher {

    private val DEX_PATTERN = Regex("^classes\\d*\\.dex$")

    fun patchApk(
        input: File,
        output: File,
        region: RegionConfig,
        onProgress: ((String) -> Unit)? = null,
    ): PatchReport {
        val report = PatchReport()
        val dexNames = mutableListOf<String>()

        ZipFile(input).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { DEX_PATTERN.matches(it) }
                .forEach { dexNames.add(it) }
        }

        val totalDex = dexNames.size.coerceAtLeast(1)
        var dexIndex = 0

        ZipFile(input).use { zip ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.startsWith("META-INF/")) {
                        continue
                    }

                    val outData: ByteArray
                    if (DEX_PATTERN.matches(name)) {
                        dexIndex++
                        report.scannedDexFiles++
                        onProgress?.invoke("DEX $dexIndex/$totalDex: $name…")
                        val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                        outData = DexTelephonyPatcher.patchDexBytes(
                            dexBytes,
                            region,
                            report,
                            name,
                        ) { detail ->
                            onProgress?.invoke("DEX $dexIndex/$totalDex: $detail")
                        }
                    } else {
                        outData = zip.getInputStream(entry).use { it.readBytes() }
                    }

                    writeEntry(zos, name, outData, entry.method)
                }
            }
        }
        return report
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, data: ByteArray, method: Int) {
        val entry = ZipEntry(name)
        if (method == ZipEntry.STORED) {
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = CRC32()
            crc.update(data)
            entry.crc = crc.value
            entry.method = ZipEntry.STORED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }
}
