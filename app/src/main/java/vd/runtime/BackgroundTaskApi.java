package vd.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/** Narrow reflection boundary. Construction resolves ALL methods before the first side effect. */
final class BackgroundTaskApi {
    private final Object organizer;
    private final Constructor<?> transaction;
    private final Method create, delete, children, hidden, focusable, reparent, reorder, apply;
    /** Resolved argument count of createRootTask (3 or 6 on current ROMs). */
    private final int createArity;

    static BackgroundTaskApi resolve() throws Exception {
        return new BackgroundTaskApi(Class.forName("android.window.TaskOrganizer"),
                Class.forName("android.window.WindowContainerTransaction"),
                Class.forName("android.window.WindowContainerToken"),
                Class.forName("android.os.IBinder"));
    }

    /** Class seam exercises the actual reflection/transaction recipe without Android or a device. */
    BackgroundTaskApi(Class<?> organizerClass, Class<?> transactionClass,
            Class<?> tokenClass, Class<?> binderClass) throws Exception {
        Constructor<?> organizerConstructor = organizerClass.getConstructor();
        transaction = transactionClass.getConstructor();
        // Android 17 changed createRootTask: the documented 4-arg
        // createRootTask(int, int, IBinder, boolean) no longer exists; this ROM exposes
        // void createRootTask(int, int, IBinder) and a 6-arg overload. Resolve either shape and
        // remember the arity so invoke() fills the optional booleans with false (no onTop, no
        // removeWithTaskOrganizer), which is exactly what the old 4-arg call passed.
        Method createMethod = null;
        for (Method candidate : organizerClass.getMethods()) {
            if (!"createRootTask".equals(candidate.getName())) continue;
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length < 3 || params[0] != int.class || params[1] != int.class
                    || params[2] != binderClass) continue;
            boolean booleansOk = true;
            for (int i = 3; i < params.length; i++) {
                if (params[i] != boolean.class) { booleansOk = false; break; }
            }
            if (!booleansOk) continue;
            if (params.length == 3) { createMethod = candidate; break; }
            if (createMethod == null) createMethod = candidate;
        }
        if (createMethod == null) throw new NoSuchMethodException("createRootTask");
        create = createMethod;
        createArity = createMethod.getParameterTypes().length;
        delete = organizerClass.getMethod("deleteRootTask", tokenClass);
        children = organizerClass.getMethod("getChildTasks", tokenClass, int[].class);
        hidden = transactionClass.getMethod("setHidden", tokenClass, boolean.class);
        focusable = transactionClass.getMethod("setFocusable", tokenClass, boolean.class);
        reparent = transactionClass.getMethod("reparent", tokenClass, tokenClass, boolean.class);
        reorder = transactionClass.getMethod("reorder", tokenClass, boolean.class);
        apply = organizerClass.getMethod("applyTransaction", transactionClass);
        if (create.getReturnType() != void.class || delete.getReturnType() != boolean.class
                || !List.class.isAssignableFrom(children.getReturnType())
                || apply.getReturnType() != void.class) {
            throw new NoSuchMethodException("background task API signature");
        }
        // Neither constructor registers an organizer or mutates tasks. Never register/unregister.
        transaction.newInstance();
        organizer = organizerConstructor.newInstance();
    }

    void create(Object cookie) throws Exception {
        // Android 15 TaskOrganizerController.createRootTask builds an empty organizer root in
        // display 0's default TDA. Task.Builder.mOnTop defaults to false. The final boolean is
        // removeWithTaskOrganizer, NOT onTop. Do not replace this with an ATMS display move.
        Object[] createArgs = new Object[createArity];
        createArgs[0] = 0;
        createArgs[1] = 1 /* fullscreen */;
        createArgs[2] = cookie;
        // Optional booleans keep the documented defaults: no onTop, no removeWithTaskOrganizer.
        for (int i = 3; i < createArity; i++) createArgs[i] = false;
        create.invoke(organizer, createArgs);
    }

    List<?> children(Object token) throws Exception {
        Object result = children.invoke(organizer, token, null);
        if (!(result instanceof List)) throw new IllegalStateException("staging children unknown");
        return (List<?>) result;
    }

    void delete(Object token) throws Exception {
        if (!Boolean.TRUE.equals(delete.invoke(organizer, token))) {
            throw new IllegalStateException("staging deletion unverified");
        }
    }

    void hide(Object token) throws Exception {
        Object change = transaction.newInstance();
        hidden.invoke(change, token, true);
        focusable.invoke(change, token, false);
        apply.invoke(organizer, change);
    }

    void relocateAndRestore(Object token, Object staging) throws Exception {
        apply.invoke(organizer, movement(token, staging));
    }

    /** Also built (not applied) during preflight to validate reflective argument compatibility. */
    Object movement(Object token, Object staging) throws Exception {
        Object change = transaction.newInstance();
        // WOC applies value changes before its ordered hierarchy list, with layout/root visibility
        // deferred. Do not mistake builder call order for ordered setHidden/setFocusable operations.
        hidden.invoke(change, token, false);
        focusable.invoke(change, token, true);
        // Android 15 WOC.sanitizeAndApplyHierarchyOp resolves null against CURRENT display, so
        // both hops MUST be in this order in ONE WCT. The first hop gives the task display 0.
        reparent.invoke(change, token, staging, false);
        reparent.invoke(change, token, null, false);
        reorder.invoke(change, token, false);
        return change;
    }
}
