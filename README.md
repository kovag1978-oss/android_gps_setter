# GPS Setter 

[English](README.md) | [Русский](README.ru.md)

[![Latest release](https://img.shields.io/github/v/release/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/kovag1978-oss/android_gps_setter/total)](https://github.com/kovag1978-oss/android_gps_setter/releases)
[![License: GPL-3.0](https://img.shields.io/github/license/kovag1978-oss/android_gps_setter)](LICENSE)
[![Open issues](https://img.shields.io/github/issues/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/issues)

Pick a place once, then switch saved locations by voice from Android Quick Settings without opening GPS Setter. GPS Setter is an Xposed location module with system-wide or per-app scope, joystick adjustment, and randomized nearby coordinates.

> **Requirements:** Android 8.1 or later and an Xposed-compatible framework, such as LSPosed or Vector. Root is required for LSPosed/Vector. LSPatch is also supported with a manually embedded location.

## Download

> **App ID changed:** older releases use `io.github.kovag1978oss.gpssetter`; the current ID is `io.github.kovag1978_oss.gpssetter`. Android treats them as separate apps, so uninstall the old version before installing the current one. App data and settings are not transferred.

Download the latest arm64 APK from [GitHub Releases](https://github.com/kovag1978-oss/android_gps_setter/releases/latest):

| Variant | Maps | Fused location | Download |
| --- | --- | --- | --- |
| **Full** | Google Maps | Google Play services | [Full APK](https://github.com/kovag1978-oss/android_gps_setter/releases/latest/download/app-full-arm64-v8a-release.apk) |
| **FOSS** | MapLibre | microG | [FOSS APK](https://github.com/kovag1978-oss/android_gps_setter/releases/latest/download/app-foss-arm64-v8a-release.apk) |

## Setup

1. Install the APK and enable **GPS Setter** in LSPosed or Vector.
2. In the module's scope, enable **Android System** (`android`) for system hooking.
3. If using the Full variant, enable **Google Play services** (`com.google.android.gms`) so GMS-based location requests are hooked.
4. For app-only hooking, also enable each target app in the scope.
5. Select a location in GPS Setter and start location spoofing. Restart affected apps after changing the scope; system hooking may require a device reboot.

The module lists Android System and Google Play services as recommended scope entries, but you still need to enable them in your Xposed manager.

## Features

- Set a location for apps or the system.
- Choose system-wide or per-app hooking.
- Randomize the location around the selected point.
- Adjust the active location with the on-screen joystick.
- Save, import, and export favorite locations.
- Use the GPS Setter Quick Settings tile to select a saved favorite by voice or say “stop” to turn spoofing off. Android's speech-recognition interface opens directly; GPS Setter itself does not open. Recognition checks the speech alternatives in order and allows a favorite name within a longer phrase. Unmatched or cancelled input leaves the current spoofed location unchanged.

## How it works

The module hooks Android location APIs, including `Location` and `LocationManager`, to provide the selected coordinates to apps. System hooking supports location-service changes introduced in Android 14 and later.

## Credits

- [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter) for the original project.
- [MapLibre](https://github.com/maplibre/maplibre-native) and [microG](https://github.com/microg/GmsCore) for the FOSS map and location components.

## Support

If GPS Setter is useful to you, you can [sponsor the project on GitHub](https://github.com/sponsors/kovag1978-oss).

Licensed under [GPL-3.0-only](LICENSE).
