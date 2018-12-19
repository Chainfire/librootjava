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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dalvik.system.BaseDexClassLoader;

/**
 * Main class with utility functions to launch Java code running as root.<br>
 * <br>
 * We execute our code by calling app_process, so we can call into the Android framework easily.
 * It is possible to use dalvikvm directly to achieve the same thing, but getting all the
 * parameters right across different Android versions can be tricky. Neither option is ideal,
 * but app_process seems more stable, and takes care of a lot of work for us.<br>
 * <br>
 * Note: you may see complaints from dex2oat in logcat during development on 64-bit devices. This
 * happens because we patch LD_LIBRARY_PATH for 64-bit libraries, and dex2oat is a 32-bit
 * executable. When a release APK is installed however, dex2oat is called at install time and
 * the odex should be successfully generated, preventing this error from occurring when you
 * launch the Java code as root. When this error is logged, we are likely running in JIT mode,
 * which may be significantly slower.<br>
 * <br>
 * Note: occasionally during development some confusing occurs about the number of bits the
 * application has, and the root code will not run. Rebuild your project to fix.
 *
 * @see #getLaunchScript(Context, Class, String, String, String[], String)
 * @see #restoreOriginalLdLibraryPath()
 * @see #getSystemContext()
 * @see #getPackageContext(String)
 * @see #getLibraryPath(Context, String)
 * @see Debugger#setEnabled(boolean)
 */
@SuppressWarnings({"unused", "WeakerAccess", "Convert2Diamond"})
public class RootJava {

    // ------------------------ calls for non-root ------------------------

