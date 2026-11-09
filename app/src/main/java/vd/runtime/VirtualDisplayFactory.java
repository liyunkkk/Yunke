package vd.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Display;
import android.view.Surface;

/**
 * Creates one virtual display through the public {@link DisplayManager} API.
 *
 * <p>The owner runs as root, but {@code DisplayManager.createVirtualDisplay} only accepts a package
 * name that belongs to the calling uid. Reaching the framework's system {@link Context} through
 * {@code ActivityThread.systemMain()} / {@code getSystemContext()} yields a {@code DisplayManager}
 * whose op package is {@code android}, which is what uid 0 owns. That is the same route the
 * platform's own display clients use; no hidden {@code DisplayManagerGlobal.createVirtualDisplay}
 * signature is guessed and no {@code IVirtualDisplayCallback} is fabricated.
 *
 * <p>The retained owner is an {@link ImageReader}: its {@link Surface} is the display's output
 * surface and it stays alive for the whole owner session, so the display is never released while
 * frames can still arrive. The framework owns the virtual-display callback.
 *
 * <p>The public API does not let a caller choose the display's unique id, so the value reported by
 * the created display's {@code DisplayInfo} is read back instead of echoing the requested string. A
 * display whose unique id cannot be read is never reported as created.
 */
final class VirtualDisplayFactory {
    /** uid 0 owns this package, which satisfies the display service's package-vs-uid check. */
    static final String OWNER_PACKAGE = "android";

    /**
     * The display must be trusted to host the focusable window of a task that was launched onto
     * it; without {@code FLAG_TRUSTED} the platform folds the window back onto the default
     * display and injected input has no window to land on. {@code FLAG_OWN_FOCUS} (Android 14+)
     * keeps that focus while the main display stays interactive, and {@code FLAG_SUPPORTS_TOUCH}
     * advertises the injected touch stream. The three flags are @hide constants, so they are
     * spelled out here; the values were read back from the running framework.
     */
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;

    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;

    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;

    /** @hide 平台常量：主屏熄屏/锁定时该 display 仍继续渲染。 */
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    /**
     * WindowManager 的每 display 输入法策略：不在该 display 上显示输入法。
     *
     * <p>取值与 AOSP {@code WindowManager.LayoutParams#DISPLAY_IME_POLICY_HIDE} 一致
     * （0=local，1=fallback 到主屏，2=hide）。副屏默认策略允许输入法「回退到物理主屏弹出」，
     * 表现就是后台任务一打字，主屏突然弹出软键盘或前台输入法被抢走。
     */
    private static final int DISPLAY_IME_POLICY_HIDE = 2;

    /** 最近一次 IME 策略设置结果（null=未尝试），供状态回报。 */
    private static volatile Boolean lastImePolicyHidden = null;

    static Object imeDiagnostics() {
        return lastImePolicyHidden;
    }

    /** 由 --allow-screen-off 打开；默认关闭，避免无谓耗电。 */
    private static volatile boolean alwaysUnlocked = false;

    static void setAlwaysUnlocked(boolean value) {
        alwaysUnlocked = value;
    }

    /**
     * 副屏标志组合。
     *
     * <p>与主项目 2026-11-07 的 c866a747 对齐：**不再启用 FLAG_OWN_FOCUS**。
     * OWN_FOCUS 让副屏拥有独立焦点，副作用是副屏里的应用会把系统输入法抢过去，
     * 表现为「后台任务一打字，主屏弹出软键盘 / 前台输入法被抢」。
     * 取代方案是建屏后用 {@link #applyDisplayImePolicyHide(int)} 把该 display 的
     * 输入法策略设为 HIDE。
     */
    static final int DEFAULT_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
            | VIRTUAL_DISPLAY_FLAG_TRUSTED;

    private static final String ACTIVITY_THREAD_CLASS = "android.app.ActivityThread";
    private static final String DISPLAY_MANAGER_GLOBAL_CLASS =
            "android.hardware.display.DisplayManagerGlobal";

    static final class Created {
        final VirtualDisplay display;
        final ImageReader reader;
        final Surface surface;
        final int displayId;
        final String uniqueId;
        final String name;
        final int width;
        final int height;
        final int densityDpi;
        final int flags;

        Created(VirtualDisplay display, ImageReader reader, Surface surface, int displayId,
                String uniqueId, String name, int width, int height, int densityDpi, int flags) {
            this.display = display;
            this.reader = reader;
            this.surface = surface;
            this.displayId = displayId;
            this.uniqueId = uniqueId;
            this.name = name;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.flags = flags;
        }
    }

    private VirtualDisplayFactory() {
    }

