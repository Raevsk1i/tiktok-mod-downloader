package com.tiktok.regionpatcher.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.tiktok.regionpatcher.InstallResultReceiver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Full on-device pipeline: copy APKs -> patch dex -> sign -> install.
 */
class PatchPipeline(private val context: Context) {

    data class Result(
        val report: PatchReport,
        val signedApks: List<File>,
        val packageName: String?,
    )

    fun patchInstalledTikTok(
        install: TikTokInstall,
        workDir: File,
        onProgress: (String) -> Unit,
    ): Result {
        onProgress("Копирую APK TikTok…")
        val copied = copyApks(install.apkFiles, File(workDir, "input"))
        return patchApkSet(copied, workDir, onProgress, install.packageName)
    }

    fun patchFromUserApk(
        source: File,
        workDir: File,
        onProgress: (String) -> Unit,
    ): Result {
        onProgress("Разбираю выбранный файл…")
        val apks = if (isBundle(source)) {
            extractBundle(source, File(workDir, "bundle"))
        } else {
            listOf(source)
        }
        val base = pickBase(apks)
        val splits = apks.filter { it != base }
        onProgress("Найден base: ${base.name}, split: ${splits.size}")
        val inputDir = File(workDir, "input")
        inputDir.mkdirs()
        val copied = mutableListOf<File>()
        copied.add(copyFile(base, File(inputDir, base.name)))
        splits.forEach { split ->
            copied.add(copyFile(split, File(inputDir, split.name)))
        }
        return patchApkSet(copied, workDir, onProgress, null)
    }

    private fun patchApkSet(
        apks: List<File>,
        workDir: File,
        onProgress: (String) -> Unit,
        packageName: String?,
    ): Result {
        val region = RegionConfig()
        val report = PatchReport()
        val patchedDir = File(workDir, "patched_raw")
        patchedDir.mkdirs()

        val baseApk = apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: apks.firstOrNull { hasDex(it) }
            ?: apks.maxByOrNull { apkSize(it) }
            ?: apks.first()
        val others = apks.filter { it != baseApk }

        onProgress("Патчу dex в ${baseApk.name}…")
        val patchedBase = File(patchedDir, baseApk.name)
        val baseReport = ApkDexPatcher.patchApk(baseApk, patchedBase, region)
        report.scannedDexFiles += baseReport.scannedDexFiles
        report.patchedSites += baseReport.patchedSites
        report.details.addAll(baseReport.details)

        val toSign = mutableListOf(patchedBase)
        others.forEach { split ->
            onProgress("Копирую split ${split.name}…")
            val out = File(patchedDir, split.name)
            copyFile(split, out)
            toSign.add(out)
        }

        if (report.patchedSites == 0) {
            throw IllegalStateException(
                "Не найдено вызовов TelephonyManager. TikTok может определять регион иначе.",
            )
        }

        onProgress("Подписываю ${toSign.size} APK…")
        val signedDir = File(context.filesDir, "patched")
        signedDir.deleteRecursively()
        val signed = ApkSignerHelper.signAll(toSign, signedDir)

        onProgress("Готово к установке")
        return Result(report, signed, packageName)
    }

    fun installApks(signedApks: List<File>) {
        if (signedApks.size == 1) {
            installSingle(signedApks.first())
            return
        }
        installMultiple(signedApks)
    }

    private fun installSingle(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun installMultiple(apks: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apks.forEach { apk ->
                FileInputStream(apk).use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private fun copyApks(sources: List<File>, dir: File): List<File> {
        dir.mkdirs()
        return sources.map { copyFile(it, File(dir, it.name)) }
    }

    private fun copyFile(src: File, dst: File): File {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
        return dst
    }

    private fun isBundle(file: File): Boolean {
        if (!file.name.lowercase().let { it.endsWith(".xapk") || it.endsWith(".apks") || it.endsWith(".apkm") || it.endsWith(".zip") }) {
            return false
        }
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var hasApk = false
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase().endsWith(".apk")) {
                        hasApk = true
                        break
                    }
                    entry = zis.nextEntry
                }
                hasApk
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractBundle(bundle: File, dest: File): List<File> {
        dest.mkdirs()
        val apks = mutableListOf<File>()
        ZipInputStream(FileInputStream(bundle)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.lowercase().endsWith(".apk")) {
                    val out = File(dest, File(entry.name).name)
                    FileOutputStream(out).use { output -> zis.copyTo(output) }
                    apks.add(out)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (apks.isEmpty()) throw IllegalStateException("В архиве нет APK")
        return apks
    }

    private fun pickBase(apks: List<File>): File {
        apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.name.lowercase().startsWith("base") }?.let { return it }
        return apks.maxByOrNull { apkSize(it) } ?: apks.first()
    }

    private fun hasDex(apk: File): Boolean {
        return try {
            ZipInputStream(FileInputStream(apk)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.matches(Regex("^classes\\d*\\.dex$"))) return true
                    entry = zis.nextEntry
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun apkSize(apk: File): Long {
        return try {
            var dexSize = 0L
            ZipInputStream(FileInputStream(apk)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.matches(Regex("^classes\\d*\\.dex$"))) {
                        dexSize += entry.size.coerceAtLeast(0)
                    }
                    entry = zis.nextEntry
                }
            }
            if (dexSize > 0) dexSize else apk.length()
        } catch (_: Exception) {
            apk.length()
        }
    }
}
