package com.tiktok.regionpatcher.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Patches dex entries inside an APK zip without a full apktool decode/rebuild.
 */
object ApkDexPatcher {

  private val DEX_PATTERN = Regex("^classes\\d*\\.dex$")

  fun patchApk(input: File, output: File, region: RegionConfig): PatchReport {
    val report = PatchReport()
    ZipInputStream(BufferedInputStream(FileInputStream(input))).use { zis ->
      ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zos ->
        var entry = zis.nextEntry
        while (entry != null) {
          val name = entry.name
          if (name.startsWith("META-INF/")) {
            // Drop old signatures; APK will be re-signed.
            entry = zis.nextEntry
            continue
          }
          val data = zis.readBytes()
          val outData = if (DEX_PATTERN.matches(name)) {
            report.scannedDexFiles++
            DexTelephonyPatcher.patchDexBytes(data, region, report)
          } else {
            data
          }
          writeEntry(zos, name, outData, entry.method)
          zis.closeEntry()
          entry = zis.nextEntry
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
