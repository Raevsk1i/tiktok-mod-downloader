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
 *
 * Critical: [AndroidManifest.xml], [resources.arsc] and dex files must keep their
 * original STORED/DEFLATED method. Re-compressing STORED entries (especially
 * resources.arsc) breaks APK parsing at install time (error ~-124).
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
        val workTmp = File(input.parentFile, "apk_patch_tmp").apply {
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
                            val tmpIn = File(workTmp, "in_$name")
                            val tmpOut = File(workTmp, "out_$name")
                            zip.getInputStream(entry).use { stream ->
                                FileOutputStream(tmpIn).use { out -> stream.copyTo(out) }
                            }
                            DexTelephonyPatcher.patchDexFile(
                                tmpIn,
                                tmpOut,
                                region,
                                report,
                                onProgress,
                                isCancelled,
                            )
                            writeFileEntry(zos, name, tmpOut, entry)
                            tmpIn.delete()
                            tmpOut.delete()
                        } else {
                            copyEntryPreservingCompression(zip, entry, zos, workTmp)
                        }
                    }
                }
            }
        } finally {
            workTmp.deleteRecursively()
        }
        return report
    }

    /** Copy a zip entry keeping STORED vs DEFLATED exactly as in the source APK. */
    private fun copyEntryPreservingCompression(
        zip: ZipFile,
        entry: ZipEntry,
        zos: ZipOutputStream,
        tmpDir: File,
    ) {
        if (entry.method == ZipEntry.STORED) {
            copyStoredEntry(zip, entry, zos, tmpDir)
        } else {
            val newEntry = ZipEntry(entry.name)
            newEntry.method = ZipEntry.DEFLATED
            newEntry.time = entry.time
            zos.putNextEntry(newEntry)
            zip.getInputStream(entry).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun copyStoredEntry(
        zip: ZipFile,
        entry: ZipEntry,
        zos: ZipOutputStream,
        tmpDir: File,
    ) {
        val tmp = File(tmpDir, "st_${entry.name.hashCode() and 0x7FFFFFFF}")
        val crc = CRC32()
        var size = 0L
        zip.getInputStream(entry).use { input ->
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    crc.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    size += read
                }
            }
        }
        val newEntry = ZipEntry(entry.name)
        newEntry.method = ZipEntry.STORED
        newEntry.size = size
        newEntry.compressedSize = size
        newEntry.crc = crc.value
        newEntry.time = entry.time
        zos.putNextEntry(newEntry)
        FileInputStream(tmp).use { it.copyTo(zos) }
        zos.closeEntry()
        tmp.delete()
    }

    private fun writeFileEntry(zos: ZipOutputStream, name: String, file: File, source: ZipEntry) {
        if (source.method == ZipEntry.STORED) {
            writeStoredFromFile(zos, name, file, source.time)
        } else {
            val newEntry = ZipEntry(name)
            newEntry.method = ZipEntry.DEFLATED
            newEntry.time = source.time
            zos.putNextEntry(newEntry)
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun writeStoredFromFile(zos: ZipOutputStream, name: String, file: File, time: Long) {
        val data = file.readBytes()
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
        entry.time = time
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }
}

class PatchCancelledException : RuntimeException("Патч отменён")
