"""End-to-end orchestration: decode -> patch -> rebuild -> sign."""

from __future__ import annotations

import logging
import shutil
from dataclasses import dataclass
from pathlib import Path

from .config import PipelineConfig
from .downloader import ApkSet, resolve_source
from .patcher import PatchReport, find_smali_roots, patch_tree
from .tools import ToolManager

log = logging.getLogger(__name__)


@dataclass
class PipelineResult:
    apk_set: ApkSet
    report: PatchReport
    signed_apks: list[Path]
    output_dir: Path

    def install_hint(self) -> str:
        if len(self.signed_apks) == 1:
            return f"adb install -r \"{self.signed_apks[0]}\""
        joined = " ".join(f'"{p}"' for p in self.signed_apks)
        return f"adb install-multiple -r {joined}"


class Pipeline:
    def __init__(self, config: PipelineConfig):
        self.cfg = config
        self.tools = ToolManager(config.cache_dir)

    # ---------------------------------------------------------------- steps
    def _decode(self, base_apk: Path) -> Path:
        decoded = self.cfg.work_dir / "decoded"
        if decoded.exists():
            shutil.rmtree(decoded)
        log.info("Decoding %s with apktool ...", base_apk.name)
        # -r keeps resources as-is (faster, avoids resource recompile issues);
        # we only touch smali. -f overwrites any stale output.
        self.tools.apktool("d", "-f", "-r", str(base_apk), "-o", str(decoded))
        return decoded

    def _patch(self, decoded: Path) -> PatchReport:
        roots = find_smali_roots(decoded)
        if not roots:
            raise RuntimeError(
                f"No smali directories found in {decoded}; is this the base APK?"
            )
        report = PatchReport()
        for root in roots:
            sub = patch_tree(root, self.cfg.region)
            report.scanned_files += sub.scanned_files
            report.referencing_files += sub.referencing_files
            report.patches.extend(sub.patches)
            report.skipped.extend(sub.skipped)
        log.info("Patch summary: %s", report.summary())
        return report

    def _build(self, decoded: Path) -> Path:
        rebuilt = self.cfg.work_dir / "rebuilt.apk"
        if rebuilt.exists():
            rebuilt.unlink()
        log.info("Rebuilding APK with apktool ...")
        args = ["b", str(decoded), "-o", str(rebuilt), "--use-aapt2"]
        aapt2 = self.tools.find_aapt2()
        if aapt2:
            log.info("Using system aapt2: %s", aapt2)
            args += ["--aapt", aapt2]
        self.tools.apktool(*args)
        return rebuilt

    def _sign(self, apks: list[Path]) -> list[Path]:
        self.cfg.output_dir.mkdir(parents=True, exist_ok=True)
        args: list[str] = []
        for apk in apks:
            args += ["-a", str(apk)]
        # NOTE: -o (copy to folder) and --overwrite (in place) are mutually
        # exclusive in uber-apk-signer; use only -o here.
        args += ["-o", str(self.cfg.output_dir), "--allowResign"]

        if self.cfg.keystore:
            args += ["--ks", str(self.cfg.keystore)]
            if self.cfg.keystore_pass:
                args += ["--ksPass", self.cfg.keystore_pass]
            if self.cfg.key_alias:
                args += ["--ksAlias", self.cfg.key_alias]
            if self.cfg.key_pass:
                args += ["--ksKeyPass", self.cfg.key_pass]
            log.info("Signing %d APK(s) with keystore %s", len(apks), self.cfg.keystore)
        else:
            log.info("Signing %d APK(s) with an auto-generated debug key", len(apks))

        self.tools.uber_apk_signer(*args)

        signed = sorted(self.cfg.output_dir.glob("*-aligned-*Signed*.apk"))
        if not signed:
            # uber-apk-signer naming varies by version; fall back to any apk.
            signed = sorted(self.cfg.output_dir.glob("*.apk"))
        return signed

    # ------------------------------------------------------------------ run
    def run(self) -> PipelineResult:
        apk_set = resolve_source(self.cfg.source, self.cfg.work_dir)
        log.info(
            "Input resolved: base=%s, splits=%d",
            apk_set.base.name,
            len(apk_set.splits),
        )

        decoded = self._decode(apk_set.base)
        report = self._patch(decoded)

        if report.patched_count == 0:
            log.warning(
                "No TelephonyManager call-sites were patched. The APK may use "
                "obfuscated wrappers or native code for region detection."
            )

        if self.cfg.dry_run:
            log.info("Dry run: skipping rebuild and signing.")
            return PipelineResult(apk_set, report, [], self.cfg.output_dir)

        rebuilt = self._build(decoded)

        # The rebuilt base replaces the original base; splits are copied as-is
        # and co-signed so the install set shares one signature.
        to_sign = [rebuilt, *apk_set.splits]
        signed = self._sign(to_sign)

        return PipelineResult(apk_set, report, signed, self.cfg.output_dir)
