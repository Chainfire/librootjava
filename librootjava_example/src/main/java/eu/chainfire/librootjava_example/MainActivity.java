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

package eu.chainfire.librootjava_example;

import android.graphics.Bitmap;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.RootIPCReceiver;
import eu.chainfire.librootjava.RootJava;
import eu.chainfire.librootjava_example.root.IIPC;
import eu.chainfire.librootjava_example.root.IPingCallback;
import eu.chainfire.librootjava_example.root.PassedData;
import eu.chainfire.librootjava_example.root.RemoteInputStream;
import eu.chainfire.librootjava_example.root.RootMain;
import eu.chainfire.libsuperuser.Debug;
import eu.chainfire.libsuperuser.Shell;

/*
    IMPORTANT: The code in this class is written to make it easier to understand how libRootJava
    works. It takes some shortcuts for the sake of brevity. Long running code (such as executing
    commands in a root shell) is usually better placed inside a Service.

 */
public class MainActivity extends AppCompatActivity {
    static {
        // Application::onCreate would be a better place for this

        // librootjava's logger
        Logger.setLogTag("librootjava");
        Logger.setDebugLogging(BuildConfig.DEBUG);

        // libsuperuser's logger
        Debug.setDebug(BuildConfig.DEBUG);
    }

    private Button button = null;
    private TextView textView = null;
    private ImageView imageView = null;
    private Shell.Interactive shell = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        button = findViewById(R.id.button);
        textView = findViewById(R.id.textView);
        textView.setHorizontallyScrolling(true);
        textView.setMovementMethod(new ScrollingMovementMethod());
        imageView = findViewById(R.id.imageView);

        /* As we declare and construct ipcReceiver in the class definition rather than constructing
           it inside a method (in this example), the context passed to its constructor is an empty
           wrapper. We need to update it to a proper context, so we may actually receive the Binder
           object from our root code. */
        ipcReceiver.setContext(this);

