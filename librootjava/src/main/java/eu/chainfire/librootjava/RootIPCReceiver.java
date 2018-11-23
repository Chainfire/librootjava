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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.ref.WeakReference;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Binder-based IPC receiver for the non-root process<br>
 * <br>
 * This class handles receiving the (wrapped) Binder interface, casting it to your own interface,
 * and handling connection state.
 *
 * @see RootIPC
 *
 * @param <T> Your IPC interface
 */
@SuppressWarnings({"unused", "WeakerAccess", "Convert2Diamond", "TryWithIdenticalCatches"})
public abstract class RootIPCReceiver<T> {
    /**
     * Callback for when the IPC interface becomes available.<br>
     * <br>
     * This callback is always called from a background thread, it is safe to perform blocking
     * operations here.<br>
     * <br>
     * If another thread calls {@link #release()} or {@link #disconnect()}, the connection is not
     * actually aborted until this callback returns. You can check for this state with
     * {@link #isDisconnectScheduled()}.<br>
     * <br>
     * This connection may still be severed at any time due to the process on the other end
     * dieing, any calls on the IPC interface will then throw a RemoteException.<br>
     * <br>
     * Do not store a reference to ipc, but use the {@link #getIPC()} method to retrieve it when
     * you need it outside of this callback.
     *
     * @param ipc The Binder interface you declared in an aidl and passed to RootIPC on the root side
     */
    public abstract void onConnect(T ipc);

    /**
     * Callback for when the IPC interface is going (or has gone) away.<br>
     * <br>
     * The ipc parameter is there for reference, but it may not be safe to use. Avoid doing so.<br>
     *
     * @param ipc The Binder interface you declared in an aidl and passed to RootIPC on the root side
     */
    public abstract void onDisconnect(T ipc);

    static final String BROADCAST_ACTION = "eu.chainfire.librootjava.RootIPCReceiver.BROADCAST";
    static final String BROADCAST_EXTRA = "eu.chainfire.librootjava.RootIPCReceiver.BROADCAST.EXTRA";
    static final String BROADCAST_BINDER = "binder";
    static final String BROADCAST_CODE = "code";

    private final HandlerThread handlerThread;
    private final Handler handler;

    private final int code;
    private final Class<T> clazz;
    private final IBinder self = new Binder();
    private final Object binderSync = new Object();
    private final Object eventSync = new Object();

    private volatile WeakReference<Context> context;
    private volatile IBinder binder = null;
    private volatile IRootIPC ipc = null;
    private volatile T userIPC = null;
    private volatile boolean inEvent = false;
    private volatile boolean disconnectAfterEvent = false;

