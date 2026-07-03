"""Download and locate the external Java tools (apktool, uber-apk-signer).

Both tools are single self-contained jars that only require a JRE, which keeps
the whole pipeline free of an Android SDK install.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import urllib.request
from pathlib import Path

from .config import (
    APKTOOL_URL,
    APKTOOL_VERSION,
    UBER_APK_SIGNER_URL,
    UBER_APK_SIGNER_VERSION,
)

log = logging.getLogger(__name__)


class ToolError(RuntimeError):
    """Raised when a required tool is missing or fails to run."""


def _require_java() -> str:
    java = shutil.which("java")
    if not java:
        raise ToolError(
            "A Java runtime is required (java not found on PATH). "
            "Install a JRE/JDK 8+ and try again."
        )
    return java


def _download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    log.info("Downloading %s -> %s", url, dest)
    req = urllib.request.Request(url, headers={"User-Agent": "tiktok-region-patcher"})
    with urllib.request.urlopen(req) as resp, open(tmp, "wb") as fh:  # noqa: S310
        shutil.copyfileobj(resp, fh)
    tmp.replace(dest)


class ToolManager:
    """Resolves the paths to apktool.jar and uber-apk-signer.jar, downloading
    them into the cache directory on first use."""

    def __init__(self, cache_dir: Path):
        self.cache_dir = Path(cache_dir)
        self.java = _require_java()

    # ------------------------------------------------------------------ jars
    def apktool_jar(self) -> Path:
        dest = self.cache_dir / f"apktool_{APKTOOL_VERSION}.jar"
        if not dest.exists():
            _download(APKTOOL_URL, dest)
        return dest

    def uber_apk_signer_jar(self) -> Path:
        dest = self.cache_dir / f"uber-apk-signer-{UBER_APK_SIGNER_VERSION}.jar"
        if not dest.exists():
            _download(UBER_APK_SIGNER_URL, dest)
        return dest

    # --------------------------------------------------------------- runners
    def _run(self, args: list[str], **kwargs) -> subprocess.CompletedProcess:
        log.debug("Running: %s", " ".join(args))
        proc = subprocess.run(  # noqa: S603
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            **kwargs,
        )
        if proc.stdout:
            log.debug(proc.stdout)
        return proc

    def apktool(self, *args: str) -> subprocess.CompletedProcess:
        cmd = [self.java, "-jar", str(self.apktool_jar()), *args]
        proc = self._run(cmd)
        if proc.returncode != 0:
            raise ToolError(
                f"apktool failed (exit {proc.returncode}):\n{proc.stdout}"
            )
        return proc

    def uber_apk_signer(self, *args: str) -> subprocess.CompletedProcess:
        cmd = [self.java, "-jar", str(self.uber_apk_signer_jar()), *args]
        proc = self._run(cmd)
        if proc.returncode != 0:
            raise ToolError(
                f"uber-apk-signer failed (exit {proc.returncode}):\n{proc.stdout}"
            )
        return proc

    def find_aapt2(self) -> str | None:
        """apktool bundles aapt2 but the bundled binary does not run in every
        environment. Prefer a system aapt2 when one is available so building
        resources does not fail."""
        env = os.environ.get("TIKTOK_PATCHER_AAPT2")
        if env and Path(env).exists():
            return env
        return shutil.which("aapt2")


def default_cache_dir() -> Path:
    env = os.environ.get("TIKTOK_PATCHER_CACHE")
    if env:
        return Path(env)
    from .config import DEFAULT_CACHE_DIR

    return DEFAULT_CACHE_DIR
