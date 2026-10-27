# GPS Setter

[English](README.md) | [Русский](README.ru.md)

[![Latest release](https://img.shields.io/github/v/release/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/kovag1978-oss/android_gps_setter/total)](https://github.com/kovag1978-oss/android_gps_setter/releases)
[![License: GPL-3.0](https://img.shields.io/github/license/kovag1978-oss/android_gps_setter)](LICENSE)
[![Open issues](https://img.shields.io/github/issues/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/issues)
[![LSPosed module](https://img.shields.io/badge/LSPosed-Module-blue)](https://modules.lsposed.org/module/io.github.kovag1978_oss.gpssetter/)

GPS Setter is an Xposed location-spoofing module for Android. Set a location system-wide or assign independent coordinates to selected apps. Active spoofed locations are shown on the map, favorites can be selected by voice from Android Quick Settings, and a foreground service keeps the active-spoofing status visible while spoofing is running.

> **Requirements:** Android 8.1 or later and an Xposed-compatible environment such as LSPosed or Vector. LSPosed/Vector usually requires a rooted device. LSPatch can also be used with the module embedded into the target app.

## Download

Download the latest arm64 APK from [GitHub Releases](https://github.com/kovag1978-oss/android_gps_setter/releases/latest):

| Variant | Maps | Fused location provider | Download |
| --- | --- | --- | --- |
| **Full** | Google Maps | Google Play services | [Full APK](https://github.com/kovag1978-oss/android_gps_setter/releases/latest/download/app-full-arm64-v8a-release.apk) |
| **FOSS** | MapLibre | microG | [FOSS APK](https://github.com/kovag1978-oss/android_gps_setter/releases/latest/download/app-foss-arm64-v8a-release.apk) |

The current application ID is `io.github.kovag1978_oss.gpssetter`. Releases before v0.0.9 used a different ID (`io.github.kovag1978oss.gpssetter`); Android treats them as separate apps, so uninstall the older app before installing v0.0.9 or later. Its data is not transferred automatically.

## Setup

1. Install the APK and enable **GPS Setter** in LSPosed or Vector.
2. In the manager's scope, enable **Android System** (`android`) for system-wide spoofing. This is optional for app-only spoofing.
3. For the Full variant, enable **Google Play services** (`com.google.android.gms`) if the target uses GMS location APIs. For FOSS, enable the relevant microG package when needed.
4. For app-specific spoofing, also enable each target app in the manager's scope. Choosing an app in GPS Setter does not change LSPosed/Vector scope; scope must be managed there.
5. Select a location and apply it in GPS Setter. Restart target apps after changing Xposed scope; system-wide hooks may require a device reboot.

## Separate app locations

1. Open **Settings → Enable separate control** and select **Apps for separate control**.
2. Assign each app a unique voice command and marker color, then enable its separate profile. Installed Google Play services and Google Services Framework are included in the selectable apps.
3. Choose a location on the map or from favorites. Tap the apply button and choose an app or **All** to update every enabled profile.
4. When profiles are active, tap the separate stop button to choose one active profile or **All**. The system location target is listed when system spoofing is active. Long-press behavior is not used.

Each active app profile has its own ongoing notification. Applying a new location to one app updates that app's notification; stopping it removes only its notification. **All** stops every app profile and system spoofing.

## Voice control

Add the GPS Setter tile to Android Quick Settings and tap it to open Android's speech recognizer. GPS Setter itself does not need to be opened.

- With separate control enabled, the first prompt lists the enabled apps' voice commands and saved favorites and is labeled **Step 1 of 2**.
- Say a favorite name to apply that location to all enabled app profiles in one step, or say **выключить** to stop all system and app spoofing.
- Say an app's voice command to select it. After a short pause, the recognizer starts **Step 2 of 2** and names the selected app; say a favorite to set its location or **выключить** to stop spoofing for that app.
- With separate control disabled, say a favorite name to set the system location or **выключить** to stop system spoofing.
- App voice commands must be unique and cannot be **выключить**. Favorite names can be included in longer phrases. Unmatched or cancelled input leaves the current spoofing state unchanged.

The recognition prompt includes the configured app commands and saved favorite names. A long list may be truncated by the speech-recognition app.

## Map and active notifications

- The map displays every active spoofed location: the system location and locations assigned to managed apps. Markers move when coordinates change and disappear when spoofing stops.
- Managed-app marker colors are configured alongside each app's voice command. The system location marker uses its own color; the marker placed by tapping the map is black.
- Nearby active markers are grouped into clusters with visible colored dots. Tap a cluster to spread its markers around the cluster center, with lines connecting them to their original locations; moving the map closes the spider layout and recalculates clusters.
- Active location notifications are ongoing and cannot be dismissed with an ordinary swipe. A foreground service also runs while any system or app-specific spoofing is active. Android or the device manufacturer may still allow the user or system to stop the app or disable its notifications.

## Features

- System-wide or per-app location spoofing through Xposed hooks.
- Independent coordinates, voice commands, and map marker colors for selected apps.
- Live map markers for all active spoofed locations, with colored clusters and an expandable spider layout.
- Randomized coordinates near the selected location.
- Saved favorites with import and export.
- Ongoing active-location notifications and a foreground service while spoofing is in use.
- Full and FOSS variants with different map and fused-location providers.

## How it works

The module hooks Android location APIs, including `Location` and `LocationManager`, and supplies the configured coordinates to hooked apps. System hooking supports location-service changes introduced in Android 14 and later. The Xposed manager controls which processes receive the module; GPS Setter cannot modify that scope itself.

## Credits

- [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter) for the original project.
- [MapLibre](https://github.com/maplibre/maplibre-native) and [microG](https://github.com/microg/GmsCore) for the FOSS map and location components.

## Support

If GPS Setter is useful to you, you can [sponsor the project on GitHub](https://github.com/sponsors/kovag1978-oss).

Licensed under [GPL-3.0-only](LICENSE).
