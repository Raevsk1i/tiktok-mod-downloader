"""Acquire the input APK and normalise bundles (.xapk / .apks / .apkm).

TikTok is distributed as a *split* APK bundle. This module resolves the
``source`` (local path or URL) into a base APK plus a list of split APKs so
the pipeline knows what to patch (the base) and what to co-sign (the splits).
"""

from __future__ import annotations

import logging
import shutil
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

BUNDLE_SUFFIXES = {".xapk", ".apks", ".apkm", ".zip"}


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
    name = url.split("?")[0].rstrip("/").split("/")[-1] or "download.apk"
    dest = dest_dir / name
    log.info("Downloading APK from %s", url)
    req = urllib.request.Request(url, headers={"User-Agent": "tiktok-region-patcher"})
    with urllib.request.urlopen(req) as resp, open(dest, "wb") as fh:  # noqa: S310
        shutil.copyfileobj(resp, fh)
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


def _extract_bundle(bundle: Path, dest_dir: Path) -> ApkSet:
    """Unpack a split-APK bundle and classify base vs. split APKs."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    apks: list[Path] = []
    with zipfile.ZipFile(bundle) as zf:
        for member in zf.namelist():
            if member.lower().endswith(".apk"):
                target = dest_dir / Path(member).name
                with zf.open(member) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)
                apks.append(target)

    if not apks:
        raise ValueError(f"No .apk entries found inside bundle {bundle}")

    base = _pick_base(apks)
    splits = [a for a in apks if a != base]
    log.info("Bundle unpacked: base=%s, %d split(s)", base.name, len(splits))
    return ApkSet(base=base, splits=splits)


def _pick_base(apks: list[Path]) -> Path:
    """The base APK is the one carrying the app code (classes.dex)."""
    for apk in apks:
        name = apk.name.lower()
        if name in ("base.apk",) or name.startswith("base"):
            return apk
    # Fall back to whichever APK actually contains a dex file.
    for apk in apks:
        with zipfile.ZipFile(apk) as zf:
            if any(n.endswith(".dex") for n in zf.namelist()):
                return apk
    # Last resort: the largest APK.
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

    # A bundle: could be .xapk/.apks/.apkm, or a .zip/.apk that is really a
    # zip-of-apks. Detect by peeking inside.
    if suffix in BUNDLE_SUFFIXES and not _is_apk(local):
        return _extract_bundle(local, work_dir / "bundle")

    if _is_apk(local):
        return ApkSet(base=local, splits=[])

    # Unknown zip: try treating it as a bundle before giving up.
    if _looks_like_zip(local):
        return _extract_bundle(local, work_dir / "bundle")

    raise ValueError(f"Unrecognised input (not an APK or bundle): {local}")
