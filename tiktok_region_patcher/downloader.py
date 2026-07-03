"""Acquire the input APK and normalise bundles (.xapk / .apks / .apkm)."""

from __future__ import annotations

import logging
import shutil
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

from .validation import sanitize_apk_filename

log = logging.getLogger(__name__)

BUNDLE_SUFFIXES = {".xapk", ".apks", ".apkm", ".zip"}
DOWNLOAD_TIMEOUT_SEC = 120
MAX_BUNDLE_MEMBERS = 256
MAX_BUNDLE_BYTES = 4 * 1024 * 1024 * 1024  # 4 GiB uncompressed total


@dataclass
class ApkSet:
    """A base APK plus any split APKs that must be installed together."""

    base: Path
    splits: list[Path]

    @property
    def all_apks(self) -> list[Path]:
        return [self.base, *self.splits]


def _is_url(source: str) -> bool:
    return source.startswith("http://") or source.startswith("https://")


def _download_url(url: str, dest_dir: Path) -> Path:
    dest_dir.mkdir(parents=True, exist_ok=True)
    raw_name = url.split("?")[0].rstrip("/").split("/")[-1] or "download.apk"
    dest = dest_dir / sanitize_apk_filename(raw_name)
    tmp = dest.with_suffix(dest.suffix + ".part")
    log.info("Downloading APK from %s", url)
    req = urllib.request.Request(url, headers={"User-Agent": "tiktok-region-patcher"})
    with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT_SEC) as resp, open(  # noqa: S310
        tmp, "wb"
    ) as fh:
        shutil.copyfileobj(resp, fh)
    tmp.replace(dest)
    log.info("Saved to %s (%d bytes)", dest, dest.stat().st_size)
    return dest


def _looks_like_zip(path: Path) -> bool:
    return zipfile.is_zipfile(path)


def _is_apk(path: Path) -> bool:
    if not _looks_like_zip(path):
        return False
    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())
    return "AndroidManifest.xml" in names


def _safe_member_name(member: str) -> str | None:
    normal = member.replace("\\", "/")
    if normal.startswith("/") or ".." in normal.split("/"):
        log.warning("Skipping unsafe zip member: %s", member)
        return None
    return normal


def _extract_bundle(bundle: Path, dest_dir: Path) -> ApkSet:
    """Unpack a split-APK bundle and classify base vs. split APKs."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    apks: dict[str, Path] = {}
    total_uncompressed = 0

    with zipfile.ZipFile(bundle) as zf:
        members = [m for m in zf.namelist() if m.lower().endswith(".apk")]
        if len(members) > MAX_BUNDLE_MEMBERS:
            raise ValueError(f"Bundle has too many APK entries ({len(members)})")

        for idx, member in enumerate(members):
            safe = _safe_member_name(member)
            if safe is None:
                continue
            info = zf.getinfo(member)
            total_uncompressed += info.file_size
            if total_uncompressed > MAX_BUNDLE_BYTES:
                raise ValueError("Bundle uncompressed size exceeds limit")

            base_name = Path(safe).name or f"part{idx}.apk"
            unique = base_name if base_name not in apks else f"{idx}_{base_name}"
            target = dest_dir / unique
            with zf.open(member) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            apks[unique] = target

    if not apks:
        raise ValueError(f"No .apk entries found inside bundle {bundle}")

    apk_list = list(apks.values())
    base = _pick_base(apk_list)
    splits = [a for a in apk_list if a != base]
    log.info("Bundle unpacked: base=%s, %d split(s)", base.name, len(splits))
    return ApkSet(base=base, splits=splits)


def _pick_base(apks: list[Path]) -> Path:
    """The base APK is the one carrying the app code (classes.dex)."""
    for apk in apks:
        name = apk.name.lower()
        if name in ("base.apk",) or name.startswith("base"):
            return apk
    for apk in apks:
        with zipfile.ZipFile(apk) as zf:
            if any(n.endswith(".dex") for n in zf.namelist()):
                return apk
    return max(apks, key=lambda p: p.stat().st_size)


def resolve_source(source: str, work_dir: Path) -> ApkSet:
    """Turn a user-supplied source into an :class:`ApkSet` ready for patching."""
    work_dir.mkdir(parents=True, exist_ok=True)

    if _is_url(source):
        local = _download_url(source, work_dir / "download")
    else:
        local = Path(source).expanduser().resolve()
        if not local.exists():
            raise FileNotFoundError(f"Input not found: {local}")

    suffix = local.suffix.lower()

    if suffix in BUNDLE_SUFFIXES and not _is_apk(local):
        return _extract_bundle(local, work_dir / "bundle")

    if _is_apk(local):
        return ApkSet(base=local, splits=[])

    if _looks_like_zip(local):
        return _extract_bundle(local, work_dir / "bundle")

    raise ValueError(f"Unrecognised input (not an APK or bundle): {local}")
