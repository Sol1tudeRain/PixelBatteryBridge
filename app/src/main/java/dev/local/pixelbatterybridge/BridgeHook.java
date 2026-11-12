package dev.local.pixelbatterybridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * EXPERIMENTAL. Intercepts only the missing battery-widget request inside
 * com.google.android.settings.intelligence. Does not patch or replace system APKs.
 * See README for known limitations and rollback.
 */
public final class BridgeHook implements IXposedHookLoadPackage {
    private static final String APP = "com.google.android.settings.intelligence";
    private static final String ACTION =
        "settings.intelligence.battery.action.FETCH_BLUETOOTH_BATTERY_DATA";
    private static final String EXTRA_RECEIVER = "android.intent.extra.RESULT_RECEIVER";
    private static final String TAG = "PixelBatteryBridge";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!APP.equals(lpparam.packageName)) return;
        // Emit a marker BEFORE trying to resolve/hook Android framework classes.
        // This distinguishes scope/entrypoint failures from hook registration failures.
        diag("v0.2 entry: package=" + lpparam.packageName +
             " process=" + lpparam.processName);
        try {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0 || !(param.args[0] instanceof Intent)) return;
                        Intent intent = (Intent) param.args[0];
                        if (!ACTION.equals(intent.getAction()) ||
                            !"com.android.settings".equals(intent.getPackage())) return;
                        diag("Observed FETCH_BLUETOOTH_BATTERY_DATA request");
                        ResultReceiver receiver = intent.getParcelableExtra(
                            EXTRA_RECEIVER, ResultReceiver.class);
                        if (receiver == null) {
                            diag("Missing ResultReceiver; allowing original broadcast");
                            return;
                        }
                        Bundle answer = collectBluetooth();
                        receiver.send(0, answer);
                        param.setResult(null);
                        diag("Replied with " +
                            answer.getStringArrayList("bluetoothAddressListKey").size() +
                            " devices");
                    } catch (Throwable ex) {
                        Log.e(TAG, "Bluetooth bridge error; allowing original request", ex);
                        XposedBridge.log(ex);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", lpparam.classLoader,
                "sendBroadcast", Intent.class, callback
            );
            diag("Installed hook: ContextImpl.sendBroadcast(Intent)");
            // Some app/framework builds call the overload with a permission argument.
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ContextImpl", lpparam.classLoader,
                    "sendBroadcast", Intent.class, String.class, callback
                );
                diag("Installed hook: ContextImpl.sendBroadcast(Intent,String)");
            } catch (Throwable optional) {
                diag("Optional two-arg overload unavailable: " + optional.getClass().getSimpleName());
            }
        } catch (Throwable ex) {
            diag("FAILED installing hook: " + ex.getClass().getName() + ": " + ex.getMessage());
            XposedBridge.log(ex);
        }
    }

    private static void diag(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    private static Bundle collectBluetooth() {
        ArrayList<String> addresses = new ArrayList<>();
        ArrayList<String> aliases = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<BluetoothClass> classes = new ArrayList<>();
        ArrayList<String> levels = new ArrayList<>();
        ArrayList<String> charging = new ArrayList<>();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice dev : bonded) {
                try {
                    Object battery = XposedHelpers.callMethod(dev, "getBatteryLevel");
                    if (!(battery instanceof Integer)) continue;
                    int level = (Integer) battery;
                    if (level < 0 || level > 100) continue;
                    String name = dev.getName();
                    if (name == null || name.isEmpty()) name = dev.getAddress();
                    addresses.add(dev.getAddress());
                    aliases.add(name);
                    names.add(name);
                    classes.add(dev.getBluetoothClass());
                    levels.add(Integer.toString(level));
                    charging.add("false");
                } catch (Throwable ex) {
                    Log.w(TAG, "Skipping a Bluetooth device", ex);
                }
            }
        }
        Bundle result = new Bundle();
        result.putStringArrayList("bluetoothAddressListKey", addresses);
        result.putStringArrayList("bluetoothAliasListKey", aliases);
        result.putStringArrayList("bluetoothNameListKey", names);
        result.putParcelableArrayList("bluetoothClassListKey", classes);
        result.putStringArrayList("bluetoothBatteryLevelListKey", levels);
        result.putStringArrayList("bluetoothBatteryChargingListKey", charging);
        result.putParcelableArrayList("bluetoothWrapDataListKey", new ArrayList<Parcelable>());
        result.putParcelableArrayList("bluetoothParcelableListKey", new ArrayList<Parcelable>());
        return result;
    }
}
