/* Copyright 2018 Jorrit 'Chainfire' Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.librootjavadaemon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.chainfire.librootjava.AppProcess;
import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.RootIPC;
import eu.chainfire.librootjava.RootJava;

/**
 * Class with utility function sto launch Java code running as a root as a daemon
 *
 * @see #getLaunchScript(Context, Class, String, String, String[], String)
 * @see #daemonize(String, int, boolean, OnExitListener)
 * @see #run()
 * @see #exit()
 */
@SuppressWarnings({"unused", "WeakerAccess", "Convert2Diamond"})
public class RootDaemon {

    /** Used for logging */
    private static String LOG_PREFIX = "daemon";

    // ------------------------ calls for non-root ------------------------

    /**
     * Patches a script returned from librootjava's RootJava.getLaunchScript() to run as
     * a daemon.<br>
     * <br>
     * You would normally call {@link #getLaunchScript(Context, Class, String, String, String[], String)}.<br>
     * <br>
     * NOTE: This is not compatible with using extractNativeLibs="false" in your manifest!
     *
     * @param context Application or activity context
     * @param script Script from RootJava.getLaunchScript()
     * @return Patched script
     */
    public static List<String> patchLaunchScript(Context context, List<String> script) {
        List<String> ret = new ArrayList<String>();
        String app_process = null;
        boolean in_post = false;
        for (String line : script) {
            if (line.startsWith("#app_process=")) {
                // app_process marker
                app_process = line.substring("#app_process=".length());
                ret.add(line);
            } else if ((app_process != null) && line.contains(app_process) && line.contains("CLASSPATH=")) {
                // patch the main script line
                String app_process_path = app_process.substring(0, app_process.lastIndexOf('/'));

                // our executable
                String libSource = RootJava.getLibraryPath(context, "daemonize");
                String libExec;

                if (app_process_path.startsWith("/system/bin")) {
                    // app_process was not relocated, assume caller knows what he's doing, and
                    // run our executable from its library location
                    libExec = libSource;
                } else {
                    // copy our executable
                    libExec = app_process_path + "/.daemonize_" + AppProcess.UUID;
                    boolean onData = libExec.startsWith("/data/");

                    ret.add(String.format(Locale.ENGLISH, "%s cp %s %s >/dev/null 2>/dev/null", AppProcess.BOX, libSource, libExec));
                    ret.add(String.format(Locale.ENGLISH, "%s chmod %s %s >/dev/null 2>/dev/null", AppProcess.BOX, onData ? "0766" : "0700", libExec));
                    if (onData) ret.add(String.format(Locale.ENGLISH, "restorecon %s >/dev/null 2>/dev/null", libExec));
                }

                // inject executable into command
                int idx = line.indexOf(app_process);
                ret.add(line.substring(0, idx) + libExec + " " + line.substring(idx));

                in_post = true;
            } else if (in_post && line.contains("box rm")) {
                // we could remove the files we need before they properly execute
                ret.add("#" + line);
            } else {
                // other lines are good
                ret.add(line);
            }
        }
        return ret;
    }

    /**
     * Get script to be executed (in a root shell) to launch the Java code as root as a daemon.<br>
     * <br>
     * See librootjava's RootJava.getLaunchScript() for further details.<br>
     * <br>
     * NOTE: This is not compatible with using extractNativeLibs="false" in your manifest!
     *
     * @param context Application or activity context
     * @param clazz Class containing "main" method
     * @param app_process Specific app_process binary to use, or null for default
     * @param relocate_path Path to relocate app_process to (must exist), or null for default
     * @param params Parameters to supply to Java code, or null
     * @param niceName Process name to use (ps) instead of app_process (should be unique to your app), or null
     * @return Script
     */
    public static List<String> getLaunchScript(Context context, Class<?> clazz, String app_process, String relocate_path, String[] params, String niceName) {
        return patchLaunchScript(context, RootJava.getLaunchScript(context, clazz, app_process, relocate_path, params, niceName));
    }

    /** Prefixes of filename to remove from the app's cache directory */
    public static final String[] CLEANUP_CACHE_PREFIXES = new String[] { ".daemonize_" };

