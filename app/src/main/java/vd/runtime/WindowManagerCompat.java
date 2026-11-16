package vd.runtime;

import android.os.IBinder;
import java.lang.reflect.Method;

/** app_process does not receive Zygote's bindApplication initialization. */
final class WindowManagerCompat {
    private WindowManagerCompat() {}

    static void exemptHiddenApis() {
        try {
            Class<?> bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            bypass.getMethod("addHiddenApiExemptions", String[].class).invoke(null,
                    (Object) new String[] {"Landroid/view/", "Landroid/hardware/display/",
                            "Landroid/app/", "Landroid/os/", "Lcom/android/internal/os/"});
        } catch (Throwable ignored) {
            // Some owner launchers already exempt hidden APIs. Lookup still reports failures below.
        }
    }

    static Object resolve() throws Exception {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
            if (binder != null) {
                Object proxy = Class.forName("android.view.IWindowManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
                if (proxy != null) return proxy;
            }
        } catch (Exception ignored) {
            // Compatibility fallback for vendor frameworks without a usable direct proxy.
        }
        ensureSharedMemory();
        return Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
    }

    private static void ensureSharedMemory() {
        try {
            Class<?> cls = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            boolean initialized;
            try { initialized = cls.getMethod("getInstance").invoke(null) != null; }
            catch (Exception ignored) { initialized = false; }
            if (!initialized) {
                Object instance = cls.getMethod("create").invoke(null);
                cls.getMethod("setInstance", cls).invoke(null, instance);
            }
        } catch (Exception ignored) {
            // Absent on older Android; WMG remains authoritative.
        }
    }

    static Method method(Object receiver, String name, Class<?>... args) throws Exception {
        Method result = receiver.getClass().getMethod(name, args);
        result.setAccessible(true);
        return result;
    }
}
