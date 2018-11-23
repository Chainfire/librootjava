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

package eu.chainfire.librootjava_example.root;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.RootIPC;
import eu.chainfire.librootjava.RootJava;
import eu.chainfire.librootjava_example.BuildConfig;
import eu.chainfire.librootjava_example.R;
import eu.chainfire.libsuperuser.Debug;
import eu.chainfire.libsuperuser.Shell;

/**
 * This class' main method will be launched as root. You can access any other class from your
 * package, but not instances - this is a separate process from the UI.
 */
@SuppressWarnings({"Convert2Diamond", "SdCardPath", "FieldCanBeLocal"})
public class RootMain {
    /**
     * Call this from non-root code to generate the script to launch the root code
     *
     * @param context Application or activity context
     * @param params Parameters to pass
     * @param libs Native libraries to pass (no extension), for example libmynativecode
     * @return Script
     */
    public static List<String> getLaunchScript(Context context, String[] params, String[] libs) {
        // Add some of our parameters to whatever has been passed in
        // Doing it this way is an example of separating parameters you need every time from
        // parameters that may differ based on what the app is doing.
        // If we didn't do this, we'd use params directly in the getLaunchScript call below
        List<String> paramList = new ArrayList<String>();

        // Path to our APK - this is just an example of parameter passing, there are several ways
        // to get the path of the APK from the code running as root without this.
        paramList.add(context.getPackageCodePath());

        // Add paths to our native libraries
        if (libs != null) {
            for (String lib : libs) {
                paramList.add(RootJava.getLibraryPath(context, lib));
            }
        }

        // Originally passed parameters
        if (params != null) {
            Collections.addAll(paramList, params);
        }

        // Create actual script
        return RootJava.getLaunchScript(context, RootMain.class, null, null, paramList.toArray(new String[0]), context.getPackageName() + ":root");
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

    private String pathToAPK = null;
    private Context context = null;

    /**
     * All your code here. Execution ends when this method returns.
     *
     * @param args Passed arguments
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private void run(String[] args) {
        Logger.d("START");

        // Interpret prepended arguments
        if ((args == null) || (args.length == 0)) {
            // We expect at least one argument, location of our APK
            return;
        }

        pathToAPK = args[0];
        Logger.d("APK [%s]", pathToAPK);

        int usedArgs = 1;
        for (int i = usedArgs; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("/") && (arg.endsWith(".so"))) {
                // assume this is the path to a native library we want to load
                // this has to be done before you can make any JNI calls
                Logger.d("Loading [%s]", arg);
                Runtime.getRuntime().load(arg);
                usedArgs = i + 1;
            }
        }

        // Make sure LD_LIBRARY_PATH is sane again, so we don't run into issues running shell commands
        RootJava.restoreOriginalLdLibraryPath();

        // As we made sure to prepend APK and native libs, you can do other parameter parsing here like so
        for (int i = usedArgs; i < args.length; i++) {
            // do something with args[i]
            Logger.dp("ARGS", "[%d] [%s]", i - usedArgs, args[i]);
        }

        // Grab a (limited) context
        context = RootJava.getSystemContext();

        Logger.d("MAIN SECTION");

        // These two examples write to stdout, for the non-root code to read
        exampleWork1();
        exampleWork2();

        // This is the binder-based IPC example
        exampleWorkIPC();

        // Done

        Logger.d("END");
    }

    private void exampleWork1() {
        Logger.d("exampleWork1()");

        /* Run a shell command with libsuperuser (basic) and write the output to stdout
           We use SH instead of SU because we're obviously already root */
        for (String line : Shell.SH.run("ls -l /init")) {
            System.out.println("exampleWork1: " + line);
        }

        System.out.flush();
    }

    private void exampleWork2() {
        Logger.d("exampleWork2()");

        /* Run shell command with libsuperuser (extended), and write the output to stdout

           libsuperuser does not normally allow calls on the main thread, to prevent its users
           from blocking Android's UI. This is not relevant here as there is no UI thread.

           The detection calls never trigger as the main thread does not have an associated Looper.
           However, if the RootJava::getSystemContext() call is used, a Looper is created, and the
           checks trigger again.

           For Shell.SH calls, calling Debug.setSanityChecksEnabled(false) (done in the main()
           method) is enough to bypass these checks, but it still causes issues for the
           Shell.Interactive class. The solution is to make sure the callbacks run in a separate
           thread of our own choosing.

           You would normally create just one of these in your initialization code and clean it
           up just before exiting, but we do it all in one go here to keep the sample
           self-contained.
       */

        // Create a thread with its own Looper
        HandlerThread handlerThread = new HandlerThread("libsuperuser");
        handlerThread.start();

        // Create a handler to post callbacks to that thread
        Handler handler = new Handler(handlerThread.getLooper());

        // Setup Shell.Interactive and run a command
        Shell.Interactive shell = (new Shell.Builder()).
                setAutoHandler(false).
                setHandler(handler).
                useSH().
                addCommand("ls -l /init", 0, new Shell.OnCommandResultListener() {
                    @Override
                    public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                        // executed in handlerThread
                        for (String line : output) {
                            System.out.println("exampleWork2: " + line);
                        }
                    }
                }).
                open();

        // For the sake of the example being self-contained, we wait until the commands are complete
        // and close the shell, but you'd normally keep a single instance around and use it to run
        // all your shell commands.
        shell.waitForIdle();
        shell.close();

