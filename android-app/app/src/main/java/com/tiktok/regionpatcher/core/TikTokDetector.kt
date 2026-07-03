package com.tiktok.regionpatcher.core

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

data class TikTokInstall(
    val packageName: String,
    val label: String,
    val apkFiles: List<File>,
)

object TikTokDetector {

    val PACKAGE_CANDIDATES = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.ss.android.ugc.aweme",
    )

    fun findInstalled(context: Context): TikTokInstall? {
        val pm = context.packageManager
        for (pkg in PACKAGE_CANDIDATES) {
            val install = tryGetInstall(pm, pkg) ?: continue
            return install
        }
        return null
    }

    private fun tryGetInstall(pm: PackageManager, packageName: String): TikTokInstall? {
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            val paths = mutableListOf(File(info.sourceDir))
            info.splitSourceDirs?.forEach { paths.add(File(it)) }
            val label = pm.getApplicationLabel(info).toString()
            TikTokInstall(packageName, label, paths)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
