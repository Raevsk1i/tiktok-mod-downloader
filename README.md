# tiktok-region-patcher

A command-line tool that patches a **TikTok APK** so it stops resolving the
device region to **Russia**. It does this by rewriting the app's own bytecode
so every call to `android.telephony.TelephonyManager` region lookups returns a
fixed non-Russian value (Germany by default: country `de`, MCC `262`).

Because TikTok picks the "For You" catalogue partly from the SIM/network
country, forcing those values makes it serve the international feed instead of
the Russian one.

> **Educational / personal-use reverse-engineering tool.** Modifying and
> redistributing an app can violate its Terms of Service and, in some places,
> local law. Use it only on software you are allowed to modify, on your own
> device. This project does **not** download, bundle, or scrape the TikTok APK
> for you — you must supply it yourself from a source you trust.

## How it works

The pipeline is fully automated:

1. **Acquire** – Take a local APK / split bundle, or download one from a URL you
   provide. Split bundles (`.xapk`, `.apks`, `.apkm`) are unpacked into a base
   APK plus split APKs.
2. **Decode** – Run [`apktool`](https://apktool.org) on the base APK to get
   `smali` (human-readable Dalvik bytecode).
3. **Patch** – Find every call-site of the region-leaking `TelephonyManager`
   methods and rewrite the `invoke` + `move-result-object` pair into a plain
   `const-string` that loads the spoofed value.
4. **Rebuild** – Repackage the patched sources with `apktool`.
5. **Sign** – Zipalign and sign (v1/v2/v3) with
   [`uber-apk-signer`](https://github.com/patrickfav/uber-apk-signer), using an
   auto-generated debug key or a keystore you provide. Split APKs are co-signed
   with the same key so the install set shares one signature.

`TelephonyManager` is an Android **framework** class, so its code is not inside
the APK. That is why we patch the *call-sites* in the app rather than the class
itself. Example transformation:

```smali
# before
invoke-virtual {v2}, Landroid/telephony/TelephonyManager;->getNetworkCountryIso()Ljava/lang/String;
move-result-object v2

# after
const-string v2, "de"
```

### Methods that get patched

| Method | Forced value (default) | Meaning |
| --- | --- | --- |
| `getNetworkCountryIso` | `de` | Network country ISO |
| `getSimCountryIso` | `de` | SIM country ISO |
| `getNetworkOperator` | `26201` | Network MCC+MNC (262 = Germany) |
| `getSimOperator` | `26201` | SIM MCC+MNC |
| `getNetworkOperatorName` | `Telekom.de` | Network carrier name |
| `getSimOperatorName` | `Telekom.de` | SIM carrier name |

All values are configurable on the command line.

## Requirements

- **Python 3.10+** (standard library only at runtime).
- **Java 8+** on `PATH` (`apktool` and `uber-apk-signer` are jars).
- **`aapt2`** for rebuilding resources. On Debian/Ubuntu: `sudo apt-get install aapt`.
  The tool auto-detects a system `aapt2`; you can also point it at one with the
  `TIKTOK_PATCHER_AAPT2` environment variable.
- To install the result: `adb` (Android Platform Tools) and a device/emulator.

`apktool` and `uber-apk-signer` are downloaded automatically on first run and
cached under `~/.cache/tiktok-region-patcher` (override with `--cache-dir` or
the `TIKTOK_PATCHER_CACHE` env var).

## Install

```bash
pip install -e .            # installs the `tiktok-region-patcher` command
# or just run it in place:
python -m tiktok_region_patcher --help
```

## Usage

```bash
# Patch a local base APK, output signed APK into ./out
python -m tiktok_region_patcher path/to/tiktok.apk -o ./out

# Patch a split bundle (base + config splits handled automatically)
python -m tiktok_region_patcher path/to/tiktok.xapk -o ./out

# Download from a URL you trust, then patch
python -m tiktok_region_patcher "https://example.com/tiktok.apk" -o ./out

# See what would be patched without rebuilding/signing
python -m tiktok_region_patcher tiktok.apk --dry-run

# Spoof a different region (e.g. France)
python -m tiktok_region_patcher tiktok.apk --country fr --operator 20801 --operator-name Orange

# Sign with your own keystore instead of a debug key
python -m tiktok_region_patcher tiktok.apk \
    --keystore my.jks --keystore-pass pass --key-alias mykey --key-pass pass
```

### Installing the patched app

```bash
# Single APK
adb install -r out/rebuilt-aligned-*Signed.apk

# Split bundle (multiple APKs must be installed together)
adb install-multiple -r out/*Signed.apk
```

If installation fails with a signature mismatch, uninstall the original first
(this removes app data):

```bash
adb uninstall com.zhiliaoapp.musically   # global TikTok
adb uninstall com.ss.android.ugc.trill   # some regional builds
```

## Notes & limitations

- If the tool reports **0 patches**, TikTok is reading the region through an
  obfuscated wrapper, reflection, or native (`.so`) code that this smali-level
  patch does not cover. Region detection can also use IP geolocation and the
  system locale, which this tool does not change — use a VPN and set the device
  language/region accordingly for best results.
- Patched, self-signed builds cannot receive Play Store updates and may trip
  integrity checks (Play Integrity / SafetyNet) in some app features.
- Re-run after each TikTok update; patches are not persistent across updates.

## Development

```bash
pip install -e '.[dev]'
pytest
```

The smali rewriting logic lives in `tiktok_region_patcher/patcher.py` and is
covered by unit tests in `tests/test_patcher.py`.
