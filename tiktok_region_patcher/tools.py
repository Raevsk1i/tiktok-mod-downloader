"""Download and locate the external Java tools (apktool, uber-apk-signer)."""

from __future__ import annotations

import fcntl
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

DOWNLOAD_TIMEOUT_SEC = 120


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
    with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT_SEC) as resp, open(  # noqa: S310
        tmp, "wb"
    ) as fh:
        shutil.copyfileobj(resp, fh)
    tmp.replace(dest)


def _with_download_lock(cache_dir: Path, name: str, fn) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    lock_path = cache_dir / f".{name}.lock"
    with open(lock_path, "w") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        return fn()


class ToolManager:
    """Resolves the paths to apktool.jar and uber-apk-signer.jar."""

    def __init__(self, cache_dir: Path):
        self.cache_dir = Path(cache_dir)
        self.java = _require_java()

    def apktool_jar(self) -> Path:
        dest = self.cache_dir / f"apktool_{APKTOOL_VERSION}.jar"

        def _ensure() -> Path:
            if not dest.exists():
                _download(APKTOOL_URL, dest)
            return dest

        return _with_download_lock(self.cache_dir, "apktool", _ensure)

    def uber_apk_signer_jar(self) -> Path:
        dest = self.cache_dir / f"uber-apk-signer-{UBER_APK_SIGNER_VERSION}.jar"

        def _ensure() -> Path:
            if not dest.exists():
                _download(UBER_APK_SIGNER_URL, dest)
            return dest

        return _with_download_lock(self.cache_dir, "uber-apk-signer", _ensure)

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
        env = os.environ.get("TIKTOK_PATCHER_AAPT2")
        if env:
            path = Path(env).resolve()
            if path.is_file() and path.name in ("aapt2", "aapt2.exe"):
                return str(path)
            log.warning("TIKTOK_PATCHER_AAPT2 ignored (not a valid aapt2 path): %s", env)
        found = shutil.which("aapt2")
        return found


def default_cache_dir() -> Path:
    env = os.environ.get("TIKTOK_PATCHER_CACHE")
    if env:
        return Path(env)
    from .config import DEFAULT_CACHE_DIR

    return DEFAULT_CACHE_DIR
