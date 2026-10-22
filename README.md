# GPS Setter

[![Stars](https://img.shields.io/github/stars/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/stargazers)
[![GitHub](https://img.shields.io/github/downloads/kovag1978-oss/android_gps_setter/total?label=GitHub&logo=GitHub)](https://github.com/kovag1978-oss/android_gps_setter/releases)
[![release](https://img.shields.io/github/v/release/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/releases)
[![build](https://img.shields.io/github/actions/workflow/status/kovag1978-oss/android_gps_setter/apk.yml)](https://github.com/kovag1978-oss/android_gps_setter/actions/workflows/apk.yml)
[![license](https://img.shields.io/github/license/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/blob/master/LICENSE)
[![issues](https://img.shields.io/github/issues/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/issues)
  
A GPS setter based on the Xposed framework. This fork is the first module to achieve support for Android 15+ with its sources available.  

## Releases

This fork uses the separate application ID `io.github.kovag1978oss.gpssetter` and installs independently of the upstream app.

<table>
    <tr>
        <th>Version</th>
        <th>app-full-*.apk</th>
        <th>app-foss-*.apk</th>
    </tr>
    <tr>
        <th>Maps Library</th>
        <td>GMS (com.google.android.gms:play-services-maps)</td>
        <td>MapLibre (org.maplibre.gl:android-sdk)</td>
    </tr>
    <tr>
        <th>Fused Location</th>
        <td>GMS (com.google.android.gms:play-services-location)</td>
        <td>microG (org.microg.gms:play-services-location)</td>
    </tr>
    <tr>
        <th>Distribution</th>
        <td colspan="2">
            <a href="https://github.com/kovag1978-oss/android_gps_setter/releases">GitHub Releases (both variants)</a>
        </td>
    </tr>
</table>

<!-- 
[<img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/refs/heads/main/badge_github.png" alt="Get it on GitHub" height="80">](https://github.com/kovag1978-oss/android_gps_setter/releases)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80">]()
-->

## Motivation

An increasing number of apps are abusing the location permission for tracking purposes, preventing the user from using the app without granting the permission. Traditionally on Android, modifying the response from android server is done using the mock location provider - however, the availability of this feature is device dependent. Additionally, some apps have started explicitly checking for signals regarding whether the location provided is reliable. This module aims to mitigate this by providing an ability to either,
1. hook the app itself to modify the location it receives, or
2. hook the system server if the app explicitly checks for whether itself is being hooked

Specifically, in the case of hooking just the app, it intercepts [`android.location.Location`](https://developer.android.com/reference/android/location/Location) and [`android.location.LocationManager`](https://developer.android.com/reference/android/location/LocationManager) methods including
- [`getLatitude()`](https://developer.android.com/reference/android/location/Location#getLatitude())
- [`getLongitude()`](https://developer.android.com/reference/android/location/Location#getLongitude())
- [`getAccuracy()`](https://developer.android.com/reference/android/location/Location#getAccuracy())
- [`getLastKnownLocation(java.lang.String)`](https://developer.android.com/reference/android/location/LocationManager#getLastKnownLocation(java.lang.String))

## Compatibility

- Android 8.1+ (tested up to Android 16 Beta 2)
- Rooted devices with an Xposed-compatible framework (e.g. LSPosed or Vector; Vector supports the legacy Xposed API used by this module)
- Unrooted devices with LSPatch (with manually embedded specified location)

## LSPosed / Vector scope

The module recommends **Android System** (`android`) and **Google Play services** (`com.google.android.gms`) in its scope list. Enable Android System when using system hooking, and Google Play services when location is supplied through GMS. For app-only hooking, also enable each target app whose location should be changed. Restart the affected processes (or reboot) after changing scopes.

## Features

- ✨ (New) Supports system server location APIs introduced in Android 14+
- 🍀 (New) Supports a fully FLOSS build flavor - including all underlying dependencies
- 🖲️ (New) Allows adjusting location on the fly via an on-screen joystick overlay
- 🎉 (New) Features custom designed resource bundles with updated dependent libraries
- 🎲 Allows using a live updating random location in the vicinity of the set point
- 🔥 Compatible with latest Material Design

## Demo

<video loop src='https://github.com/user-attachments/assets/fbc0901c-b126-4ca7-9239-34390a76e7f9' alt="demo" width="200" style="display: block; margin: auto;"></video>

## Credits

- [Android1500](https://github.com/Android1500/GpsSetter) for the original GpsSetter targeting Android 8.1 to 13
- [MapLibre](https://github.com/maplibre/maplibre-native) for the mapping library
- [microG](https://github.com/microg/GmsCore) for the FOSS implementation of Google Mobile Services
