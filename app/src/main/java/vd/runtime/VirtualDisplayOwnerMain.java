package vd.runtime;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.security.SecureRandom;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * {@code app_process} entrypoint for the root VirtualDisplay owner.
 *
 * <p>Startup order matters: the process must be root, a main {@link Looper} must exist before any
 * display work, the display and its retained {@link android.media.ImageReader} are created on that
 * looper, and only then is the IPC socket bound and the single handshake line printed. A caller
 * therefore either sees {@code VD_OWNER_READY} with a socket name and token, or sees
 * {@code VD_OWNER_ERROR} with an exit code; there is no half-ready state.
 *
 * <p>The token is printed once on stdout and is the only secret the peer needs. The peer uid is
 * supplied on the command line and is checked against the kernel's peer credentials on every
 * connection.
 *
 * <p>The main looper prepared by {@link Looper#prepareMainLooper()} cannot be quit, so
 * {@link Looper#loop()} never returns. The process is ended only from the IPC stop callback, and
 * only after the owner reports the display released; a session that still has tasks is never
 * exited.
 *
 * <pre>
 * app_process /system/bin vd.runtime.VirtualDisplayOwner \
 *     --socket &lt;abstract-name&gt; --allow-uid &lt;app-uid&gt; \
 *     [--width W] [--height H] [--density DPI] [--name NAME]
 * </pre>
 */
public final class VirtualDisplayOwnerMain {
    private static final String READY_PREFIX = "VD_OWNER_READY";
    private static final String ERROR_PREFIX = "VD_OWNER_ERROR";
    private static final String STOPPED_PREFIX = "VD_OWNER_STOPPED";
    /** How often the owner checks that the app process which started it is still alive. */
    private static final long PARENT_POLL_MS = 2_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private VirtualDisplayOwnerMain() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException ex) {
            print(ERROR_PREFIX + " code=ARGS message=" + ex.getMessage());
            printUsage();
            return 2;
        }
        if (parsed.help) {
            printUsage();
            return 0;
        }
        if (Process.myUid() != 0) {
            print(ERROR_PREFIX + " code=ROOT_REQUIRED uid=" + Process.myUid());
            return 3;
        }

        VirtualDisplayFactory.setAlwaysUnlocked(parsed.allowScreenOff);

        Looper.prepareMainLooper();
        Handler handler = new Handler();

        VirtualDisplayOwner owner;
        try {
            owner = VirtualDisplayOwner.create(parsed.name, parsed.width, parsed.height,
                    parsed.densityDpi, handler);
        } catch (OwnerException ex) {
            print(ERROR_PREFIX + " code=" + ex.code + " message=" + ex.getMessage());
            return 4;
        }

        String token = newToken();
        OwnerCommandDispatcher dispatcher = new OwnerCommandDispatcher(owner);
        OwnerIpcServer server;
        try {
            server = new OwnerIpcServer(parsed.socket, parsed.allowUid, token, handler, dispatcher,
                    new Runnable() {
                        @Override
                        public void run() {
                            // IPC posts this only after release is verified and the response
                            // attempt finishes, including a failed reply or abandoned waiter.
                            // Only verified release may end the process: exiting while tasks
                            // remain would tear the display down with them.
                            if (!owner.isReleased()) {
                                print(STOPPED_PREFIX + " displayId=" + owner.displayId()
                                        + " released=false");
                                return;
                            }
                            print(STOPPED_PREFIX + " displayId=" + owner.displayId()
                                    + " released=true");
                            System.exit(0);
                        }
                    });
        } catch (OwnerException ex) {
            // returning non-zero exits the process, which tears the display down with it
            print(ERROR_PREFIX + " code=" + ex.code + " message=" + ex.getMessage());
            return 5;
        }

        print(READY_PREFIX + " v=" + OwnerProtocol.VERSION
                + " socket=" + server.socketName()
                + " token=" + token
                + " pid=" + Process.myPid()
                + " uid=" + Process.myUid()
                + " allowUid=" + parsed.allowUid
                + " displayId=" + owner.displayId()
                + " uniqueId=" + owner.uniqueId());
        server.start();

        // The app process owns this helper. An owner that outlives the app keeps a virtual display
        // alive until the device is rebooted, and nothing can ever drive or deliver that display
        // again. Exit with the app instead: process death releases the display.
        if (parsed.parentPid > 0L) {
            watchParent(parsed.parentPid, parsed.allowUid, owner);
        }

        // The main looper prepared above cannot be quit, so this never returns. The session ends
        // from the stop callback, which calls System.exit(0) once the release has been verified.
        Looper.loop();
        // Not reached: the stop callback exits the process.
        return 0;
    }

    /**
     * Ends the helper once the app process that started it is gone.
     *
     * <p>This deliberately does not use the release gate: that gate refuses to end the process
     * while tasks remain, because a caller may still hand them off. With the app gone there is no
     * caller left, so lingering would only strand the display for the next boot.
     */
    private static void watchParent(final long pid, final int allowUid, final VirtualDisplayOwner owner) {
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(PARENT_POLL_MS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    if (parentAlive(pid, allowUid)) {
                        continue;
                    }
                    try {
                        print(STOPPED_PREFIX + " displayId=" + owner.displayId()
                                + " released=false reason=parent_gone");
                    } catch (Throwable ignored) {
                        // Reporting must never keep an orphaned owner alive.
                    }
                    System.exit(0);
                }
            }
        }, "eta-vd-owner-parent");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * True while the recorded pid exists and still belongs to the app uid. Checking the uid keeps a
     * reused pid from being mistaken for the app that started this owner.
     */
    private static boolean parentAlive(long pid, int allowUid) {
        if (pid <= 0L) {
            return true;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("Uid:")) {
                    continue;
                }
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length == 0) {
                    return false;
                }
                return Integer.parseInt(parts[0]) == allowUid;
            }
            return false;
        } catch (Throwable gone) {
            // Missing or unreadable status means the process is gone.
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Nothing to do: the descriptor dies with this process anyway.
                }
            }
        }
    }

    private static void print(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static void printUsage() {
        print("usage: app_process /system/bin vd.runtime.VirtualDisplayOwner "
                + "--socket <abstract-name> --allow-uid <uid> "
                + "[--width W] [--height H] [--density DPI] [--name NAME] [--parent-pid PID]");
    }

    private static String newToken() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < random.length; i++) {
            sb.append(Character.forDigit((random[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(random[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /** Parsed command line. Values stay {@code null} when the device geometry should be used. */
    static final class Args {
        final String socket;
        final int allowUid;
        final Integer width;
        final Integer height;
        final Integer densityDpi;
        final String name;
        final boolean allowScreenOff;
        final boolean help;
        /** App process that owns this helper; 0 disables the liveness check. */
        final long parentPid;

        private Args(String socket, int allowUid, Integer width, Integer height, Integer densityDpi,
                String name, boolean allowScreenOff, boolean help, long parentPid) {
            this.socket = socket;
            this.allowUid = allowUid;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.name = name;
            this.allowScreenOff = allowScreenOff;
            this.help = help;
            this.parentPid = parentPid;
        }

        static Args parse(String[] args) {
            String socket = null;
            Integer allowUid = null;
            Integer width = null;
            Integer height = null;
            Integer density = null;
            String name = null;
            boolean allowScreenOff = false;
            boolean help = false;
            long parentPid = 0L;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if ("--help".equals(arg) || "-h".equals(arg)) {
                        help = true;
                    } else if ("--socket".equals(arg)) {
                        socket = value(args, ++i, arg);
                    } else if ("--allow-uid".equals(arg)) {
                        allowUid = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--width".equals(arg)) {
                        width = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--height".equals(arg)) {
                        height = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--density".equals(arg)) {
                        density = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--name".equals(arg)) {
                        name = value(args, ++i, arg);
                    } else if ("--allow-screen-off".equals(arg)) {
                        allowScreenOff = true;
                    } else if ("--parent-pid".equals(arg)) {
                        parentPid = positive(args, ++i, arg);
                    } else {
                        throw new IllegalArgumentException("unknown arg");
                    }
                }
            }
            if (help) {
                return new Args(null, 0, null, null, null, null, false, true, 0L);
            }
            if (socket == null || socket.isEmpty()) {
                throw new IllegalArgumentException("socket required");
            }
            if (!OwnerProtocol.isSafeIdentifier(socket)) {
                throw new IllegalArgumentException("socket");
            }
            if (allowUid == null || allowUid.intValue() <= 0) {
                throw new IllegalArgumentException("allow-uid required");
            }
            return new Args(socket, allowUid.intValue(), width, height, density, name, allowScreenOff, false, parentPid);
        }

        private static String value(String[] args, int index, String flag) {
            if (index >= args.length || args[index] == null || args[index].isEmpty()) {
                throw new IllegalArgumentException(flag);
            }
            return args[index];
        }

        private static int positive(String[] args, int index, String flag) {
            String raw = value(args, index, flag);
            int parsed;
            try {
                parsed = Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(flag);
            }
            if (parsed <= 0) {
                throw new IllegalArgumentException(flag);
            }
            return parsed;
        }
    }
}
