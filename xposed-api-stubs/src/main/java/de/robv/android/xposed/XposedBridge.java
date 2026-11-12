package de.robv.android.xposed;
/** Compile-time API declarations; LSPosed supplies implementation at runtime. */
public final class XposedBridge {
    public static synchronized void log(String message) {}
    public static synchronized void log(Throwable t) {}
}