    static Created create(String name, int width, int height, int densityDpi, String uniqueId,
            int flags, int maxImages) throws OwnerException {
        if (name == null || name.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "name");
        }
        // The public createVirtualDisplay overload has no caller-supplied unique id; the argument is
        // validated for callers but the actual id is read back from the created display below.
        if (uniqueId == null || uniqueId.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "uniqueId");
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "geometry");
        }

        DisplayManager displayManager = systemDisplayManager();

        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                Math.max(1, maxImages));
        Surface surface = reader.getSurface();
        if (surface == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "no surface");
        }

        VirtualDisplay display;
        try {
            display = displayManager.createVirtualDisplay(name, width, height, densityDpi, surface,
                    flags | (alwaysUnlocked ? VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED : 0));
        } catch (Throwable ex) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "create:" + ex.getClass().getSimpleName());
        }
        if (display == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "create result");
        }

        Display bound = display.getDisplay();
        if (bound == null) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "no display");
        }
        int displayId = bound.getDisplayId();
        if (displayId <= 0) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "main display id");
        }
        String actualUniqueId = readBackUniqueId(displayId);
        if (actualUniqueId == null || actualUniqueId.isEmpty()) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "unique id");
        }
        // 建屏后立刻把该 display 的输入法策略设为 HIDE：不这么做，副屏里的应用一取
        // 输入焦点，系统就会把软键盘回退弹到物理主屏上（后台任务打字、主屏突然弹键盘）。
        // 失败不影响建屏，只记录结果供诊断。
        applyDisplayImePolicyHide(displayId);
        return new Created(display, reader, surface, displayId, actualUniqueId, name, width, height,
                densityDpi, flags);
    }

    /**
     * 把副屏的输入法策略设为 {@code DISPLAY_IME_POLICY_HIDE}。
     *
     * <p>对应主项目 c866a747 的做法：反射 {@code WindowManagerGlobal.getWindowManagerService()}
     * 拿到 IMS，再调用隐藏方法 {@code setDisplayImePolicy(displayId, policy)}，
     * 最后用 {@code getDisplayImePolicy} 回读校验。
     *
     * <p>本 owner 以 root 身份运行，不切 uid（切到 SYSTEM_UID 会破坏后续的
     * input 注入与任务管理）；调用失败只记录，不抛给调用方。
     */
    private static void applyDisplayImePolicyHide(int displayId) {
        Boolean applied = Boolean.FALSE;
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getService = wmgClass.getMethod("getWindowManagerService");
            access(getService);
            Object wms = getService.invoke(null);
            if (wms != null) {
                Method setPolicy = null;
                Method getPolicy = null;
                for (Method candidate : wms.getClass().getMethods()) {
                    if ("setDisplayImePolicy".equals(candidate.getName())
                            && candidate.getParameterTypes().length == 2) {
                        setPolicy = candidate;
                    } else if ("getDisplayImePolicy".equals(candidate.getName())
                            && candidate.getParameterTypes().length == 1) {
                        getPolicy = candidate;
                    }
                }
                if (setPolicy != null) {
                    access(setPolicy);
                    setPolicy.invoke(wms, displayId, DISPLAY_IME_POLICY_HIDE);
                    if (getPolicy != null) {
                        access(getPolicy);
                        Object actual = getPolicy.invoke(wms, displayId);
                        applied = actual == null
                                || ((Number) actual).intValue() == DISPLAY_IME_POLICY_HIDE;
                    } else {
                        // 没有回读接口时以「调用未抛异常」为准。
                        applied = Boolean.TRUE;
                    }
                }
            }
        } catch (Throwable ignored) {
            applied = Boolean.FALSE;
        }
        lastImePolicyHidden = applied;
    }

    static void releaseQuietly(VirtualDisplay display) {
        if (display == null) {
            return;
        }
        try {
            display.release();
        } catch (Throwable ignored) {
            // best effort; the process is exiting anyway
        }
    }

    /**
     * The framework's system {@link DisplayManager}, reached the way the platform's own clients
     * reach it: {@code ActivityThread.systemMain()} creates the system thread and
     * {@code getSystemContext()} hands back the context whose op package is {@code android}.
     */
    private static DisplayManager systemDisplayManager() throws OwnerException {
        try {
            Class<?> activityThreadClass = Class.forName(ACTIVITY_THREAD_CLASS);
            Method systemMain = activityThreadClass.getMethod("systemMain");
            access(systemMain);
            Object activityThread = systemMain.invoke(null);
            if (activityThread == null) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "system thread");
            }
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            access(getSystemContext);
            Object context = getSystemContext.invoke(activityThread);
            if (!(context instanceof Context)) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "system context");
            }
            Object service = ((Context) context).getSystemService(Context.DISPLAY_SERVICE);
            if (!(service instanceof DisplayManager)) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display manager");
            }
            return (DisplayManager) service;
        } catch (OwnerException ex) {
            throw ex;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "system context:" + cause.getClass().getSimpleName());
        } catch (Exception ex) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "system context:" + ex.getClass().getSimpleName());
        }
    }

    /** Reads the unique id the display service actually assigned to {@code displayId}. */
    private static String readBackUniqueId(int displayId) {
        try {
            Class<?> globalClass = Class.forName(DISPLAY_MANAGER_GLOBAL_CLASS);
            Method getInstance = globalClass.getMethod("getInstance");
            access(getInstance);
            Object global = getInstance.invoke(null);
            if (global == null) {
                return null;
            }
            Method getInfo = globalClass.getMethod("getDisplayInfo", int.class);
            access(getInfo);
            Object info = getInfo.invoke(global, Integer.valueOf(displayId));
            if (info == null) {
                return null;
            }
            Object fromField = readField(info, "uniqueId");
            if (fromField instanceof String && !((String) fromField).isEmpty()) {
                return (String) fromField;
            }
            try {
                Method method = info.getClass().getMethod("getUniqueId");
                access(method);
                Object value = method.invoke(info);
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
                // older DisplayInfo exposes the value as a field only
            }
        } catch (Throwable ignored) {
            // fall through to null; the caller refuses to report a requested id as real
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                access(field);
                return field.get(target);
            } catch (Throwable ignored) {
                // parent
            }
        }
        return null;
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // public members stay callable
        }
    }
}