    private final IntentFilter filter = new IntentFilter(BROADCAST_ACTION);

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            synchronized (binderSync) {
                clearBinder();
                binderSync.notifyAll();
            }
        }
    };

    /**
     * Actual BroadcastReceiver that handles receiving the IRootIPC interface, sets up
     * an on-death callback, and says hello to the other side.
     */
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IBinder received = null;

            if ((intent.getAction() != null) && intent.getAction().equals(BROADCAST_ACTION)) {
                Bundle bundle = intent.getBundleExtra(BROADCAST_EXTRA);
                received = bundle.getBinder(BROADCAST_BINDER);
                int code = bundle.getInt(BROADCAST_CODE);
                if ((code == RootIPCReceiver.this.code) && (received != null)) {
                    try {
                        received.linkToDeath(deathRecipient, 0);
                    } catch (RemoteException e) {
                        received = null;
                    }
                } else {
                    received = null;
                }
            }

            if (received != null) {
                synchronized (binderSync) {
                    binder = received;
                    ipc = IRootIPC.Stub.asInterface(binder);
                    try {
                        userIPC = getInterfaceFromBinder(ipc.getUserIPC());
                    } catch (RemoteException e) {
                        Logger.ex(e);
                    }
                    try {
                        // we send over our own Binder that the other end can linkToDeath with
                        ipc.hello(self);

                        // schedule a call to doOnConnectRunnable so we stop blocking the receiver
                        handler.post(onConnectRunnable);
                    } catch (RemoteException e) {
                        Logger.ex(e);
                    }
                    binderSync.notifyAll();
                }
            }
        }
    };

    private final Runnable onConnectRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (binderSync) {
                doOnConnect();
            }
        }
    };

    /**
     * Convenience constructor that calls the proper constructor.
     *
     * @see #RootIPCReceiver(Context, int, Class)
     *
     * @param context Context to attach receiver to
     * @param code User-value, should be unique per Binder
     */
    public RootIPCReceiver(Context context, int code) {
        this(context, code, null);
    }

    /**
     * Construct the RootIPCReceiver object. This is an abstract class, you will need to implement
     * the {@link #onConnect(Object)} and {@link #onDisconnect(Object)} methods.<br>
     * <br>
     * The clazz parameter may be omitted or null, in which case it is attempted to determine the
     * class of the interface automatically. That usually works, but in some edge-cases you will
     * have to pass YourIPCInterface.class explicitly.<br>
     * <br>
     * Note that if this constructor is called in the class definition of a context (such as an
     * Activity), the context passed will not be a proper context, and you will need to call
     * {@link #setContext(Context)} in something like onCreate or the receiver will not function.
     *
     * <pre>
     * {@code
     * RootIPCReceiver<YourIPCInterface> receiver = new RootIPCReceiver<YourIPCInterface>(context, code) {
     *     public void onConnect(YourIPCInterface ipc) {
     *        ...
     *     }
     *     public void onDisconnect(YourIPCInterface ipc) {
     *        ...
     *     }
     * }
     *
     * RootIPCReceiver<YourIPCInterface> receiver = new RootIPCReceiver<YourIPCInterface>(context, code, YourIPCInterface.class) {
     *      ...
     * }
     * }
     * </pre>
     *
     * @param context Context to attach receiver to
     * @param code User-value, should be unique per Binder
     * @param clazz Class of the IPC interface, or null to attempt to determine
     */
    @SuppressWarnings("unchecked")
    public RootIPCReceiver(Context context, int code, Class<T> clazz) {
        if (clazz == null) {
            // This trick only works because this is an abstract class
            // Not inside the Reflection class because this is a well known Java trick that
            // doesn't depend on Android-specific classes.
            Type superClass = getClass().getGenericSuperclass();
            Type tType = ((ParameterizedType)superClass).getActualTypeArguments()[0];
            this.clazz = (Class<T>)tType;
        } else {
            this.clazz = clazz;
        }
        this.code = code;
        handlerThread = new HandlerThread("librootjava:RootIPCReceiver#" + String.valueOf(code));
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        setContext(context);
    }

    /**
     * Updates the context our BroadcastReceiver should attach to
     *
     * @param context Context to attach receiver to
     */
    public void setContext(Context context) {
        if (this.context != null) {
            Context oldContext = this.context.get();
            if (oldContext != null) {
                oldContext.unregisterReceiver(receiver);
            }
        }
        this.context = null;
        if (context != null) {
            if (context instanceof ContextWrapper) {
                // prevent NPE if constructed in activity class definition
                if (((ContextWrapper)context).getBaseContext() == null) return;
            }
            this.context = new WeakReference<Context>(context);
            context.registerReceiver(receiver, filter, null, handler);
        }
    }

    /**
     * The equivalent of T.Stub.asInterface(binder) using reflection, because it is not
     * possible to write that code here.
     *
     * @param binder Binder proxy to retrieve interface from
     * @return T (proxy) instance or null
     */
    private T getInterfaceFromBinder(IBinder binder) {
        return (new Reflection.InterfaceRetriever<T>()).getInterfaceFromBinder(clazz, binder);
    }

    private void doOnConnect() {
        // must be called inside synchronized(binderSync)
        if ((binder != null) && (userIPC != null)) {
            synchronized (eventSync) {
                disconnectAfterEvent = false;
                inEvent = true;
            }
            onConnect(userIPC);
            synchronized (eventSync) {
                inEvent = false;
                if (disconnectAfterEvent) {
                    disconnect();
                }
            }
        }
    }

    private void doOnDisconnect() {
        // must be called inside synchronized(binderSync)
        if ((binder != null) && (userIPC != null)) {
            // we don't need to set inEvent here, only applicable to onConnect()
            onDisconnect(userIPC);
        }
    }

    private void clearBinder() {
        // must be called inside synchronized(binderSync)
        doOnDisconnect();
        if (binder != null) {
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (Exception e) {
                // no action required
            }
        }
        binder = null;
        ipc = null;
        userIPC = null;
    }

    private boolean isInEvent() {
        synchronized (eventSync) {
            return inEvent;
        }
    }

    /**
     * Retrieve connection status<br>
     * <br>
     * Note that this may return false if a disconnect is schedule but we are actually still
     * connected.
     *
     * @return Connection available
     */
    public boolean isConnected() {
        return (getIPC() != null);
    }

    /**
     * @return If a disconnect is scheduled
     */
    public boolean isDisconnectScheduled() {
        synchronized (eventSync) {
            if (disconnectAfterEvent) {
                return true;
            }
        }
        return false;
    }

    /**
     * If connected, disconnect or schedule a disconnect
     */
    public void disconnect() {
        synchronized (eventSync) {
            if (inEvent) {
                disconnectAfterEvent = true;
                return;
            }
        }

        synchronized (binderSync) {
            if (ipc != null) {
                try {
                    ipc.bye(self);
                } catch (RemoteException e) {
                    // peer left without saying bye, rude!
                }
            }
            clearBinder();
        }
    }

    /**
     * Release all resources and (schedule a) disconnect if connected.<br>
     * <br>
     * Should be called when the context goes away, such as in on onDestroy()
     */
    public void release() {
        disconnect();
        if (this.context != null) {
            Context context = this.context.get();
            if (context != null) {
                context.unregisterReceiver(receiver);
            }
        }
        handlerThread.quitSafely();
    }

    /**
     * Retrieve IPC interface immediately
     *
     * @return Your IPC interface if connected, null otherwise
     */
    public T getIPC() {
        if (isDisconnectScheduled()) return null;
        if (isInEvent()) {
            // otherwise this call would deadlock when called from onConnect()
            // we know userIPC is valid in this case
            return userIPC;
        }

        synchronized (binderSync) {
            if (binder != null) {
                if (!binder.isBinderAlive()) {
                    clearBinder();
                }
            }
            if ((binder != null) && (userIPC != null)) {
                return userIPC;
            }
        }
        return null;
    }

    /**
     * Retrieve IPC interface, waiting for it in case it isn't available
     *
     * @param timeout_ms Time to wait for a connection (if &gt; 0)
     * @return Your IPC interface if connected, null otherwise
     */
    public T getIPC(int timeout_ms) {
        if (isDisconnectScheduled()) return null;
        if (isInEvent()) {
            // otherwise this call would deadlock when called from onConnect()
            // we know userIPC is valid in this case
            return userIPC;
        }

        if (timeout_ms <= 0) return getIPC();

        synchronized (binderSync) {
            if (binder == null) {
                try {
                    binderSync.wait(timeout_ms);
                } catch (InterruptedException e) {
                    // no action required
                }
            }
        }

        return getIPC();
    }
}
