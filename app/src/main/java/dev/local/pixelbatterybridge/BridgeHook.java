package dev.local.pixelbatterybridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Experimental PixelOS bridge v0.3. No system APK replacement.
 * Captures Android's Bluetooth battery-change broadcasts as a fallback for
 * BluetoothDevice.getBatteryLevel(), which can report an unknown level (-1).
 * This is NOT a verified fix; review logs before further changes.
 */
public final class BridgeHook implements IXposedHookLoadPackage {
    private static final String APP = "com.google.android.settings.intelligence";
    private static final String REQUEST =
        "settings.intelligence.battery.action.FETCH_BLUETOOTH_BATTERY_DATA";
    private static final String EXTRA_RECEIVER = "android.intent.extra.RESULT_RECEIVER";
    private static final String TAG = "PixelBatteryBridge";
    // System API constants are hidden from the public Android SDK. Use the exact AOSP values.
    private static final String ACTION_BT_BATTERY_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BT_BATTERY_LEVEL =
        "android.bluetooth.device.extra.BATTERY_LEVEL";

    private static final Map<String, Integer> batteryCache = new ConcurrentHashMap<>();
    private static final Map<String, BluetoothDevice> deviceCache = new ConcurrentHashMap<>();
    private static volatile boolean receiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!APP.equals(lpparam.packageName)) return;
        diag("v0.3 entry: process=" + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "attach", Context.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!receiverRegistered) registerBatteryReceiver((Context) param.args[0]);
                        } catch (Throwable t) { diag("Receiver setup failed: " + t); }
                    }
                }
            );
            diag("Installed Application.attach hook");
        } catch (Throwable t) { diag("FAILED Application.attach hook: " + t); }

        try {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0 || !(param.args[0] instanceof Intent)) return;
                        Intent intent = (Intent) param.args[0];
                        if (!REQUEST.equals(intent.getAction()) ||
                            !"com.android.settings".equals(intent.getPackage())) return;
                        diag("Observed FETCH_BLUETOOTH_BATTERY_DATA request");
                        ResultReceiver receiver = intent.getParcelableExtra(
                            EXTRA_RECEIVER, ResultReceiver.class);
                        if (receiver == null) {
                            diag("Missing ResultReceiver; leaving request unchanged");
                            return;
                        }
                        Bundle result = collectBluetooth();
                        receiver.send(0, result);
                        param.setResult(null);
                        diag("Replied with " + result.getStringArrayList(
                            "bluetoothAddressListKey").size() + " devices");
                    } catch (Throwable t) {
                        diag("Request handler failed: " + t);
                        XposedBridge.log(t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                "sendBroadcast", Intent.class, callback);
            diag("Installed sendBroadcast(Intent) hook");
            try {
                XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                    "sendBroadcast", Intent.class, String.class, callback);
                diag("Installed sendBroadcast(Intent,String) hook");
            } catch (Throwable optional) { diag("Optional sendBroadcast overload missing: " + optional); }
        } catch (Throwable t) {
            diag("FAILED request hook: " + t);
            XposedBridge.log(t);
        }
    }

    private static synchronized void registerBatteryReceiver(Context context) {
        if (receiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (intent == null) return;
                try {
                    BluetoothDevice device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null) return;
                    String address = device.getAddress();
                    if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                        batteryCache.remove(address);
                        deviceCache.remove(address);
                        diag("Bluetooth device disconnected; cache entry cleared");
                    } else if (ACTION_BT_BATTERY_CHANGED.equals(intent.getAction())) {
                        int level = intent.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1);
                        if (level >= 0 && level <= 100) {
                            batteryCache.put(address, level);
                            deviceCache.put(address, device);
                        } else {
                            batteryCache.remove(address);
                            deviceCache.remove(address);
                        }
                        diag("Bluetooth battery broadcast received; valid=" +
                            (level >= 0 && level <= 100) + "; cacheSize=" + batteryCache.size());
                    }
                } catch (Throwable t) { diag("Bluetooth broadcast handling failed: " + t); }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_BT_BATTERY_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        // Bluetooth stack broadcasts originate from a different UID; exported is required.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        receiverRegistered = true;
        diag("Registered Bluetooth battery event receiver");
    }

    private static Bundle collectBluetooth() {
        ArrayList<String> addresses = new ArrayList<>();
        ArrayList<String> aliases = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<BluetoothClass> classes = new ArrayList<>();
        ArrayList<String> levels = new ArrayList<>();
        ArrayList<String> charging = new ArrayList<>();

        Map<String, BluetoothDevice> candidates = new LinkedHashMap<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            diag("BluetoothAdapter unavailable");
        } else if (!adapter.isEnabled()) {
            diag("BluetoothAdapter disabled");
        } else {
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                diag("Bonded device count=" + (bonded == null ? -1 : bonded.size()));
                if (bonded != null) {
                    for (BluetoothDevice dev : bonded) {
                        if (dev != null) candidates.put(dev.getAddress(), dev);
                    }
                }
            } catch (Throwable t) { diag("getBondedDevices failed: " + t); }
            // Recently reported devices can be absent from the bonded-device set.
            candidates.putAll(deviceCache);
        }
        diag("Candidate count=" + candidates.size() +
            ", battery broadcast cache count=" + batteryCache.size());
        for (BluetoothDevice dev : candidates.values()) {
            try {
                int level = -1;
                try {
                    Object raw = XposedHelpers.callMethod(dev, "getBatteryLevel");
                    if (raw instanceof Integer) level = ((Integer) raw).intValue();
                } catch (Throwable t) {
                    diag("getBatteryLevel failed: " + t.getClass().getSimpleName());
                }
                if (level < 0 || level > 100) {
                    Integer fromBroadcast = batteryCache.get(dev.getAddress());
                    if (fromBroadcast != null) level = fromBroadcast;
                }
                if (level < 0 || level > 100) {
                    diag("Candidate skipped: reported battery unknown");
                    continue;
                }
                String name = dev.getName();
                if (name == null || name.isEmpty()) name = dev.getAddress();
                addresses.add(dev.getAddress());
                aliases.add(name);
                names.add(name);
                classes.add(dev.getBluetoothClass());
                levels.add(Integer.toString(level));
                charging.add("false");
            } catch (Throwable t) {
                diag("Candidate processing failed: " + t.getClass().getSimpleName());
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

    private static void diag(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
