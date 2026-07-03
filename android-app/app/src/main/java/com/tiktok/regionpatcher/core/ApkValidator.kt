package com.tiktok.regionpatcher.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object ApkValidator {

    /** Returns an error message if the APK cannot be parsed by the system. */
    fun validate(context: Context, apk: File): String? {
        val flags = PackageManager.GET_META_DATA
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: return "Система не может прочитать ${apk.name} (битый APK)"
        if (info.packageName.isNullOrBlank()) {
            return "В ${apk.name} не найден package name"
        }
        return null
    }

    fun validateAll(context: Context, apks: List<File>) {
        // Validate base APK (required); splits are copied from a working install set.
        val base = apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) } ?: apks.first()
        validate(context, base)?.let { err ->
            throw IllegalStateException("Проверка APK не пройдена: $err")
        }
    }
}
