package com.tiktok.regionpatcher.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Patches dex entries inside an APK zip without a full apktool decode/rebuild.
 * Non-dex entries are streamed (not loaded into RAM).
 */
object ApkDexPatcher {

    private val DEX_PATTERN = Regex("^classes\\d*\\.dex$")

    fun patchApk(
        input: File,
        output: File,
        region: RegionConfig,
        onProgress: ((String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
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
        val dexTempDir = File(input.parentFile, "dex_tmp").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            ZipFile(input).use { zip ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        if (isCancelled?.invoke() == true) {
                            throw PatchCancelledException()
                        }
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (name.startsWith("META-INF/")) {
                            continue
                        }

                        if (DEX_PATTERN.matches(name)) {
                            dexIndex++
                            report.scannedDexFiles++
                            onProgress?.invoke("DEX $dexIndex/$totalDex: $name…")
                            val tmpIn = File(dexTempDir, "in_$name")
                            val tmpOut = File(dexTempDir, "out_$name")
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(tmpIn).use { out -> input.copyTo(out) }
                            }
                            DexTelephonyPatcher.patchDexFile(
                                tmpIn,
                                tmpOut,
                                region,
                                report,
                                onProgress,
                                isCancelled,
                            )
                            writeFileEntry(zos, name, tmpOut, entry.method)
                            tmpIn.delete()
                            tmpOut.delete()
                        } else {
                            zip.getInputStream(entry).use { stream ->
                                copyStreamEntry(zos, name, stream)
                            }
                        }
                    }
                }
            }
        } finally {
            dexTempDir.deleteRecursively()
        }
        return report
    }

    private fun copyStreamEntry(zos: ZipOutputStream, name: String, input: InputStream) {
        zos.putNextEntry(ZipEntry(name))
        input.copyTo(zos)
        zos.closeEntry()
    }

    private fun writeFileEntry(zos: ZipOutputStream, name: String, file: File, method: Int) {
        if (method == ZipEntry.STORED) {
            val data = file.readBytes()
            writeStoredEntry(zos, name, data)
        } else {
            zos.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
        entry.method = ZipEntry.STORED
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }
}

class PatchCancelledException : RuntimeException("Патч отменён")
