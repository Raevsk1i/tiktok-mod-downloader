"""Command line interface for the TikTok region patcher."""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from . import __version__
from .config import PipelineConfig, RegionValues
from .patcher import PATCHABLE_METHODS
from .pipeline import Pipeline
from .tools import default_cache_dir


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="tiktok-region-patcher",
        description=(
            "Download/patch a TikTok APK so TelephonyManager region lookups "
            "hard-return a non-Russian region (Germany by default), then "
            "rebuild and re-sign it for installation."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument(
        "source",
        help=(
            "Path or http(s) URL to a TikTok APK or split bundle "
            "(.apk/.xapk/.apks/.apkm). Obtain it yourself from a trusted "
            "mirror; this tool does not ship or scrape any APK."
        ),
    )
    p.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("./out"),
        help="Directory for the signed, patched APK(s).",
    )
    p.add_argument(
        "-w",
        "--work-dir",
        type=Path,
        default=Path("./work"),
        help="Scratch directory for downloads and decoded files.",
    )
    p.add_argument(
        "--cache-dir",
        type=Path,
        default=default_cache_dir(),
        help="Where apktool / uber-apk-signer jars are cached.",
    )

    region = p.add_argument_group("region spoofing values")
    region.add_argument(
        "--country",
        default="de",
        help="ISO country code returned by *CountryIso() methods.",
    )
    region.add_argument(
        "--operator",
        default="26201",
        help="MCC+MNC returned by getNetworkOperator()/getSimOperator() "
        "(262 = Germany).",
    )
    region.add_argument(
        "--operator-name",
        default="Telekom.de",
        help="Carrier name returned by *OperatorName() methods.",
    )

    signing = p.add_argument_group("signing (optional; debug key used if omitted)")
    signing.add_argument("--keystore", type=Path, help="Path to a .jks/.keystore.")
    signing.add_argument("--keystore-pass", help="Keystore password.")
    signing.add_argument("--key-alias", help="Key alias inside the keystore.")
    signing.add_argument("--key-pass", help="Key password.")

    p.add_argument(
        "--dry-run",
        action="store_true",
        help="Decode and report patchable call-sites without rebuilding/signing.",
    )
    p.add_argument("-v", "--verbose", action="store_true", help="Debug logging.")
    p.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    return p


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    cfg = PipelineConfig(
        source=args.source,
        work_dir=args.work_dir.resolve(),
        output_dir=args.output.resolve(),
        cache_dir=args.cache_dir,
        region=RegionValues(
            country_iso=args.country,
            operator=args.operator,
            operator_name=args.operator_name,
        ),
        keystore=args.keystore,
        keystore_pass=args.keystore_pass,
        key_alias=args.key_alias,
        key_pass=args.key_pass,
        dry_run=args.dry_run,
    )

    log = logging.getLogger("tiktok-region-patcher")
    log.info("Patchable TelephonyManager methods: %s", ", ".join(PATCHABLE_METHODS))

    try:
        result = Pipeline(cfg).run()
    except Exception as exc:  # noqa: BLE001 - surface a clean message on the CLI
        log.error("Failed: %s", exc)
        if args.verbose:
            raise
        return 1

    print("\n=== Result ===")
    print(result.report.summary())
    if cfg.dry_run:
        print("Dry run complete (no APK produced).")
        return 0

    if not result.signed_apks:
        print("No signed APKs were produced.")
        return 1

    print("Signed APK(s):")
    for apk in result.signed_apks:
        print(f"  {apk}")
    print("\nInstall with:")
    print(f"  {result.install_hint()}")
    print(
        "\nNote: uninstall the original TikTok first if signatures differ "
        "(adb uninstall com.zhiliaoapp.musically  or  com.ss.android.ugc.trill)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
