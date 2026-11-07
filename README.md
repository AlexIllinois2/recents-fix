# Recents Double Tap Fix

English | [简体中文](README.zh-CN.md)

A lightweight Xposed module for AOSP/LineageOS-based ROMs (Launcher3 + Quickstep) that makes the
**second tap on the 3-button navigation Recents key switch to the next task immediately**, instead
of waiting for the overview transition to finish.

Tested on a Redmi Note 8 Pro (begonia) running Project Infinity X (Android 16).

## The problem

In Launcher3 the overview (Recents) transition is serialised by `OverviewCommandHelper`: while the
overview animation is playing, further overview commands are queued. So a double tap on the Recents
key behaves like:

1. First tap: Recents opens.
2. Second tap: the request waits for the opening animation to finish, then advances to the next
   task — roughly 600–700 ms of dead time.

On LineageOS-derived ROMs there is an extra layer: `PhoneWindowManager` waits ~300 ms after the
APP_SWITCH key event so it can distinguish a single press from a double press, which delays *every*
single tap as well.

## How it works

The module installs three hooks:

| Process | Target | Purpose |
| --- | --- | --- |
| `system_server` (`android`) | `PhoneWindowManager#updateKeyAssignments` | Forces `mAppSwitchDoubleTapAction` to `NOTHING`, which removes the 300 ms "wait for a possible double tap" delay for single taps. Equivalent to `lineagesettings key_app_switch_double_tap_action = 0`, but without needing write access to the Lineage settings provider. |
| Launcher | `OverviewCommandHelper#startRecentsTransitionWithEndTarget` | Captures the live `RecentsView` instance when the overview opens. |
| Launcher | `TaskbarNavButtonController#onButtonClick` | Detects a second Recents tap 80–450 ms after the first and calls the launcher's own `TaskView#launchWithAnimation` for the next task directly, short-circuiting the queued overview transition. |

Bounds on the double-tap window:

- **≥ 80 ms** — taps closer than this arrive before the overview transition is initialised; switching
  then leaves the launcher's state half-initialised and the next Recents press is swallowed.
- **≤ 450 ms** — beyond this it is simply treated as two independent presses.

The module uses the launcher's own launch path (instead of `startActivityFromRecents`) so that it
keeps building the correct `ActivityOptions` and still receives the launch callbacks, which keeps the
launcher's recents animation state consistent.

## Requirements

- Android 11+ (`minSdkVersion 30`), AOSP/LineageOS-style Launcher3 with Quickstep.
- An Xposed framework that provides the Xposed API ≥ 93 — for example **Zygisk Vector** or **LSPosed**.
- Module scope must include **System Framework** (`android`) **and** Launcher
  (`com.android.launcher3`). Both are required: without the system hook you only fix the second tap
  delay, without the launcher hook the fast path does nothing.

Non-standard launchers (MIUI, HyperOS, Pixel Launcher) will not work: the hooked classes do not exist
there.

## Install

1. Install `recentsfix.apk`.
2. Enable the module in your Xposed manager (Vector / LSPosed).
3. Set the scope to **System Framework** and **Launcher3**.
4. Reboot (or restart `system_server` and the launcher).

## Build

`build.sh` compiles the module and signs it with `debug.keystore` (generated on first run):

```bash
./build.sh
```

The APK is written to `out/recentsfix.apk`. The script expects:

- A JDK (`javac`, `jar`, `keytool`)
- Android SDK at `/home/v/Android/Sdk` with `build-tools/36.0.0` and `platforms/android-36`
  (edit `SDK` at the top of [build.sh](build.sh) if yours is elsewhere)

The Xposed API classes are not needed at build time: minimal stubs live in `stubs/` and are compiled
on the fly.

## Verifying / debugging

```bash
adb logcat -s XposedBridge
```

Expected lines from the module (prefixed with `RecentsFix:`):

- `system_server: app switch double tap forced to NOTHING`
- `overview started, view=RecentsView`
- `recents tap: delta=...` and, on a successful double tap,
  `second tap: switched to next task`

## Disable / uninstall

Disable the module in the Xposed manager and reboot, or uninstall the APK and reboot. Nothing is
persisted outside the module; the forced `NOTHING` action only lives in `PhoneWindowManager` memory
and is re-evaluated on every boot.

## Notes

If you only want the single-tap delay gone and can write Lineage settings, you can do that without
this module:

```bash
settings put lineage_global key_app_switch_double_tap_action 0
```

That leaves the double-tap-to-switch feature working through the normal (slow) path; this module
replaces it with an immediate switch.
