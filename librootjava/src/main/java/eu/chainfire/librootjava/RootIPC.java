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

package eu.chainfire.librootjava;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * Binder-based IPC server for the root process<br>
 * <br>
 * This class wraps the supplied Binder interface in its own helper (primarily to keep track of
 * the non-root processes' state), and broadcasts the wrapper to the non-root process.
 *
 * @see RootIPCReceiver
 */
@SuppressWarnings({"unused", "WeakerAccess", "BooleanMethodIsAlwaysInverted", "FieldCanBeLocal", "Convert2Diamond"})
public class RootIPC {
    /**
     * The non-root process did not connect back to us in a timely fashion after the broadcast
     */
    public static class TimeoutException extends Exception {
        public TimeoutException(String message) {
            super(message);
        }
    }

    private final String packageName;
    private final IBinder userIPC;
    private final int code;

    private final Object helloWaiter = new Object();
    private final Object byeWaiter = new Object();

    private class Connection {
        private final IBinder binder;
        private final IBinder.DeathRecipient deathRecipient;

        public Connection(IBinder binder, IBinder.DeathRecipient deathRecipient) {
            this.binder = binder;
            this.deathRecipient = deathRecipient;
        }

        public IBinder getBinder() {
            return binder;
        }

        public IBinder.DeathRecipient getDeathRecipient() {
            return deathRecipient;
        }
    }

    private final List<Connection> connections = new ArrayList<Connection>();
    private volatile boolean connectionSeen = false;

    /**
     * Wrap the supplied Binder, send it to the target package, and optionally wait until a session is connected and completed
     *
     * @param packageName Package name of process to send Binder to. Use BuildConfig.APPLICATION_ID (double check you're importing the correct BuildConfig!) for convenience
     * @param ipc Binder object to wrap and send out
     * @param code User-value, should be unique per Binder
     * @param connection_timeout_ms How long to wait for the other process to initiate the connection, -1 for default, 0 to wait forever (if blocking)
     * @param blocking If a connection is made, do not return until the other process disconnects or dies,
     * @throws TimeoutException If the connection times out
     */
    public RootIPC(String packageName, IBinder ipc, int code, int connection_timeout_ms, boolean blocking) throws TimeoutException {
        this.packageName = packageName;
        userIPC = ipc;
        this.code = code;
        broadcastIPC();

        if (connection_timeout_ms < 0) connection_timeout_ms = 30 * 1000;
        if (connection_timeout_ms > 0) {
            synchronized (helloWaiter) {
                if (!haveClientsConnected()) {
                    try {
                        helloWaiter.wait(connection_timeout_ms);
                    } catch (InterruptedException e) {
                        // expected, do nothing
                    }
                }
                if (!haveClientsConnected()) {
                    throw new TimeoutException("librootjava: timeout waiting for IPC connection");
                }
            }
        }

        if (blocking) {
            // this will loop until all connections have said goodbye or their processes have died
            synchronized (byeWaiter) {
                while (!haveAllClientsDisconnected()) {
                    try {
                        byeWaiter.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Do we or did we have a connection?
     *
     * @return if client has connected
     */
    public boolean haveClientsConnected() {
        synchronized (connections) {
            return connectionSeen;
        }
    }

    /**
     * Have we had connection and are they gone now?
     *
     * @return if client has exited
     */
    public boolean haveAllClientsDisconnected() {
        synchronized (connections) {
            return connectionSeen && (getConnectionCount() == 0);
        }
    }

    /**
     * Wrap the binder in an intent and broadcast it to packageName
     *
     * Uses the reflected sendBroadcast method that doesn't require us to have a context
     *
     * You may call this manually to re-broadcast the interface
     */
    public void broadcastIPC() {
        Intent intent = new Intent();
        intent.setPackage(packageName);
        intent.setAction(RootIPCReceiver.BROADCAST_ACTION);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        Bundle bundle = new Bundle();
        bundle.putBinder(RootIPCReceiver.BROADCAST_BINDER, binder);
        bundle.putInt(RootIPCReceiver.BROADCAST_CODE, code);
        intent.putExtra(RootIPCReceiver.BROADCAST_EXTRA, bundle);

        Reflection.sendBroadcast(intent);
    }

    /**
     * Get number of connected clients
     *
     * @return number of connected clients
     */
    public int getConnectionCount() {
        synchronized (connections) {
            pruneConnections();
            return connections.size();
        }
    }

    /**
     * Remove dead connections from our records. This should never actually have any effect due
     * to our DeathRecipients.
     */
    private void pruneConnections() {
        synchronized (connections) {
            if (connections.size() == 0) return;

            for (int i = connections.size() - 1; i >= 0; i--) {
                Connection conn = connections.get(i);
                if (!conn.getBinder().isBinderAlive()) {
                    connections.remove(i);
                }
            }

            if (!connectionSeen && (connections.size() > 0)) {
                connectionSeen = true;
                synchronized (helloWaiter) {
                    helloWaiter.notifyAll();
                }
            }

            if (connections.size() == 0) {
                synchronized (byeWaiter) {
                    byeWaiter.notifyAll();
                }
            }
        }
    }

    /**
     * Get Connection based on IBinder
     * @param binder IBinder to find Connection for
     * @return Connection or null
     */
    private Connection getConnection(IBinder binder) {
        synchronized (connections) {
            pruneConnections();
            for (Connection conn : connections) {
                if (conn.getBinder() == binder) {
                    return conn;
                }
            }
            return null;
        }
    }

    /**
     * Get Connection based on DeathRecipient
     * @param deathRecipient DeathRecipient to find Connection for
     * @return Connection or null
     */
    private Connection getConnection(IBinder.DeathRecipient deathRecipient) {
        synchronized (connections) {
            pruneConnections();
            for (Connection conn : connections) {
                if (conn.getDeathRecipient() == deathRecipient) {
                    return conn;
                }
            }
            return null;
        }
    }

    /**
     * Our own wrapper around the supplied Binder interface, which allows us to keep track of
     * non-root process' state and connection state.
     */
    private final IBinder binder = new IRootIPC.Stub() {
        @Override
        public void hello(IBinder self) {
            // incoming connection from the non-root process

            // receive notifications when that process dies
            DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Connection conn = getConnection(this);
                    if (conn != null) {
                        bye(conn.getBinder());
                    }
                }
            };
            try {
                self.linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                // it's already dead!
                self = null;
            }

            // if still alive, record the connection
            if (self != null) {
                synchronized (connections) {
                    connections.add(new Connection(self, deathRecipient));
                    connectionSeen = true;
                }
                synchronized (helloWaiter) {
                    helloWaiter.notifyAll();
                }
            }
        }

        @Override
        public IBinder getUserIPC() {
            // this is the originally supplied Binder interface
            return userIPC;
        }

        @Override
        public void bye(IBinder self) {
            // The non-root process is either informing us it is going away, or it already died
            synchronized (connections) {
                Connection conn = getConnection(self);
                if (conn != null) {
                    try {
                        conn.getBinder().unlinkToDeath(conn.getDeathRecipient(), 0);
                    } catch (Exception e) {
                        // no action required
                    }
                    connections.remove(conn);
                }
            }
            synchronized (byeWaiter) {
                byeWaiter.notifyAll();
            }
        }
    };
}
