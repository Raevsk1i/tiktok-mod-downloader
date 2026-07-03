"""TikTok region patcher.

A small toolkit that downloads a TikTok APK (or accepts a local one),
decodes it with apktool, patches the smali call-sites that read the device
region via ``android.telephony.TelephonyManager`` so they hard-return a
non-Russian region (Germany by default: country ``de`` / MCC ``262``),
rebuilds the APK and re-signs it so it can be installed on a device.

The purpose is to stop TikTok from geolocating the device to Russia so the
"For You" feed serves the international catalogue instead.
"""

__version__ = "0.1.0"

__all__ = ["__version__"]
