"""Patch smali so ``TelephonyManager`` region lookups return fixed values.

``android.telephony.TelephonyManager`` is a framework class, so its bytecode
is *not* inside the APK. What lives in the APK are the call-sites: every place
the app invokes ``getNetworkCountryIso()`` and friends. We rewrite each of
those invoke/move-result pairs into a plain ``const-string`` that loads the
spoofed value, so the app behaves as if the framework returned it.

Example transformation::

    invoke-virtual {v2}, Landroid/telephony/TelephonyManager;->getNetworkCountryIso()Ljava/lang/String;
    move-result-object v2

becomes::

    const-string v2, "de"
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path

from .config import PATCHABLE_METHODS, RegionValues

log = logging.getLogger(__name__)

TELEPHONY_CLASS = "Landroid/telephony/TelephonyManager;"

# Matches an invoke of a TelephonyManager method that returns a String, e.g.:
#   invoke-virtual {v2}, Landroid/telephony/TelephonyManager;->getSimOperator()Ljava/lang/String;
_INVOKE_RE = re.compile(
    r"^(?P<indent>\s*)invoke-(?:virtual|direct|static|interface)(?:/range)?\s*"
    r"\{[^}]*\}\s*,\s*"
    + re.escape(TELEPHONY_CLASS)
    + r"->(?P<method>\w+)\(\)Ljava/lang/String;\s*$"
)

# The move-result-object that captures the return value of the invoke above.
_MOVE_RESULT_RE = re.compile(
    r"^(?P<indent>\s*)move-result-object\s+(?P<reg>[vp]\d+)\s*$"
)

# Lines we are allowed to skip between the invoke and its move-result:
# blank lines, comments, and the debug directives apktool may emit.
_SKIPPABLE_RE = re.compile(
    r"^\s*(#.*|\.line\b.*|\.local\b.*|\.end local\b.*|\.restart local\b.*)?$"
)


@dataclass
class FilePatch:
    path: Path
    method: str
    line_no: int
    register: str
    value: str


@dataclass
class PatchReport:
    scanned_files: int = 0
    referencing_files: int = 0
    patches: list[FilePatch] = field(default_factory=list)
    # Invoke sites we recognised but could not safely rewrite.
    skipped: list[tuple[Path, int, str]] = field(default_factory=list)

    @property
    def patched_count(self) -> int:
        return len(self.patches)

    def summary(self) -> str:
        by_method: dict[str, int] = {}
        for p in self.patches:
            by_method[p.method] = by_method.get(p.method, 0) + 1
        parts = [f"{m}={n}" for m, n in sorted(by_method.items())]
        return (
            f"scanned {self.scanned_files} smali files, "
            f"{self.referencing_files} referenced TelephonyManager, "
            f"applied {self.patched_count} patch(es) "
            f"[{', '.join(parts) if parts else 'none'}], "
            f"skipped {len(self.skipped)}"
        )


def _next_meaningful_line(lines: list[str], start: int) -> int | None:
    """Index of the next line that is not a blank/comment/debug directive."""
    for idx in range(start, len(lines)):
        if _SKIPPABLE_RE.fullmatch(lines[idx]):
            continue
        return idx
    return None


def patch_smali_text(text: str, region: RegionValues, path: Path | None = None):
    """Patch a single smali file's text.

    Returns ``(new_text, patches)`` where ``patches`` is a list of
    :class:`FilePatch`. ``new_text`` is ``None`` when nothing changed.
    """
    if TELEPHONY_CLASS not in text:
        return None, []

    lines = text.splitlines()
    keepends = "\n"
    out: list[str] = []
    patches: list[FilePatch] = []
    i = 0
    n = len(lines)

    while i < n:
        line = lines[i]
        m = _INVOKE_RE.match(line)
        if not m or m.group("method") not in PATCHABLE_METHODS:
            out.append(line)
            i += 1
            continue

        method = m.group("method")
        # Find the move-result-object that follows.
        j = _next_meaningful_line(lines, i + 1)
        move_match = _MOVE_RESULT_RE.match(lines[j]) if j is not None else None

        if move_match is None:
            # The result is discarded (rare) - we cannot cleanly redirect it,
            # so leave the invoke untouched and record it.
            log.debug(
                "Skipping %s at %s:%d (no move-result-object)",
                method,
                path,
                i + 1,
            )
            out.append(line)
            i += 1
            continue

        reg = move_match.group("reg")
        value = region.value_for_method(method)
        indent = m.group("indent")
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')

        # Emit any skippable lines that sat between invoke and move-result
        # (e.g. a .line directive) so we do not lose debug info, then the
        # const-string replacing the invoke+move-result pair.
        for k in range(i + 1, j):
            out.append(lines[k])
        out.append(f'{indent}const-string {reg}, "{escaped}"')

        patches.append(
            FilePatch(
                path=path or Path("<memory>"),
                method=method,
                line_no=i + 1,
                register=reg,
                value=value,
            )
        )
        i = j + 1

    if not patches:
        return None, []

    new_text = keepends.join(out)
    if text.endswith("\n"):
        new_text += "\n"
    return new_text, patches


def patch_tree(smali_root: Path, region: RegionValues) -> PatchReport:
    """Scan and patch every ``.smali`` file under ``smali_root``."""
    report = PatchReport()
    for smali in sorted(smali_root.rglob("*.smali")):
        report.scanned_files += 1
        try:
            text = smali.read_text(encoding="utf-8", errors="surrogateescape")
        except OSError as exc:  # pragma: no cover - unexpected IO
            log.warning("Could not read %s: %s", smali, exc)
            continue

        if TELEPHONY_CLASS not in text:
            continue
        report.referencing_files += 1

        new_text, patches = patch_smali_text(text, region, path=smali)
        if new_text is not None:
            smali.write_text(new_text, encoding="utf-8", errors="surrogateescape")
            report.patches.extend(patches)
            for p in patches:
                log.info(
                    "Patched %s -> \"%s\" in %s:%d (%s)",
                    p.method,
                    p.value,
                    smali.relative_to(smali_root),
                    p.line_no,
                    p.register,
                )
    return report


def find_smali_roots(decoded_dir: Path) -> list[Path]:
    """apktool emits smali/, smali_classes2/, ... for multidex APKs."""
    roots = [p for p in decoded_dir.iterdir() if p.is_dir() and p.name.startswith("smali")]
    return sorted(roots)
