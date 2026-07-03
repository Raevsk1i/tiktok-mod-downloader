package com.tiktok.regionpatcher.core

/**
 * Maps Android package installer errors to readable Russian messages.
 */
object InstallErrorMapper {

    fun translate(raw: String?): String {
        if (raw.isNullOrBlank()) return "Неизвестная ошибка установки"
        val lower = raw.lowercase()
        return when {
            lower.contains("update_incompatible") ||
                lower.contains("signatures do not match") ||
                (lower.contains("install_failed_update") && lower.contains("incompatible")) ->
                "Подпись пропатченного APK не совпадает с установленным TikTok.\n\n" +
                    "Сначала полностью удалите оригинальный TikTok (кнопка в приложении), " +
                    "затем установите патч снова — не поверх старой версии."

            lower.contains("version_downgrade") ->
                "Версия пропатченного APK ниже установленной.\n\n" +
                    "Удалите TikTok и установите патч заново."

            lower.contains("already_exists") ->
                "Приложение с таким именем уже установлено с другой подписью.\n\n" +
                    "Удалите TikTok полностью и повторите установку."

            lower.contains("insufficient_storage") ->
                "Недостаточно места на устройстве. Освободите память и повторите."

            lower.contains("invalid_apk") || lower.contains("failed parse") ->
                "APK повреждён или несовместим с устройством.\n\n" +
                    "Попробуйте пересобрать патч или обновите TikTok Region Patcher."

            lower.contains("resources_arsc") ->
                "Ошибка resources.arsc (код -124). Обновите TikTok Region Patcher до последней версии."

            else -> raw
        }
    }
}
