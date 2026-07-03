package com.tiktok.regionpatcher.core

data class RegionPreset(
    val id: String,
    val displayName: String,
    val countryIso: String,
    val operator: String,
    val operatorName: String,
) {
    fun toConfig(): RegionConfig = RegionConfig(
        countryIso = countryIso,
        operator = operator,
        operatorName = operatorName,
    )
}

object RegionPresets {

    val defaultId: String = "de"

    val all: List<RegionPreset> = listOf(
        RegionPreset("de", "Германия", "de", "26201", "Telekom.de"),
        RegionPreset("us", "США", "us", "310260", "T-Mobile"),
        RegionPreset("gb", "Великобритания", "gb", "23415", "Vodafone"),
        RegionPreset("fr", "Франция", "fr", "20801", "Orange"),
        RegionPreset("nl", "Нидерланды", "nl", "20404", "Vodafone"),
        RegionPreset("pl", "Польша", "pl", "26001", "Plus"),
        RegionPreset("tr", "Турция", "tr", "28601", "Turkcell"),
        RegionPreset("ae", "ОАЭ", "ae", "42402", "Etisalat"),
        RegionPreset("kz", "Казахстан", "kz", "40101", "Beeline"),
        RegionPreset("ua", "Украина", "ua", "25501", "Vodafone"),
        RegionPreset("jp", "Япония", "jp", "44010", "NTT docomo"),
        RegionPreset("kr", "Южная Корея", "kr", "45005", "SKTelecom"),
    )

    fun byId(id: String): RegionPreset? = all.firstOrNull { it.id == id }

    fun default(): RegionPreset = byId(defaultId) ?: all.first()
}
