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

package eu.chainfire.librootjavadaemon_example;

import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.RootIPCReceiver;
import eu.chainfire.librootjavadaemon.RootDaemon;
import eu.chainfire.librootjavadaemon_example.root.IIPC;
import eu.chainfire.librootjavadaemon_example.root.RootMain;
import eu.chainfire.libsuperuser.Debug;
import eu.chainfire.libsuperuser.Shell;

/*
    IMPORTANT: The code in this class is written to make it easier to understand how
    libRootJavaDaemon works. It takes some shortcuts for the sake of brevity. Long running code
    (such as executing commands in a root shell) is usually better placed inside a Service.

    You should read librootjava_example first, as it's comments provide basic information
    this example builds on.

 */
public class MainActivity extends AppCompatActivity {
    static {
        // librootjava's logger
        Logger.setLogTag("librootjavadaemon");
        Logger.setDebugLogging(BuildConfig.DEBUG);

        // libsuperuser's logger
        Debug.setDebug(BuildConfig.DEBUG);
    }

    private Button buttonLaunchDaemon = null;
    private Button buttonKillDaemon = null;
    private Button buttonKillUI = null;
    private TextView textView = null;
    private Shell.Interactive shell = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        buttonLaunchDaemon = findViewById(R.id.buttonLaunchDaemon);
        buttonKillDaemon = findViewById(R.id.buttonKillDaemon);
        buttonKillDaemon.setEnabled(false);
        buttonKillUI = findViewById(R.id.buttonKillUI);
        textView = findViewById(R.id.textView);
        textView.setHorizontallyScrolling(true);
        textView.setMovementMethod(new ScrollingMovementMethod());

        // See librootjava's example for further commentary on these two calls
        ipcReceiver.setContext(this);
        (new Thread(new Runnable() {
            @Override
            public void run() {
                RootDaemon.cleanupCache(MainActivity.this);
            }
        })).start();
    }

    @Override
    protected void onDestroy() {
        ipcReceiver.release();
        super.onDestroy();
    }

    private void uiLog(final String msg) {
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
            Logger.dp("IPC", "onConnect");
            uiLog("Connected to daemon");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    buttonKillDaemon.setEnabled(true);
                }
            });

            uiLog(String.format(Locale.ENGLISH, "Our pid: %d", android.os.Process.myPid()));
            try {
                uiLog(String.format(Locale.ENGLISH, "Daemon pid: %d", ipc.getPid()));
                uiLog(String.format(Locale.ENGLISH, "Daemon launched by pid: %d", ipc.getLaunchedByPid()));

                if (ipc.getLaunchedByPid() == android.os.Process.myPid()) {
                    uiLog("That is this process!");
                } else {
                    uiLog("That was another process!");
                }

                // we don't call disconnect() here because then the Kill Daemon button wouldn't work
            } catch (RemoteException e) {
                uiLog("RemoteException during IPC. Connection lost?");
                Logger.ex(e);
            }
        }

        @Override
        public void onDisconnect(IIPC ipc) {
            uiLog("Disconnected from daemon");
            uiLog("");
            Logger.dp("IPC", "onDisconnect");
        }
    };

    public void onLaunchDaemonClick(View v) {
        buttonLaunchDaemon.setEnabled(false);

        // Get daemon launch script
        List<String> script = RootMain.getLaunchScript(this);

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
                                        buttonLaunchDaemon.setEnabled(true);
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
                        buttonLaunchDaemon.setEnabled(true);
                    }
                });
            }

            @Override
            public void onLine(String line) {
            }
        });
    }

    public void onKillDaemonClick(View v) {
        uiLog("Terminating daemon...");
        IIPC ipc = ipcReceiver.getIPC();
        if (ipc != null) {
            try {
                // Tell the other end to terminate
                ipc.terminate();

                // If no RemoteException was thrown, the daemon wasn't killed
                uiLog("Terminating daemon failed");
            } catch (RemoteException e) {
                // As the daemon process dies, the Binder link dies, and a RemoteException thrown
                uiLog("Daemon terminated");

                buttonKillDaemon.setEnabled(false);
            }
        } else {
            uiLog("Not connected to daemon");
        }
    }

    public void onKillUIClick(View v) {
        // Make sure our process dies and a new process is created when you launch this app
        // again. This is to demonstrate the daemon stays alive regardless of the state of our
        // UI process.
        System.exit(0);
    }
}
