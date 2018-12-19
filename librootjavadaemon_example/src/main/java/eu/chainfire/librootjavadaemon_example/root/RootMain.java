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

package eu.chainfire.librootjavadaemon_example.root;

import android.content.Context;
import android.os.IBinder;

import java.util.List;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.RootJava;
import eu.chainfire.librootjavadaemon.RootDaemon;
import eu.chainfire.librootjavadaemon_example.BuildConfig;
import eu.chainfire.libsuperuser.Debug;

/**
 * This class' main method will be launched as root. You can access any other class from your
 * package, but not instances - this is a separate process from the UI.
 */
public class RootMain {
    /**
     * Call this from non-root code to generate the script to launch the root code as a daemon
     *
     * @param context Application or activity context
     * @return Script
     */
    public static List<String> getLaunchScript(Context context) {
        // We pass the daemon our PID so we know which process started it
        String[] params = new String[] {
                String.valueOf(android.os.Process.myPid())
        };

        // We call RootDaemon's getLaunchScript() rather than RootJava's
        return RootDaemon.getLaunchScript(context, RootMain.class, null, null, params, context.getPackageName() + ":root");
    }

    /**
     * Entry point into code running as root
     *
     * @param args Passed arguments
     */
    public static void main(String[] args) {
        // Setup logging - note that these logs do show up in (adb) logcat, but they do not show up
        // in AndroidStudio where the logs from the non-root part of your app are displayed!
        Logger.setLogTag("librootjava:root");
        Logger.setDebugLogging(BuildConfig.DEBUG);

        // Setup libsuperuser (required for this example code, but not required to use librootjava in general)
        Debug.setDebug(BuildConfig.DEBUG);
        Debug.setLogTypeEnabled(Debug.LOG_GENERAL | Debug.LOG_COMMAND, true);
        Debug.setLogTypeEnabled(Debug.LOG_OUTPUT, true);
        Debug.setSanityChecksEnabled(false); // don't complain about calls on the main thread

        // Log uncaught exceptions rather than just sending them to stderr
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Logger.dp("EXCEPTION", "%s", throwable.getClass().getName());
                if (oldHandler != null) {
                    oldHandler.uncaughtException(thread, throwable);
                } else {
                    System.exit(1);
                }
            }
        });

        // Create instance and pass control over to run(), so we don't have to static everything
        new RootMain().run(args);
    }

    private volatile int launchedBy;

    /**
     * All your code here. Execution ends when this method returns.
     *
     * @param args Passed arguments
     */
    private void run(String[] args) {
        // Become the daemon
        RootDaemon.daemonize(BuildConfig.APPLICATION_ID, 0, false, null);

        // Restore original LD_LIBRARY_PATH
        RootJava.restoreOriginalLdLibraryPath();

        // Interpret prepended arguments
        if ((args == null) || (args.length == 0)) {
            // We expect at least one argument, PID of who started the daemon
            return;
        }

        try {
            launchedBy = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            // we expected an int
            return;
        }

        Logger.d("START");

        // Implement our IPC class
        IBinder ipc = new IIPC.Stub() {
            @Override
            public int getPid() {
                // Our Pid
                return android.os.Process.myPid();
            }

            @Override
            public int getLaunchedByPid() {
                // Pid of the process that launched the daemon
                return launchedBy;
            }

            @Override
            public void terminate() {
                // In daemon mode, this root process keeps running until you manually stop it,
                // an unhandled exception occurs, or Linux kills it for some reason.
                // Note that this will always throw a RemoteException on the other end!
                RootDaemon.exit();
            }
        };

        // We use this instead of 'new RootIPC(...)' in daemon mode
        RootDaemon.register(BuildConfig.APPLICATION_ID, ipc, 0);

        // Keep serving our IPC interface until terminated
        RootDaemon.run();

        // RootDaemon.run() never actually returns so this will never be executed
        Logger.d("END");
    }
}
