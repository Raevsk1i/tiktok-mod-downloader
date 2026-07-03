package com.tiktok.regionpatcher.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 4-byte zipalign for APK entries (required for resources.arsc on Android 11+).
 *
 * Error -124 ("resources.arsc must be stored uncompressed and aligned on a
 * 4-byte boundary") happens when [ZipOutputStream] rebuilds an APK without
 * padding local headers. [com.android.apksig.ApkSigner] does not fix this.
 */
object ZipAligner {

    private const val LOCAL_HEADER_SIZE = 30
    private const val ALIGNMENT = 4

    fun align(input: File, output: File) {
        val entries = mutableListOf<EntryPayload>()
        ZipFile(input).use { zip ->
            val list = zip.entries().asSequence().toList()
            for (entry in list) {
                val data = zip.getInputStream(entry).use { it.readBytes() }
                entries.add(
                    EntryPayload(
                        name = entry.name,
                        method = entry.method,
                        time = entry.time,
                        extra = entry.extra ?: ByteArray(0),
                        data = data,
                    ),
                )
            }
        }

        val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(output)))
        ZipOutputStream(counter).use { zos ->
            for (payload in entries) {
                writeAlignedEntry(zos, counter, payload)
            }
        }
    }

    private fun writeAlignedEntry(
        zos: ZipOutputStream,
        counter: CountingOutputStream,
        payload: EntryPayload,
    ) {
        val nameBytes = payload.name.toByteArray(Charsets.UTF_8)
        var extra = payload.extra

        if (payload.method == ZipEntry.STORED && needsAlignment(payload.name)) {
            val headerOffset = counter.byteCount
            val dataOffset = headerOffset + LOCAL_HEADER_SIZE + nameBytes.size + extra.size
            val padding = ((ALIGNMENT - (dataOffset % ALIGNMENT)) % ALIGNMENT).toInt()
            if (padding > 0) {
                extra = extra + ByteArray(padding)
            }
        }

        val entry = ZipEntry(payload.name)
        entry.method = payload.method
        entry.time = payload.time
        entry.extra = extra

        if (payload.method == ZipEntry.STORED) {
            entry.size = payload.data.size.toLong()
            entry.compressedSize = payload.data.size.toLong()
            val crc = CRC32()
            crc.update(payload.data)
            entry.crc = crc.value
        }

        zos.putNextEntry(entry)
        zos.write(payload.data)
        zos.closeEntry()
    }

    private fun needsAlignment(name: String): Boolean {
        if (name == "resources.arsc" || name == "AndroidManifest.xml") return true
        if (name.endsWith(".dex")) return true
        if (name.endsWith(".so")) return true
        return false
    }

    private data class EntryPayload(
        val name: String,
        val method: Int,
        val time: Long,
        val extra: ByteArray,
        val data: ByteArray,
    )

    private class CountingOutputStream(out: OutputStream) : java.io.FilterOutputStream(out) {
        var byteCount: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            byteCount++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            byteCount += len
        }
    }
}
