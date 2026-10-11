# Lululemon Mirror Build 148 Notes

## Device

- Android 6.0.1
- Build fingerprint:
  `mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/148:user/release-keys`
- Qualcomm MSM8916 / APQ8016 platform
- ADB serial used during development:
  `7a574a6`

## Working Development Setup

- macOS development machine
- OpenJDK 17
- Android SDK:
  `/opt/homebrew/share/android-commandlinetools`
- Build tools 35.0.0
- Mirror Home builds successfully with:
  `./gradlew assembleDebug`

APK output:

`android/mirror-home/build/outputs/apk/debug/mirror-home-debug.apk`

Install:

`adb install -r android/mirror-home/build/outputs/apk/debug/mirror-home-debug.apk`

## ADB Authorization

ADB authorization was completed using scrcpy in OTG keyboard mode:

`/opt/homebrew/bin/scrcpy --otg --keyboard=aoa --mouse=disabled -s 7a574a6`

## Mirror Home

Package:

`dev.mirror.repurpose`

Main activity:

`dev.mirror.repurpose/.MainActivity`

The application runs successfully on build 148 in unprivileged/debug APK mode.

The privileged/system-helper install path has not been used because the project was tested primarily against firmware build 329.

## Stock Mirror Software

Relevant packages:

- `com.mirror.launcher`
- `com.mirror.services`
- `com.mirror.remover`

Stock launcher:

`com.mirror.launcher/.SplashActivity`

Dashboard:

`com.mirror.launcher/.dashboard.DashboardActivity`

## Wi-Fi and Control Server

Mirror Home control server listens on port:

`8787`

Example LAN URL:

`http://MIRROR_IP:8787`

If pairing becomes stuck due to stale client state, clearing only Mirror Home app data resets its pairing/configuration:

`adb shell pm clear dev.mirror.repurpose`

This does not clear Android system Wi-Fi configuration.

## Black Screen / Overlay Issue

On build 148, `com.mirror.services` can create a full-screen `SYSTEM_ALERT_WINDOW` overlay that makes the display appear black even though Android reports the screen as awake and powered on.

Symptoms included:

- `mWakefulness=Awake`
- display state `ON`
- screenshots also appearing black
- full-screen visible window owned by `com.mirror.services`

Working fix:

`adb shell appops set com.mirror.services SYSTEM_ALERT_WINDOW deny`

Then:

`adb shell am force-stop com.mirror.services`

This should remain denied unless a future feature specifically requires the stock overlay.

Restore with:

`adb shell appops set com.mirror.services SYSTEM_ALERT_WINDOW allow`

## Sleep / Display Control

Sleep behavior is NOT yet resolved on build 148.

A temporary source modification forced Mirror Home's display visibility target to 1.0, which prevented the app from blacking out the screen but also caused the Sleep control to stop working.

That modification has been reverted.

The stock `com.mirror.services` full-screen overlay and Mirror Home's own sleep/display behavior are separate issues.

Current known state:

- The stock overlay can cause an unintended black screen.
- Denying `SYSTEM_ALERT_WINDOW` to `com.mirror.services` fixes that particular problem.
- Mirror Home sleep has not yet been confirmed to work correctly on firmware build 148.
- A reliable method for intentionally turning the LCD/backlight off and waking it again still needs to be developed.

## FCast

Mirror Home's FCast receiver listens on port:

`46899`

It implements the older FCast v3 protocol.

A manually generated FCast v3 PLAY packet successfully played network video on the Mirror.

The current FCast Desktop Sender screen-mirroring/WebRTC functions did not work with this receiver because they expect newer protocol features.

## Camera

Android reports one active camera device through the Qualcomm Camera1 HAL.

Camera 0:

- Facing: BACK
- Orientation: 180
- Qualcomm QCamera module
- Camera1 API
- preview up to 1920x1080
- video up to 1920x1080
- fixed focus
- approximately 30 fps supported

Reported field of view:

- horizontal: 54.8 degrees
- vertical: 42.5 degrees

Camera preview experiments have not yet produced a visible image in the custom app.

## Current Direction

The intended architecture is to keep the original Mirror Android hardware as a thin client for:

- camera capture
- display output
- microphone input
- audio output
- network communication

Heavy computer vision and image processing can be offloaded over Ethernet to an external computer.

First art target:

A delayed, disorienting camera image aligned approximately with the viewer's physical reflection.

Longer-term target:

Selective body-region overlays using stored or delayed imagery while the rest of the LCD remains black so the physical mirror remains visible.

