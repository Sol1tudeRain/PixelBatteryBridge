# PixelBatteryBridge v0.3 (experimental)

**Not built or tested on a device.** This is an experimental follow-up to the LSPosed log showing the v0.2 hook was installed, intercepted requests, but returned 0 devices.

Changes:
- Log bonded/candidate device counts (without logging names or MAC addresses).
- Log unknown battery levels / failures before filtering devices.
- Register for Bluetooth battery-level broadcasts inside the Google Settings Services process; cache levels as a fallback when `getBatteryLevel()` returns unknown.
- Clear cached entries when the device disconnects.

Install the APK as a normal app after compiling in GitHub Actions, select `com.google.android.settings.intelligence` as the sole LSPosed scope, and reboot. To test the broadcast fallback, with Settings Services running, disconnect and reconnect the earphones, then re-add the Google Battery Widget. Read *LSPosed module logs*, not only Android logcat.

**Limitations:** The system may not emit battery broadcasts to this receiver; output Bundle semantics may differ from what Google expects; a level might be available only through private OEM interfaces. In that case the widget will still not display the earbuds.

Rollback: disable PixelBatteryBridge in LSPosed, then force-stop Settings Services or reboot. Does not modify Settings.apk.