        /* Cleanup leftover files from our cache directory. This is not exactly an elegant way to
           do it, but it illustrates that this should be done off of the main UI thread. */
        (new Thread(new Runnable() {
            @Override
            public void run() {
                RootJava.cleanupCache(MainActivity.this);
            }
        })).start();
    }

    @Override
    protected void onDestroy() {
        /* Disconnect and release resources. If onConnect() is still running, disconnect will occur
           after its completion, but this call will return immediately. */
        ipcReceiver.release();

        super.onDestroy();
    }

    private void uiLog(final String msg) {
        // Log a string to our textView, making sure the addition runs on the UI thread
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.append(msg + "\n");
            }
        });
    }

    private final RootIPCReceiver<IIPC> ipcReceiver = new RootIPCReceiver<IIPC>(this, 0) {
        @Override
        public void onConnect(IIPC ipc) {
            /* This is always called from a background thread, so you can do blocking operations
               here without issue. Keep in mind that due to this, the activity may actually be
               destroyed while this callback is still running.

               As always long-running code should be executed in a service rather than in an
               activity, but that is beyond the scope of this example.

               If release() is called from onDestroy, this will schedule a disconnect, and
               you can use the isDisconnectScheduled() call as a trigger to abort.

               If you're done with the IPC interface at the end of this method, call disconnect().
               You shouldn't store the interface itself, but if you don't disconnect() you can call
               RootIPCReceiver.getIPC() later.
            */
            Logger.dp("IPC", "onConnect");
            try {
                uiLog("");
                uiLog("Connected");

                // Get the other end's PID
                uiLog("");
                uiLog(String.format(Locale.ENGLISH, "Remote pid: %d", ipc.getPid()));
                uiLog("");

                // This file is actually readable directly from your app, but it's a nice short
                // text file that serves well as an example
                uiLog("Example InputStream:");
                InputStream is = RemoteInputStream.toInputStream(ipc.openFileForReading("/system/bin/am"));
                if (is != null) {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(is));
                        try {
                            while (br.ready()) {
                                uiLog(br.readLine());
                            }
                        } catch (IOException e) {
                            uiLog(e.getMessage());
                            Logger.ex(e);
                        }
                    } finally {
                        try {
                            is.close();
                        } catch (IOException e) {
                            // no action required
                        }
                    }
                }
                uiLog("");

                // Receive an automatically reconstructed PassedObject. This is a copy of the
                // object on the other end, so changing it here does not change it there.
                PassedData pd = ipc.getSomeData();
                uiLog(String.format(Locale.ENGLISH, "getSomeData(): %d %d %s", pd.getA(), pd.getB(), pd.getC()));

                // Run a command on the root end and get the output back
                List<String> output = ipc.run("ls -l /init");
                if (output != null) {
                    for (String line : output) {
                        // should show the same output as exampleWork1 and exampleWork2
                        uiLog("exampleWorkX: " + line);
                    }
                }

                // Get our icon through root
                final Bitmap bitmap = ipc.getIcon();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        imageView.setImageBitmap(bitmap);
                    }
                });

                // Ping-pong, get some thread info through a callback
                final long ipcThreadId = Thread.currentThread().getId();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        uiLog(String.format(Locale.ENGLISH, "Ping: thisUI[%d] thisIPC[%d]", Thread.currentThread().getId(), ipcThreadId));
                    }
                });
                // Note that we implement IPingCallback.Stub rather than IPingCallback
                ipc.ping(new IPingCallback.Stub() {
                    /* The pong callback may be executed on the same thread as the ping call is
                       made, but only when the root end calls the callback while the ping call is
                       still blocking. It is best to assume it will run on a different thread and
                       guard variable and method access accordingly.

                       In this example you are likely to see thisCallback[%d] returning the same
                       value for as thisIPC[%d] the first pong, and a different value the second
                       pong. */
                    @Override
                    public void pong(long rootMainThreadId, long rootCallThreadId) {
                        uiLog(String.format(Locale.ENGLISH, "Pong: rootMain[%d] rootCall[%d] thisCallback[%d]", rootMainThreadId, rootCallThreadId, Thread.currentThread().getId()));
                    }
                });

                try {
                    // give the root end some time to send pong replies
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // no action required
                }

                // Our work here is done
                disconnect();
            } catch (RemoteException e) {
                uiLog("RemoteException during IPC. Connection lost?");
                Logger.ex(e);
            }
        }

        @Override
        public void onDisconnect(IIPC ipc) {
            // May or may not be called from a background thread
            uiLog("");
            uiLog("Disconnected");
            Logger.dp("IPC", "onDisconnect");
        }
    };

    public void onRunClick(View v) {
        button.setEnabled(false);

        uiLog("Executing script:");
        List<String> script = RootMain.getLaunchScript(this, null, null);
        for (String line : script) {
            Logger.d("%s", line);
            uiLog(line);
        }
        uiLog("");

        // Open a root shell if we don't have one yet
        if ((shell == null) || !shell.isRunning()) {
            shell = (new Shell.Builder())
                    .useSU()
                    .open(new Shell.OnCommandResultListener() {
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            if (exitCode != SHELL_RUNNING) {
                                // we couldn't open the shell, enable the button
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        button.setEnabled(true);
                                    }
                                });
                            }
                        }
                    });
        }

        // Execute the script (asynchronously)
        shell.addCommand(script, 0, new Shell.OnCommandLineListener() {
            @Override
            public void onCommandResult(int commandCode, int exitCode) {
                // execution finished, enable the button
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        button.setEnabled(true);
                    }
                });
            }

            @Override
            public void onLine(String line) {
                // we receive the output of exampleWork1/2 here
                uiLog(line);
            }
        });

        /*
            If this method was not running on the main thread, and you wanted to use the IPC class
            serially rather than using the onConnect callback, you could do it like this:

            IIPC ipc = ipcReceiver.getIPC(30 * 1000);
            if (ipc != null) {
                int remotePid = ipc.getPid();
                // ...
                ipc.disconnect();
            }

         */
    }
}
