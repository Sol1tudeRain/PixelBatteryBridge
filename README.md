# PixelBatteryBridge (experimental / 实验补丁)

## Status

**SOURCE CODE ONLY — NOT A TESTED APK, NOT A CONFIRMED FIX.**

Target: the provided PixelOS Android 17 build where `com.android.settings` lacks the receiver for
`settings.intelligence.battery.action.FETCH_BLUETOOTH_BATTERY_DATA`, while
`com.google.android.settings.intelligence` requests that action.

This is a small legacy Xposed/LSPosed hook **only scoped to Google Settings Services**. It does **not** modify the Settings APK, use platform signatures, or alter the kernel.

## How it works

It hooks `ContextImpl.sendBroadcast(Intent)` inside Google Settings Services. If the exact action and target package match the missing Settings Bluetooth battery data request, it reads the bond list and the framework-reported Bluetooth battery level, constructs the matching typed `Bundle` lists observed in the supplied Google Settings Services APK, and replies to the app's `ResultReceiver`. All other broadcasts are untouched.

### Data fields confirmed by APK inspection

- `bluetoothAddressListKey`: ArrayList<String>
- `bluetoothAliasListKey`: ArrayList<String>
- `bluetoothNameListKey`: ArrayList<String>
- `bluetoothClassListKey`: ArrayList<BluetoothClass> (Parcelable)
- `bluetoothBatteryLevelListKey`: ArrayList<String>
- `bluetoothBatteryChargingListKey`: ArrayList<String>
- `bluetoothWrapDataListKey`: Parcelable ArrayList (empty in prototype)
- `bluetoothParcelableListKey`: Parcelable ArrayList (empty in prototype)

### Known risks and gaps

- This code has **not** been run on the target phone, so it may fail to render anything.
- Google may require additional data normalization or flags not yet identified.
- The hook intercepts requests when issued; it does not independently force widget refresh events.
- The implementation only uses bonded Bluetooth devices with an exposed battery level. Device sub-components and per-bud levels are not supported.
- Some Google processes may reject reflection to `BluetoothDevice.getBatteryLevel()`; logs will show it.
- Even with an LSPosed-enabled device, Android 17 may require a compatible current LSPosed fork.
- On failure, disable the LSPosed module; no system APK has been replaced.

## Build with GitHub Actions

1. Put the **contents** of this folder into a new GitHub repository.
2. Open **Actions > Build experimental APK > Run workflow**.
3. Download the generated `PixelBatteryBridge-experimental-APK` artifact; it contains `app-debug.apk`.
4. Install `app-debug.apk` normally; enable this module in an LSPosed-compatible manager and set its **only scope** to `com.google.android.settings.intelligence` (Settings Services). Restart that application or reboot.
5. Re-add the Google Battery widget, connect the headset, and observe.

You must already have a compatible LSPosed implementation running. **KernelSU by itself is insufficient.**

## Check logs

```sh
su -c 'logcat -d -v time | grep PixelBatteryBridge | tail -n 40'
```

This command reads Android logs and filters for the bridge; it doesn't change anything. If the module is enabled but no `Installed hook` entry ever appears, the module is not loaded into the specified process.

## Rollback

Disable the module in LSPosed and restart the Settings Services process, or uninstall the module APK. The original Settings and Settings Services APKs are unchanged.

## Developer notes

The `xposed-api-stubs` Gradle module provides *compile-time only* Xposed legacy API declarations; runtime classes must be provided by LSPosed. No code from the user-supplied proprietary Google APK has been copied into this project.
