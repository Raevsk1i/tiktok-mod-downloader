package com.tiktok.regionpatcher.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object PackageInstallHelper {

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun resolvePackageName(context: Context, signedApks: List<File>, hint: String?): String? {
        if (!hint.isNullOrBlank()) return hint
        val base = signedApks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: signedApks.firstOrNull()
            ?: return null
        val flags = PackageManager.GET_META_DATA
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                base.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(base.absolutePath, flags)
        }
        return info?.packageName
    }

    fun needsUninstallFirst(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return isInstalled(context, packageName)
    }
}
