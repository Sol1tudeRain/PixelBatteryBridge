package dev.local.pixelbatterybridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * Experimental PixelOS bridge v0.5. No system APK replacement.
 * Extends v0.4 with connected-device filtering and debounced refresh of the
 * existing Google Battery Widget after Bluetooth state or battery changes.
 * Not tested on device; disable in LSPosed if Settings Services misbehaves.
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
    private static final String ACTION_A2DP_STATE =
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_HEADSET_STATE =
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";
    private static final String PROVIDER =
        "com.google.android.settings.intelligence.modules.batterywidget.impl.BatteryAppWidgetProvider";
    // Avoid racing Bluetooth profile transitions or spamming the widget provider.
    private static final long REFRESH_DELAY_MS = 900L;
    private static final long MIN_REFRESH_INTERVAL_MS = 700L;

    private static final Map<String, Integer> batteryCache = new ConcurrentHashMap<>();
    private static final Map<String, BluetoothDevice> deviceCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> observedConnection = new ConcurrentHashMap<>();
    private static volatile boolean receiverRegistered = false;
    private static volatile Context applicationContext;
    private static Handler mainHandler;
    private static Runnable pendingRefresh;
    private static long lastRefreshElapsed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!APP.equals(lpparam.packageName)) return;
        diag("v0.5 entry: process=" + lpparam.processName);

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
        applicationContext = context.getApplicationContext();
        if (applicationContext == null) applicationContext = context;
        mainHandler = new Handler(Looper.getMainLooper());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (action == null) return;
                try {
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                        if (state == BluetoothAdapter.STATE_OFF ||
                            state == BluetoothAdapter.STATE_TURNING_OFF) {
                            batteryCache.clear();
                            deviceCache.clear();
                            observedConnection.clear();
                            diag("Bluetooth switched off: cache cleared");
                        }
                        scheduleRefresh("adapter state " + state);
                        return;
                    }
                    BluetoothDevice device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null) return;
                    String address = device.getAddress();
                    if (address == null) return;

                    if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                        observedConnection.put(address, false);
                        batteryCache.remove(address);
                        deviceCache.remove(address);
                        diag("ACL disconnected: cached data removed");
                        scheduleRefresh("ACL disconnect");
                    } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                        observedConnection.put(address, true);
                        deviceCache.put(address, device);
                        diag("ACL connected");
                        scheduleRefresh("ACL connect");
                    } else if (ACTION_BT_BATTERY_CHANGED.equals(action)) {
                        int level = intent.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1);
                        if (level >= 0 && level <= 100) {
                            batteryCache.put(address, level);
                            deviceCache.put(address, device);
                            // A valid report may be the first event after our process starts.
                            // The actual link state is rechecked before displaying the device.
                            if (!Boolean.FALSE.equals(observedConnection.get(address))) {
                                observedConnection.put(address, true);
                            }
                        } else {
                            batteryCache.remove(address);
                        }
                        diag("Battery report valid=" + (level >= 0 && level <= 100)
                            + ", cacheSize=" + batteryCache.size());
                        scheduleRefresh("battery changed");
                    } else if (ACTION_A2DP_STATE.equals(action) ||
                            ACTION_HEADSET_STATE.equals(action)) {
                        // A profile may disconnect while another profile is still active;
                        // rely on ACL_DISCONNECTED/isConnected() for final removal.
                        diag("Bluetooth profile state changed");
                        scheduleRefresh("profile changed");
                    }
                } catch (Throwable t) {
                    diag("Bluetooth broadcast handling failed: " + t);
                    XposedBridge.log(t);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_BT_BATTERY_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(ACTION_A2DP_STATE);
        filter.addAction(ACTION_HEADSET_STATE);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Broadcasts originate from the Bluetooth system UID; allow external senders.
        applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        receiverRegistered = true;
        diag("Registered Bluetooth connection/battery receiver (v0.5)");
    }

    /** Request a standard widget redraw, after coalescing rapid profile events. */
    private static synchronized void scheduleRefresh(String reason) {
        if (mainHandler == null || applicationContext == null) return;
        if (pendingRefresh != null) mainHandler.removeCallbacks(pendingRefresh);
        pendingRefresh = () -> refreshWidget(reason);
        mainHandler.postDelayed(pendingRefresh, REFRESH_DELAY_MS);
    }

    private static void refreshWidget(String reason) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastRefreshElapsed < MIN_REFRESH_INTERVAL_MS) return;
            Context ctx = applicationContext;
            if (ctx == null) return;
            AppWidgetManager widgets = AppWidgetManager.getInstance(ctx);
            ComponentName provider = new ComponentName(APP, PROVIDER);
            int[] ids = widgets.getAppWidgetIds(provider);
            if (ids == null || ids.length == 0) {
                diag("Refresh skipped: no Google battery widgets");
                return;
            }
            lastRefreshElapsed = now;
            // The supplied Settings Services APK overrides onUpdate(). Invoke
            // it inside its own process, avoiding broadcasts to a disabled
            // BatteryWidgetUpdateReceiver or protected broadcast restrictions.
            try {
                Class<?> providerClass = ctx.getClassLoader().loadClass(PROVIDER);
                Object providerInstance = providerClass.getDeclaredConstructor().newInstance();
                providerClass.getMethod("onUpdate", Context.class,
                    AppWidgetManager.class, int[].class).invoke(
                    providerInstance, ctx, widgets, (Object) ids);
                diag("Invoked Google provider.onUpdate for " + ids.length +
                    " widget(s): " + reason);
            } catch (Throwable directFailure) {
                diag("Direct onUpdate failed; trying standard update broadcast: " +
                    directFailure.getClass().getSimpleName());
                Intent update = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                update.setComponent(provider);
                update.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                ctx.sendBroadcast(update);
                diag("Sent standard widget update broadcast for " + ids.length +
                    " widget(s): " + reason);
            }
        } catch (Throwable t) {
            diag("Widget update request failed: " + t);
            XposedBridge.log(t);
        }
    }

    /** Hidden framework API: System Settings usually has Bluetooth permission. */
    private static Boolean isReallyConnected(BluetoothDevice device) {
        try {
            Object value = XposedHelpers.callMethod(device, "isConnected");
            if (value instanceof Boolean) return (Boolean) value;
        } catch (Throwable ignored) {
            // Some newer Android builds expose only isConnected(int transport).
            try {
                Object bredr = XposedHelpers.callMethod(device, "isConnected", 1);
                Object le = XposedHelpers.callMethod(device, "isConnected", 2);
                if (bredr instanceof Boolean && le instanceof Boolean) {
                    return (Boolean) bredr || (Boolean) le;
                }
            } catch (Throwable ignoredAgain) { /* see observed connection fallback */ }
        }
        return null;
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
                String addr = dev.getAddress();
                Boolean actualConnected = isReallyConnected(dev);
                Boolean observed = observedConnection.get(addr);
                // Never display a merely paired device. This is especially
                // important on disconnect: getBatteryLevel() can remain cached.
                if (Boolean.FALSE.equals(actualConnected) ||
                    (actualConnected == null && !Boolean.TRUE.equals(observed))) {
                    diag("Candidate skipped: not connected (system=" + actualConnected
                        + ", observed=" + observed + ")");
                    continue;
                }
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
                // Google's lvk.onReceiveResult() reads four battery values and
                // four charging flags for each device, in this exact order:
                // main, case, left, right. Missing values must still occupy slots.
                levels.add(Integer.toString(level)); // main
                levels.add("-1");                 // case unknown
                levels.add("-1");                 // left unknown
                levels.add("-1");                 // right unknown
                charging.add("false");            // main
                charging.add("false");            // case
                charging.add("false");            // left
                charging.add("false");            // right
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