    /**
     * Clean up leftover files from our cache directory.<br>
     * <br>
     * Call this method instead of (not in addition to) RootJava#cleanupCache(Context).
     *
     * @param context Context to retrieve cache directory from
     */
    public static void cleanupCache(Context context) {
        String[] prefixes = new String[RootJava.CLEANUP_CACHE_PREFIXES.length + CLEANUP_CACHE_PREFIXES.length];
        System.arraycopy(RootJava.CLEANUP_CACHE_PREFIXES, 0, prefixes, 0, RootJava.CLEANUP_CACHE_PREFIXES.length);
        System.arraycopy(CLEANUP_CACHE_PREFIXES, 0, prefixes, RootJava.CLEANUP_CACHE_PREFIXES.length, CLEANUP_CACHE_PREFIXES.length);
        RootJava.cleanupCache(context, prefixes);
    }

    // ------------------------ calls for root ------------------------

    /** Registered interfaces */
    private static final List<RootIPC> ipcs = new ArrayList<RootIPC>();

    /** Called before termination */
    public interface OnExitListener {
        void onExit();
    }

    /** Stored by daemonize(), called by exit() */
    private static volatile OnExitListener onExitListener = null;

    /**
     * Makes sure there is only a single daemon running with this code parameter. This should
     * be one of the first calls in your process to be run as daemon, just after setting up logging
     * and exception handling. Anything you do before this call has a high potential of being
     * a waste of resources, as this call may terminate the process.<br>
     * <br>
     * This method checks to see if another daemon is already running. If so and the other daemon
     * is of the same version, it asks the other daemon to re-broadcast its IPC interfaces to
     * listening non-root processes, and this process terminates (this method never returns).<br>
     * <br>
     * If another daemon of an older version is running, it will be told to terminate.<br>
     * <br>
     * If there is no other daemon running or it was terminated, this process becomes the default
     * daemon and this method returns. Internally it registers a Binder service (using reflection)
     * that calls to this method from other processes can connect to, to negotiate who terminates
     * and who keeps running, and to trigger a re-broadcast of the registered IPC interfaces.<br>
     * <br>
     * We are already technically daemonized when we reach this point, as our STDIN/STDOUT/STDERR
     * are closed, we are running as a child of pid 1 (init), and no longer tied to the lifecycle of
     * the process that started us.
     *
     * @param packageName Package name of the app. BuildConfig.APPLICATION_ID can generally be used.
     * @param code User-value, should be unique per daemon process
     * @param surviveFrameworkRestart If false (recommended), automatically terminate if the Android framework restarts
     * @param exitListener Callback called before the daemon exists either due to a newer daemon version being started or {@link #exit()} being called, or null
     */
    @SuppressLint("PrivateApi")
    public static void daemonize(String packageName, int code, boolean surviveFrameworkRestart, OnExitListener exitListener) {
        String id = packageName + "_" + String.valueOf(code) + "_daemonize";

        File apk = new File(System.getenv("CLASSPATH"));
        final String version = String.format(Locale.ENGLISH, "%s:%d:%d", apk.getAbsolutePath(), apk.lastModified(), apk.length());

        try {
            // Reflection stability: presumed stable
            Class<?> cServiceManager = Class.forName("android.os.ServiceManager");
            Method mGetService = cServiceManager.getDeclaredMethod("getService", String.class);
            Method mAddService = cServiceManager.getDeclaredMethod("addService", String.class, IBinder.class);

            IBinder svc = (IBinder)mGetService.invoke(null, id);
            if (svc != null) {
                IRootDaemonIPC ipc = IRootDaemonIPC.Stub.asInterface(svc);
                if (!ipc.getVersion().equals(version)) {
                    // Installing a newer APK usually kills the daemon automagically, but not always
                    Logger.dp(LOG_PREFIX, "Terminating outdated daemon");
                    try {
                        ipc.terminate();
                    } catch (RemoteException e) {
                        // this always happens because the process we're calling dies
                    }
                } else {
                    Logger.dp(LOG_PREFIX, "Service already running, requesting re-broadcast and aborting");
                    ipc.broadcast();
                    exit();
                }
            }

            // If we reach this, there either was no previous daemon, or it was outdated
            Logger.dp(LOG_PREFIX, "Installing service");
            onExitListener = exitListener;

            if (!surviveFrameworkRestart) {
                /* We link to Android's activity service, which lives in system_server. If the
                   framework is restarted, for example through stop/start in a root shell, this
                   service will die and we will be notified.

                   Obviously when setting surviveFrameworkRestart to true, things you do in
                   your own code may still cause this process to terminate when the framework
                   dies, we're just not doing it automatically. */

                IBinder activityService = (IBinder)mGetService.invoke(null, Context.ACTIVITY_SERVICE);
                if (activityService != null) {
                    try {
                        activityService.linkToDeath(new IBinder.DeathRecipient() {
                            @Override
                            public void binderDied() {
                                exit();
                            }
                        }, 0);
                    } catch (RemoteException e) {
                        // already dead
                        exit();
                    }
                }
            }

            mAddService.invoke(null, id, new IRootDaemonIPC.Stub() {
                @Override
                public String getVersion() {
                    return version;
                }

                @Override
                public void terminate() {
                    exit();
                }

                @Override
                public void broadcast() {
                    Logger.dp(LOG_PREFIX, "Re-broadcasting IPC interfaces");
                    synchronized (ipcs) {
                        for (RootIPC ipc : ipcs) {
                            ipc.broadcastIPC();
                        }
                    }
                }
            });
        } catch (Exception e) {
            Logger.ex(e);
            throw new RuntimeException("librootjavadaemon: could not get/add service");
        }
    }