        // Cleanup
        handlerThread.quitSafely();
        System.out.flush();
    }

    private void exampleWorkIPC() {
        Logger.d("exampleWorkIPC()");

        final long rootMainThreadId = Thread.currentThread().getId();

        /* The interface is defined in IPC.aidl (which imports the other aidls), appropriate Java
           code is generated only when built. So change the .aidl file first to add new method
           signatures, then do a build, and only then implement the new methods here.

           Note that Binder is pretty fast but memory copies may still be involved, so sending
           large amounts of raw data is not advisable. The maximum size of a Binder transaction
           (each call is a transaction) is currently also 1MB, so a call should never transfer
           anything bigger than that (and leave some room for the protocol). */

        /* Note that we implement IPC.Stub (generated by the build process from the aidl
           file) rather than IPC */
        IBinder ipc = new IIPC.Stub() {
            /* These calls are executed on a different thread, but not necessarily on the same
               one! Binder uses a thread pool, the calls could come in on any one of those
               threads. Guard variable access and method calls accordingly. */

            @Override
            public int getPid() {
                /* This should be pretty obvious. Primitives (int, long, char, boolean, etc),
                   String, CharSequence, List<> and Map<> can be used directly. */
                return android.os.Process.myPid();
            }

            @Override
            public List<String> run(String command) {
                /* Using a pre-opened Shell.Interactive instance (see exampleWork2() ) would be
                   quicker and more efficient. Doing it this way just to keep the example simpler. */
                return Shell.SH.run(command);
            }

            @Override
            public IRemoteInputStream openFileForReading(String filename) {
                /* Example of a passing an InputStream-like object, as we cannot pass a real
                   InputStream through Binder. See RemoteInputStream.java for further details.

                   You may be tempted to pass a ParcelFileDescriptor instead,
                   but note that those are still limited by SELinux contexts and file permissions.
                   If you were to pass a ParcelFileDescriptor referencing for example /init.rc,
                   which the non-root part of the app doesn't have access to, opening the
                   file would work fine on this end, but passing the descriptor would fail.

                   Another possibility is to create a pipe (see ParcelFileDescriptor again),
                   and send the contents of the file through that, then create an InputStream
                   on that pipe on the other end.

                   You wouldn't normally do something like this anyway, rather you would have
                   the non-root part of the app send a command to do something with a file on
                   this end, process it here, and return whether the operation was successful.

                   This serves as an example of how to pass a secondary interface and how to
                   wrap an object, rather than doing something practically useful.

                   We catch IOExceptions and return null instead. You should never throw an
                   exception in your Binder interface implementation, by far most of them do not
                   propagate, but some of them do, making handling weird. We do log the exception
                   to logcat so we can pick it up during development.
                */
                try {
                    return RemoteInputStream.fromInputStream(new FileInputStream(filename));
                } catch (IOException e) {
                    Logger.ex(e);
                    return null;
                }
            }

            @Override
            public PassedData getSomeData() {
                /* Any object that implements Parcelable can be passed through Binder.
                   Parcelable is really just a special (de)serialization interface.

                   The Android framework already defines a couple of Parcelables for you,
                   such as Bitmap (though of course Bitmaps larger than 1MB will fail).

                   The class itself needs a CREATOR defined (see PassedData.java), and an aidl
                   file declaring it (see PassedData.aidl). */
                return new PassedData(1, 2, "3");
            }

            @Override
            public Bitmap getIcon() {
                /* We can access our APK's resources and pass bitmaps around.

                   Of course APKs are just fancy ZIP files, and as we know the path to our APK
                   already as it was passed as a parameter (could also be retrieved here with
                   app.sourceDir), you could use ZipFile to read whatever you want from it as well.

                   We could have used context.getPackageManager().getApplicationIcon(), but the
                   latter method causes a SecurityException on some devices as we do not have an
                   active ProcessRecord.
                 */
                try {
                    Context ourPackage = RootJava.getPackageContext(BuildConfig.APPLICATION_ID);
                    Drawable icon = ourPackage.getResources().getDrawable(R.mipmap.ic_launcher, null);

                    Bitmap bitmap = Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
                    icon.draw(canvas);
                    return bitmap;
                } catch (Exception e) {
                    Logger.ex(e);
                }
                return null;
            }

            @Override
            public void ping(final IPingCallback pong) {
                /* This part blocks, and the callback is usually (but not guaranteed to?) executed
                   on the non-root end in the same thread that made the ping call. */
                try {
                    pong.pong(rootMainThreadId, Thread.currentThread().getId());
                } catch (RemoteException e) {
                    Logger.ex(e);
                }

                /* Do some work asynchronously and then call the callback, which on the non-root
                   end will be executed on a different thread than the one that made the ping
                   call. */
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // very useful work
                            Thread.sleep(250);
                        } catch (InterruptedException e) {
                            // no action required
                        }
                        try {
                            pong.pong(rootMainThreadId, Thread.currentThread().getId());
                        } catch (RemoteException e) {
                            Logger.ex(e);
                        }
                    }
                })).start();
            }
        };


        try {
            /* Send our IPC binder to the non-root part of the app, wait for a connection, and
               don't return until the app has disconnected.

               It is possible to register multiple interfaces (with different codes) in which
               case this connection-waiting/blocking mechanism is not ideal. You could run them
               each in a separate thread or implement your own handling. But really the easiest
               way is just to return other interfaces through methods of a single main interface,
               and register that one here.
            */
            new RootIPC(BuildConfig.APPLICATION_ID, ipc, 0, 30 * 1000, true);
        } catch (RootIPC.TimeoutException e) {
            /* It doesn't make sense to wait for very long, the broadcast is *not* sticky. RootIPCReceiver
               on the non-root side should connect immediately when it sees the broadcast. If it doesn't,
               it doesn't seem likely it ever will. */
            Logger.dp("IPC", "Non-root process did not connect in a timely fashion");
        }
    }
}
