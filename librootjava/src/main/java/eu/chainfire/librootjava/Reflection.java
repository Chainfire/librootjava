package eu.chainfire.librootjava;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-based methods are implemented here, so we have all the methods that are most
 * likely to break in one spot.
 */
class Reflection {
    private static final Object lock = new Object();

    /** Cache for getSystemContext() */
    @SuppressLint("StaticFieldLeak")
    private static Context systemContext = null;

    /**
     * Stability: unlikely to change, this implementation works from 1.6 through 9.0
     *
     * @see RootJava#getSystemContext()
     *
     * @return system context
     */
    @SuppressLint("PrivateApi")
    static Context getSystemContext() {
        synchronized (lock) {
            try {
                if (systemContext != null) {
                    return systemContext;
                }

                // a prepared Looper is required for the calls below to succeed
                if (Looper.getMainLooper() == null) {
                    try {
                        Looper.prepareMainLooper();
                    } catch (Exception e) {
                        Logger.ex(e);
                    }
                }

                Class<?> cActivityThread = Class.forName("android.app.ActivityThread");
                Method mSystemMain = cActivityThread.getMethod("systemMain");
                Method mGetSystemContext = cActivityThread.getMethod("getSystemContext");

                Object oActivityThread = mSystemMain.invoke(null);
                Object oContext = mGetSystemContext.invoke(oActivityThread);

                systemContext = (Context)oContext;
                return systemContext;
            } catch (Exception e) {
                Logger.ex(e);
                throw new RuntimeException("librootjava: unexpected exception in getSystemContext()");
            }
        }
    }

    /** Cache for getActivityManager() */
    private static Object oActivityManager = null;

    /**
     * Retrieve ActivityManager instance without needing a context
     *
     * @return ActivityManager
     */
    @SuppressLint("PrivateApi")
    @SuppressWarnings({"JavaReflectionMemberAccess"})
    private static Object getActivityManager() {
        // We could possibly cast this to ActivityManager instead of Object, but we don't currently
        // need that for our usage, and it would require retesting everything. Maybe ActivityManager
        // is even wrong and it should be ActivityManagerService, for which we don't have the class
        // definition anyway. TODO: investigate further.

        synchronized (lock) {
            if (oActivityManager != null) {
                return oActivityManager;
            }

            try { // marked deprecated in Android source
                Class<?> cActivityManagerNative = Class.forName("android.app.ActivityManagerNative");
                Method mGetDefault = cActivityManagerNative.getMethod("getDefault");
                oActivityManager = mGetDefault.invoke(null);
                return oActivityManager;
            } catch (Exception e) {
                // possibly removed
            }

            try {
                // alternative
                Class<?> cActivityManager = Class.forName("android.app.ActivityManager");
                Method mGetService = cActivityManager.getMethod("getService");
                oActivityManager = mGetService.invoke(null);
                return oActivityManager;
            } catch (Exception e) {
                Logger.ex(e);
            }

            throw new RuntimeException("librootjava: unable to retrieve ActivityManager");
        }
    }

    /** Cache for getFlagReceiverFromShell() */
    private static Integer FLAG_RECEIVER_FROM_SHELL = null;

    /**
     * Retrieve value of Intent.FLAG_RECEIVER_FROM_SHELL, if it exists
     *
     * @return FLAG_RECEIVER_FROM_SHELL or 0
     */
    @SuppressWarnings({"JavaReflectionMemberAccess"})
    private static int getFlagReceiverFromShell() {
        synchronized (lock) {
            if (FLAG_RECEIVER_FROM_SHELL != null) {
                return FLAG_RECEIVER_FROM_SHELL;
            }

            try {
                Field fFlagReceiverFromShell = Intent.class.getDeclaredField("FLAG_RECEIVER_FROM_SHELL");
                FLAG_RECEIVER_FROM_SHELL = fFlagReceiverFromShell.getInt(null);
                return FLAG_RECEIVER_FROM_SHELL;
            } catch (NoSuchFieldException e) {
                // not present on all Android versions
            } catch (IllegalAccessException e) {
                Logger.ex(e);
            }

            FLAG_RECEIVER_FROM_SHELL = 0;
            return FLAG_RECEIVER_FROM_SHELL;
        }
    }

