package com.tiktok.regionpatcher.core

/**
 * Literal values injected into patched TelephonyManager call-sites.
 * Defaults spoof a German SIM/network (de / MCC 262).
 */
data class RegionConfig(
    val countryIso: String = "de",
    val operator: String = "26201",
    val operatorName: String = "Telekom.de",
) {
    fun valueForMethod(method: String): String = when (method) {
        "getNetworkCountryIso", "getSimCountryIso" -> countryIso
        "getNetworkOperator", "getSimOperator" -> operator
        "getNetworkOperatorName", "getSimOperatorName" -> operatorName
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    companion object {
        val PATCHABLE_METHODS = setOf(
            "getNetworkCountryIso",
            "getSimCountryIso",
            "getNetworkOperator",
            "getSimOperator",
            "getNetworkOperatorName",
            "getSimOperatorName",
        )
    }
}

data class PatchReport(
    var scannedDexFiles: Int = 0,
    var patchedSites: Int = 0,
    val details: MutableList<String> = mutableListOf(),
) {
    fun summary(): String =
        "Пропатчено вызовов: $patchedSites (в $scannedDexFiles dex-файлах)"
}