    /**
     * Register a Binder interface for IPC.<br>
     * <br>
     * Use this method instead of librootjava's 'new RootIPC()' constructor when running as daemon.
     *
     * @param packageName Package name of the app. Use the same value as used when calling {@link #daemonize(String, int, boolean, OnExitListener)}.
     * @param ipc Binder object to wrap and send out
     * @param code User-value, should be unique per Binder
     * @return RootIPC instance
     */
    public static RootIPC register(String packageName, IBinder ipc, int code) {
        synchronized (ipcs) {
            try {
                RootIPC ripc = new RootIPC(packageName, ipc, code, 0, false);
                ipcs.add(ripc);
                return ripc;
            } catch (RootIPC.TimeoutException e) {
                // doesn't actually happen
            }
            return null;
        }
    }

    /** Used to suspend the thread on which {@link #run()} is called */
    private static final Object runWaiter = new Object();

    /**
     * Keep handling Binder connections until explicitly terminated.<br>
     * <br>
     * This really only suspends the main thread so the process doesn't terminate at the end of
     * the main() implementation. The initial Binder broadcasts and the connections themselves
     * are handled in background threads created by the RootIPC instances created when
     * {@link #register(String, IBinder, int)} is called, and re-broadcasting those interfaces
     * is done by the internal Binder interface registered by
     * {@link #daemonize(String, int, boolean, OnExitListener)}.<br>
     * <br>
     * This method never returns!
     */
    @SuppressWarnings("InfiniteLoopStatement")
    public static void run() {
        synchronized (runWaiter) {
            while (true) {
                try {
                    runWaiter.wait();
                } catch (InterruptedException e) {
                    // no action required
                }
            }
        }
    }

    /**
     * Cleanup and terminate.<br>
     * <br>
     * This method should be called from a Binder interface implementation, and will trigger a
     * RemoteException on the other end.
     */
    public static void exit() {
        /* We do not return from the run() call but immediately exit, so if this method is called
           from inside a Binder interface method implementation, the process has died before the
           IPC call to terminate completes on the other end. This triggers a RemoteException so the
           other end can easily verify this process has terminated. It also prevents a
           race-condition between the old service dying and new service registering. Additionally it
           saves us from having to use another synchronizer to cope with a termination request
           coming in from another daemon launch before run() is actually called. */

        Logger.dp(LOG_PREFIX, "Exiting");

        try {
            if (onExitListener != null) {
                onExitListener.onExit();
            }
        } catch (Exception e) {
            Logger.ex(e);
        }

        try {
            /* Unlike when using RootJava.getLaunchScript(), RootDaemon.getLaunchScript() does
               not clean up our relocated app_process binary after executing (which might prevent
               the daemon from running due to a race-condition), so we clean it up here. */
            File app_process = new File("/proc/self/exe").getCanonicalFile();
            if (app_process.exists() && !app_process.getAbsolutePath().startsWith("/system/bin/")) {
                //noinspection ResultOfMethodCallIgnored
                app_process.delete();

                // See if we can also find a copy of the daemonize binary
                String daemonize_path = app_process.getAbsolutePath();
                daemonize_path = daemonize_path.replace(".app_process32_", ".daemonize_");
                daemonize_path = daemonize_path.replace(".app_process64_", ".daemonize_");
                File daemonize = new File(daemonize_path);
                if (daemonize.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    daemonize.delete();
                }
            }
        } catch (IOException e) {
            // should never actually happen
        }

        // Goodbye!
        System.exit(0);
    }
}
