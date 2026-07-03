"""Input validation helpers."""

from __future__ import annotations

import re

from .config import RegionValues

COUNTRY_ISO_RE = re.compile(r"^[a-z]{2}$")
OPERATOR_RE = re.compile(r"^\d{5,6}$")
# Printable ASCII without quotes, backslashes, or control chars.
OPERATOR_NAME_RE = re.compile(r"^[\x20-\x7E]+$")
MAX_OPERATOR_NAME_LEN = 64


def validate_region_values(region: RegionValues) -> None:
    """Reject region strings that would break smali or inject instructions."""
    if not COUNTRY_ISO_RE.match(region.country_iso):
        raise ValueError(
            f"Invalid --country {region.country_iso!r}: use a 2-letter ISO code (e.g. de)."
        )
    if not OPERATOR_RE.match(region.operator):
        raise ValueError(
            f"Invalid --operator {region.operator!r}: use 5–6 digits (MCC+MNC, e.g. 26201)."
        )
    if (
        not region.operator_name
        or len(region.operator_name) > MAX_OPERATOR_NAME_LEN
        or not OPERATOR_NAME_RE.match(region.operator_name)
        or '"' in region.operator_name
        or "\\" in region.operator_name
        or "\n" in region.operator_name
    ):
        raise ValueError(
            f"Invalid --operator-name {region.operator_name!r}: "
            "use printable ASCII without quotes (max 64 chars)."
        )


def sanitize_apk_filename(name: str) -> str:
    base = name.split("/")[-1].split("\\")[-1]
    safe = re.sub(r"[^\w.\-]", "_", base).strip("._")
    return (safe[:120] or "download.apk")