    /**
     * Retrieve the full path to a native library.<br>
     * <br>
     * If you want to load one of your own native libraries, the code running as root will have to
     * know it's exact location. This method helps you determine that location.<br>
     *
     * <pre>
     * {@code
     * String libpath = RootJava.getLibraryPath(context, "mynativecode");
     * }
     * </pre>
     *
     * NOTE: This is not compatible with using extractNativeLibs="false" in your manifest!
     *
     * @param context Application or activity context
     * @param libname Name of the library
     * @return Full path to library or null
     */
    @SuppressLint("SdCardPath")
    public static String getLibraryPath(Context context, String libname) {
        if (Build.VERSION.SDK_INT >= 23) {
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) == 0) {
                throw new RuntimeException("librootjava: incompatible with extractNativeLibs=\"false\" in your manifest");
            }
        }

        if (libname.toLowerCase().startsWith("lib")) {
            libname = libname.substring(3);
        }
        if (libname.toLowerCase().endsWith(".so")) {
            libname = libname.substring(0, libname.length() - 3);
        }
        String packageName = context.getPackageName();

        // try nativeLibraryDir
        ApplicationInfo appInfo = context.getApplicationInfo();
        for (String candidate : new String[] {
                appInfo.nativeLibraryDir + File.separator + "lib" + libname + ".so",
                appInfo.nativeLibraryDir + File.separator + libname + ".so" // unlikely but not impossible
        }) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }

        // try BaseDexClassLoader
        if (context.getClassLoader() instanceof BaseDexClassLoader) {
            try {
                BaseDexClassLoader bdcl = (BaseDexClassLoader)context.getClassLoader();
                return bdcl.findLibrary(libname);
            } catch (Throwable t) {
                // not a standard call: catch Errors and Violations too aside from Exceptions
            }
        }

        // try (old) default location
        for (String candidate : new String[] {
                String.format(Locale.ENGLISH, "/data/data/%s/lib/lib%s.so", packageName, libname),
                String.format(Locale.ENGLISH, "/data/data/%s/lib/%s.so", packageName, libname)
        }) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Get string to be executed (in a root shell) to launch the Java code as root.
     *
     * You would normally use {@link #getLaunchScript(Context, Class, String, String, String[], String)}
     *
     * @param context Application or activity context
     * @param clazz Class containing "main" method
     * @param app_process Specific app_process binary to use, or null for default
     * @param params Parameters to supply to Java code, or null
     * @param niceName Process name to use (ps) instead of app_process (should be unique to your app), or null
     * @return Script
     */
    public static String getLaunchString(Context context, Class<?> clazz, String app_process, String[] params, String niceName) {
        if (app_process == null) app_process = AppProcess.getAppProcess();
        return getLaunchString(context.getPackageCodePath(), clazz.getName(), app_process, AppProcess.guessIfAppProcessIs64Bits(app_process), params, niceName);
    }

    /**
     * Get string to be executed (in a root shell) to launch the Java code as root.
     *
     * You would normally use {@link #getLaunchScript(Context, Class, String, String, String[], String)}
     *
     * @param packageCodePath Path to APK
     * @param clazz Class containing "main" method
     * @param app_process Specific app_process binary to use
     * @param is64Bit Is specific app_process binary 64-bit?
     * @param params Parameters to supply to Java code, or null
     * @param niceName Process name to use (ps) instead of app_process (should be unique to your app), or null
     * @return Script
     */
    public static String getLaunchString(String packageCodePath, String clazz, String app_process, boolean is64Bit, String[] params, String niceName) {
        String ANDROID_ROOT = System.getenv("ANDROID_ROOT");
        StringBuilder prefix = new StringBuilder();
        if (ANDROID_ROOT != null) {
            prefix.append("ANDROID_ROOT=");
            prefix.append(ANDROID_ROOT);
            prefix.append(' ');
        }

        int p;
        String[] extraPaths = null;
        if ((p = app_process.lastIndexOf('/')) >= 0) {
            extraPaths = new String[] { app_process.substring(0, p) };
        }
        String LD_LIBRARY_PATH = Linker.getPatchedLdLibraryPath(is64Bit, extraPaths);
        if (LD_LIBRARY_PATH != null) {
            prefix.append("LD_LIBRARY_PATH=");
            prefix.append(LD_LIBRARY_PATH);
            prefix.append(' ');
        }

        String vmParams = "";
        String extraParams = "";
        if (niceName != null) {
            extraParams += " --nice-name=" + niceName;
        }
        if (Debugger.enabled) { // we don't use isEnabled() because that has a different meaning when called as root, and though rare we might call this method from root too
            vmParams += " -Xcompiler-option --debuggable";
            if (Build.VERSION.SDK_INT >= 28) {
                // Android 9.0 Pie changed things up a bit
                vmParams += " -XjdwpProvider:internal -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y";
            } else {
                vmParams += " -agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y";
            }
        }
        String ret = String.format("NO_ADDR_COMPAT_LAYOUT_FIXUP=1 %sCLASSPATH=%s %s%s /system/bin%s %s", prefix.toString(), packageCodePath, app_process, vmParams, extraParams, clazz);
        if (params != null) {
            StringBuilder full = new StringBuilder(ret);
            for (String param : params) {
                full.append(' ');
                full.append(param);
            }
            ret = full.toString();
        }
        return ret;
    }

    /**
     * Get script to be executed (in a root shell) to launch the Java code as root.<br>
     * <br>
     * app_process is relocated during script execution. If a relocate_path is supplied
     * it must already exist. It is also made linker-namespace-safe, so optionally you
     * can put native libraries there (rarely necessary). By default we relocate to the app's
     * cache dir, falling back to /dev in case of issues or the app living on external storage.<br>
     * <br>
     * Note that SELinux policy patching takes place only in the script returned from
     * the first call, so be sure to execute that script first if you call this method
     * multiple times. You can change this behavior with the {@link Policies#setPatched(Boolean)}
     * method. The patch is only needed for the Binder-based IPC calls, if you do not use those,
     * you may consider passing true to {@link Policies#setPatched(Boolean)} and prevent the
     * patching altogether.
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
        ArrayList<String> pre = new ArrayList<String>();
        ArrayList<String> post = new ArrayList<String>();

        // relocate app_process
        app_process = AppProcess.getAppProcessRelocate(context, app_process, pre, post, relocate_path);

        // librootjavadaemon uses this
        pre.add(0, "#app_process=" + app_process);

        // patch SELinux policies
        Policies.getPatch(pre);

        // combine
        ArrayList<String> script = new ArrayList<String>(pre);
        script.add(getLaunchString(context, clazz, app_process, params, niceName));
        script.addAll(post);
        return script;
    }

    /** Prefixes of filename to remove from the app's cache directory */
    public static final String[] CLEANUP_CACHE_PREFIXES = new String[] { ".app_process32_", ".app_process64_" };

    /**
     * Clean up leftover files from our cache directory.<br>
     * <br>
     * In ideal circumstances no files should be left dangling, but in practise it happens sooner
     * or later anyway. Periodically (once per app launch or per boot) calling this method is
     * advised.<br>
     * <br>
     * This method should be called from a background thread, as it performs disk i/o.<br>
     * <br>
     * It is difficult to determine which of these files may actually be in use, especially in
     * daemon mode. We try to determine device boot time, and wipe everything from before that
     * time. For safety we explicitly keep files using our current UUID.
     *
     * @param context Context to retrieve cache directory from
     */
    public static void cleanupCache(Context context) {
        cleanupCache(context, CLEANUP_CACHE_PREFIXES);
    }

    /**
     * Clean up leftover files from our cache directory.<br>
     * <br>
     * This version is for internal use, see {@link #cleanupCache(Context)} instead.
     *
     * @param context Context to retrieve cache directory from
     * @param prefixes List of prefixes to scrub
     */
    public static void cleanupCache(Context context, final String[] prefixes) {
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir.exists()) {
                // determine time of last boot
                long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();

                // find our files
                for (File file : cacheDir.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        boolean accept = false;
                        for (String prefix : prefixes) {
                            // just in case: don't return files that contain our current uuid
                            if (name.startsWith(prefix) && !name.endsWith(AppProcess.UUID)) {
                                accept = true;
                                break;
                            }
                        }
                        return accept;
                    }
                })) {
                    if (file.lastModified() < boot) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
        } catch (Exception e) {
            Logger.ex(e);
        }
    }

    // ------------------------ calls for root ------------------------

    /**
     * Restores correct LD_LIBRARY_PATH environment variable. This should be one of the first
     * calls in your Java code running as root, after (optionally) loading native libraries.<br>
     * <br>
     * Failing to call this method may cause errors when executing other binaries (such as
     * running shell commands).
     *
     * @see Linker#restoreOriginalLdLibraryPath()
     * @see Linker#getPatchedLdLibraryPath(boolean, String[])
     */
    public static void restoreOriginalLdLibraryPath() {
        Linker.restoreOriginalLdLibraryPath();
    }

    /**
     * Returns a context that is useful for <i>some</i> calls - but this is not a proper full
     * context, and many calls that take a context do not actually work when running as root, or
     * not having the Android framework fully spun up, or not having an active ProcessRecord.
     * Some services can be accessed (see getPackageManager(), getSystemService(), ...).<br>
     * <br>
     * Due to preparing the main looper, this throws off libsuperuser if you use it for shell
     * commands on the main thread. If you use this call, you will probably need to call
     * Debug.setSanityChecksEnabled(false) to get any shell calls executed, and create a
     * separate HandlerThread (and Handler), and use both Shell.Builder.setAutoHandler(false)
     * and Shell.Builder.setHandler(^^^^) for Shell.Interactive to behave as expected.
     *
     * @return System context
     */
    public static Context getSystemContext() {
        return Reflection.getSystemContext();
    }

    /**
     * Returns a context with access to the resources of the passed package. This context is still
     * limited in many of the same ways the context returned by {@link #getSystemContext()} is, as
     * we still do not have an active ProcessRecord.
     *
     * @see #getSystemContext()
     *
     * @param packageName Name of the package to create Context for. Use BuildConfig.APPLICATION_ID (double check you're importing the correct BuildConfig!) to access our own package.
     * @return Package context
     * @throws PackageManager.NameNotFoundException If package could not be found
     */
    public static Context getPackageContext(String packageName) throws PackageManager.NameNotFoundException {
        return getSystemContext().createPackageContext(packageName, 0);
    }

    /**
     * Broadcast an intent using a reflected method that doesn't require us to have a Context or
     * ProcessRecord.
     *
     * @param intent Intent to broadcast
     */
    public static void sendBroadcast(Intent intent) {
        Reflection.sendBroadcast(intent);
    }
}
