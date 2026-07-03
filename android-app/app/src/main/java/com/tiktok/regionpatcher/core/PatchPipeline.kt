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
import java.util.zip.ZipFile
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
        isCancelled: (() -> Boolean)? = null,
    ): Result {
        onProgress("Копирую APK TikTok…")
        val copied = copyApks(install.apkFiles, File(workDir, "input"))
        return patchApkSet(copied, workDir, onProgress, install.packageName, isCancelled)
    }

    fun patchFromUserApk(
        source: File,
        workDir: File,
        onProgress: (String) -> Unit,
        isCancelled: (() -> Boolean)? = null,
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
        return patchApkSet(copied, workDir, onProgress, null, isCancelled)
    }

    private fun patchApkSet(
        apks: List<File>,
        workDir: File,
        onProgress: (String) -> Unit,
        packageName: String?,
        isCancelled: (() -> Boolean)?,
    ): Result {
        val region = RegionPreferences.load(context)
        val report = PatchReport()
        val patchedDir = File(workDir, "patched_raw")
        patchedDir.mkdirs()

        val toSign = mutableListOf<File>()
        for (apk in apks) {
            if (isCancelled?.invoke() == true) throw PatchCancelledException()
            val out = File(patchedDir, apk.name)
            if (hasDex(apk)) {
                onProgress("Патчу ${apk.name} (может занять несколько минут)…")
                val apkReport = ApkDexPatcher.patchApk(apk, out, region, onProgress, isCancelled)
                report.scannedDexFiles += apkReport.scannedDexFiles
                report.patchedSites += apkReport.patchedSites
                report.details.addAll(apkReport.details)
            } else {
                onProgress("Копирую ${apk.name}…")
                copyFile(apk, out)
            }
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
        val signed = ApkSignerHelper.signAll(context, toSign, signedDir)

        onProgress("Проверяю APK перед установкой…")
        ApkValidator.validateAll(context, signed)

        onProgress("Готово к установке")
        return Result(report, signed, packageName)
    }

    fun installApks(signedApks: List<File>, packageName: String?) {
        val ordered = signedApks.sortedWith(compareBy<File> {
            !it.name.equals("base.apk", ignoreCase = true)
        }.thenBy { it.name })
        if (ordered.size == 1) {
            installSingle(ordered.first())
            return
        }
        installMultiple(ordered, packageName)
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

    private fun installMultiple(apks: List<File>, packageName: String?) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        packageName?.let { params.setAppPackageName(it) }
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
        } finally {
            session.close()
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
        val lower = file.name.lowercase()
        if (!lower.endsWith(".xapk") && !lower.endsWith(".apks") &&
            !lower.endsWith(".apkm") && !lower.endsWith(".zip")
        ) {
            return false
        }
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase().endsWith(".apk")) return true
                    entry = zis.nextEntry
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractBundle(bundle: File, dest: File): List<File> {
        dest.mkdirs()
        val apks = linkedMapOf<String, File>()
        ZipInputStream(FileInputStream(bundle)).use { zis ->
            var entry = zis.nextEntry
            var index = 0
            while (entry != null) {
                val rawName = entry.name.replace('\\', '/')
                if (rawName.contains("..")) {
                    entry = zis.nextEntry
                    continue
                }
                if (rawName.lowercase().endsWith(".apk")) {
                    val baseName = File(rawName).name.ifBlank { "part$index.apk" }
                    val uniqueName = if (apks.containsKey(baseName)) {
                        "${index}_$baseName"
                    } else {
                        baseName
                    }
                    val out = File(dest, uniqueName)
                    FileOutputStream(out).use { output -> zis.copyTo(output) }
                    apks[uniqueName] = out
                    index++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (apks.isEmpty()) throw IllegalStateException("В архиве нет APK")
        return apks.values.toList()
    }

    private fun pickBase(apks: List<File>): File {
        apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.name.lowercase().startsWith("base") }?.let { return it }
        return apks.firstOrNull { hasDex(it) }
            ?: apks.maxByOrNull { apkSize(it) }
            ?: apks.first()
    }

    private fun hasDex(apk: File): Boolean {
        return try {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().any { it.name.matches(Regex("^classes\\d*\\.dex$")) }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun apkSize(apk: File): Long {
        return try {
            var dexSize = 0L
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.name.matches(Regex("^classes\\d*\\.dex$"))) {
                        val size = entry.size
                        if (size > 0) dexSize += size
                    }
                }
            }
            if (dexSize > 0) dexSize else apk.length()
        } catch (_: Exception) {
            apk.length()
        }
    }
}
