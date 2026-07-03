"""Configuration objects and defaults for the patcher pipeline."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

# Pinned tool versions. Bump these to pick up newer releases.
APKTOOL_VERSION = "2.9.3"
APKTOOL_URL = (
    "https://github.com/iBotPeaches/Apktool/releases/download/"
    f"v{APKTOOL_VERSION}/apktool_{APKTOOL_VERSION}.jar"
)

UBER_APK_SIGNER_VERSION = "1.3.0"
UBER_APK_SIGNER_URL = (
    "https://github.com/patrickfav/uber-apk-signer/releases/download/"
    f"v{UBER_APK_SIGNER_VERSION}/uber-apk-signer-{UBER_APK_SIGNER_VERSION}.jar"
)

# Where downloaded tools live (overridable via the CLI / env).
DEFAULT_CACHE_DIR = Path.home() / ".cache" / "tiktok-region-patcher"


@dataclass
class RegionValues:
    """The literal values injected into the patched methods.

    Defaults spoof a German SIM/network so TikTok resolves the region to the
    EU instead of Russia.
    """

    # ISO 3166-1 alpha-2 country code returned by *CountryIso() methods.
    country_iso: str = "de"
    # MCC+MNC string returned by getNetworkOperator()/getSimOperator().
    # 262 is Germany's MCC; 01 is Telekom's MNC. Apps typically read the
    # first three digits (the MCC) to derive the country.
    operator: str = "26201"
    # Human readable carrier name for *OperatorName() methods.
    operator_name: str = "Telekom.de"

    def value_for_method(self, method: str) -> str:
        """Return the literal string a given TelephonyManager method should
        be forced to return."""
        if method in ("getNetworkCountryIso", "getSimCountryIso"):
            return self.country_iso
        if method in ("getNetworkOperator", "getSimOperator"):
            return self.operator
        if method in ("getNetworkOperatorName", "getSimOperatorName"):
            return self.operator_name
        raise KeyError(f"No region value configured for method {method!r}")


# TelephonyManager methods that return a String and leak the region. These
# are the invoke-sites we rewrite in the app's own smali (the framework class
# itself is not shipped inside the APK, so we patch every call to it).
PATCHABLE_METHODS: tuple[str, ...] = (
    "getNetworkCountryIso",
    "getSimCountryIso",
    "getNetworkOperator",
    "getSimOperator",
    "getNetworkOperatorName",
    "getSimOperatorName",
)


@dataclass
class PipelineConfig:
    """Everything the end-to-end pipeline needs."""

    # Input: either a local file path or an http(s) URL to an APK / bundle.
    source: str
    work_dir: Path
    output_dir: Path
    region: RegionValues = field(default_factory=RegionValues)
    cache_dir: Path = DEFAULT_CACHE_DIR
    # Optional custom signing keystore. If omitted, uber-apk-signer generates
    # (and reuses) a debug key automatically.
    keystore: Path | None = None
    keystore_pass: str | None = None
    key_alias: str | None = None
    key_pass: str | None = None
    # Skip the download step and treat ``source`` as an already-decoded folder.
    keep_intermediate: bool = True
    dry_run: bool = False