    /** Cache for getBroadcastIntent() */
    private static Method mBroadcastIntent = null;

    /**
     * Retrieve the ActivityManager.broadcastIntent() method
     *
     * @param cActivityManager ActivityManager class
     * @return broadcastIntent method
     */
    private static Method getBroadcastIntent(Class<?> cActivityManager) {
        synchronized (lock) {
            if (mBroadcastIntent != null) {
                return mBroadcastIntent;
            }

            for (Method m : cActivityManager.getMethods()) {
                if (m.getName().equals("broadcastIntent") && (m.getParameterTypes().length == 13)) {
                    // API 24+
                    mBroadcastIntent = m;
                    return mBroadcastIntent;
                }
                if (m.getName().equals("broadcastIntent") && (m.getParameterTypes().length == 12)) {
                    // API 21+
                    mBroadcastIntent = m;
                    return mBroadcastIntent;
                }
            }

            throw new RuntimeException("librootjava: unable to retrieve broadcastIntent method");
        }
    }

    /**
     * Stability: the implementation for this will definitely change over time
     *
     * This implementation does not require us to have a context
     *
     * @see RootJava#sendBroadcast(Intent)
     * @see RootIPC#broadcastIPC()
     *
     * @param intent Intent to broadcast
     */
    @SuppressLint("PrivateApi")
    static void sendBroadcast(Intent intent) {
        try {
            // Prevent system from complaining about unprotected broadcast, if the field exists
            intent.setFlags(getFlagReceiverFromShell());

            Object oActivityManager = getActivityManager();
            Method mBroadcastIntent = getBroadcastIntent(oActivityManager.getClass());
            if (mBroadcastIntent.getParameterTypes().length == 13) {
                // API 24+
                mBroadcastIntent.invoke(oActivityManager, null, intent, null, null, 0, null, null, null, -1, null, true, false, 0);
                return;
            }
            if (mBroadcastIntent.getParameterTypes().length == 12) {
                // API 21+
                mBroadcastIntent.invoke(oActivityManager, null, intent, null, null, 0, null, null, null, -1, true, false, 0);
                return;
            }
        } catch (Exception e) {
            Logger.ex(e);
            return;
        }

        // broadcast wasn't sent if we arrive here
        throw new RuntimeException("librootjava: unable to send broadcast");
    }

    @SuppressWarnings("unchecked")
    static class InterfaceRetriever<T> {
        /**
         * Stability: stable, as changes to this pattern in AOSP would probably require all
         * AIDL-using apps to be recompiled.
         *
         * @see RootIPCReceiver#getInterfaceFromBinder(IBinder)
         *
         * @param clazz Class of T
         * @param binder Binder proxy to retrieve interface from
         * @return T (proxy) instance or null
         */
        T getInterfaceFromBinder(Class<T> clazz, IBinder binder) {
            // There does not appear to be a nice way to do this without reflection,
            // though of course you can use T.Stub.asInterface(binder) in final code, that doesn't
            // help for our callbacks
            try {
                Class<?> cStub = Class.forName(clazz.getName() + "$Stub");
                Field fDescriptor = cStub.getDeclaredField("DESCRIPTOR");
                fDescriptor.setAccessible(true);

                String descriptor = (String)fDescriptor.get(binder);
                IInterface intf = binder.queryLocalInterface(descriptor);
                if (clazz.isInstance(intf)) {
                    // local
                    return (T)intf;
                } else {
                    // remote
                    Class<?> cProxy = Class.forName(clazz.getName() + "$Stub$Proxy");
                    Constructor<?> ctorProxy = cProxy.getDeclaredConstructor(IBinder.class);
                    ctorProxy.setAccessible(true);
                    return (T)ctorProxy.newInstance(binder);
                }
            } catch (Exception e) {
                Logger.ex(e);
            }
            return null;
        }
    }
}
