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
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Utility methods to determine the location and bits of the app_process executable to be used.<br>
 * <br>
 * This is normally handled automatically by the {@link RootJava} class, but the option exists
 * to override the used app_process, in which case you would use these methods to find the
 * appropriate binary.
 *
 * @see RootJava
 */
@SuppressWarnings({"unused", "WeakerAccess", "BooleanMethodIsAlwaysInverted"})
public class AppProcess {
    /**
     * Toolbox or toybox?
     */
    public static final String BOX = Build.VERSION.SDK_INT < 23 ? "toolbox" : "toybox";

    /**
     * Used to create unique filenames in common locations
     */
    public static final String UUID = getUUID();

    /**
     * @return uuid that doesn't contain 32 or 64, as to not confuse bit-choosing code
     */
    private static String getUUID() {
        String uuid = null;
        while ((uuid == null) || uuid.contains("32") || uuid.contains("64")) {
            uuid = java.util.UUID.randomUUID().toString();
        }
        return uuid;
    }

    /**
     * Tries to read a file's ELF header to determine if it's a 64-bit binary
     *
     * @param filename Filename to check
     * @return True if 64-bit, false if 32-bit, null if unsure
     */
    @SuppressWarnings("all")
    private static Boolean checkELFHeaderFor64Bits(final String filename) {
        // Check ELF header. 8-bit value at 0x04 should be 1 for 32-bit or 2 for 64-bit
        try {
            FileInputStream is = new FileInputStream(filename);
            try {
                is.skip(4);
                int b = is.read();
                if (b == 1) return false;
                if (b == 2) return true;
            } finally {
                is.close();
            }
        } catch (FileNotFoundException e) {
            // no action required
        } catch (Exception e) {
            Logger.ex(e);
        }
        return null;
    }

    /**
     * Are we running on a 64-bit device?
     *
     * @return If 64-bit architecture
     */
    @SuppressLint("ObsoleteSdkInt")
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static boolean is64BitArch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // there is no (retail) 64-bit pre-Lollipop
            return false;
        }

        return Build.SUPPORTED_64_BIT_ABIS.length > 0;
    }

    /**
     * Are we running in 32-bit mode on a 64-bit device?<br>
     * <br>
     * This happens if the app includes only 32-bit native libraries and is run on a 64-bit device
     *
     * @return If running as 32-bit on a 64-bit device
     */
    private static boolean isRunningAs32BitOn64BitArch() {
        if (!is64BitArch()) {
            return false;
        }

        File f = new File("/proc/self/exe");
        try {
            if (f.getCanonicalPath().contains("32")) {
                return true;
            }
        } catch (Exception e) {
            Logger.ex(e);
        }
        return false;
    }

    /**
     * Get the path to the app_process with unspecified bits.<br>
     * <br>
     * It is unlikely you will need to call this method.<br>
     * <br>
     * Note that app_process_original and app_process_init are checked to cope with root on
     * older Android versions and Xposed installations.
     *
     * @see #getAppProcess()
     *
     * @return Path to app_process binary with unspecified bits or null
     */
    public static String getAppProcessNoBit() {
        for (String candidate : new String[] {
                "/system/bin/app_process_original",
                "/system/bin/app_process_init",
                "/system/bin/app_process"
        }) {
            if ((new File(candidate)).exists()) return candidate;
        }
        return null;
    }

    /**
     * Get the path to the 32-bit app_process binary.<br>
     * <br>
     * It is unlikely you will need to call this method.
     *
     * @see #getAppProcess()
     * @see #getAppProcess32Bit(boolean)
     *
     * @return Path to 32-bit app_process or app_process with unspecified bits or null
     */
    public static String getAppProcess32Bit() {
        return getAppProcess32Bit(true);
    }

    /**
     * Get the path to the 32-bit app_process binary.<br>
     * <br>
     * It is unlikely you will need to call this method.
     *
     * @see #getAppProcess()
     *
     * @param orDefault Whether to return the app_process with unspecified bits if a specific 32-bit binary isn't found
     * @return Path to 32-bit app_process or optionally app_process with unspecified bits or null
     */
    public static String getAppProcess32Bit(boolean orDefault) {
        // app_process32 or null if not 32-bit
        // if >32bit, app_process32 will always exist, if ==32bit, default is 32bit
        for (String candidate : new String[] {
                "/system/bin/app_process32_original",
                "/system/bin/app_process32_init",
                "/system/bin/app_process32"
        }) {
            if ((new File(candidate)).exists()) return candidate;
        }
        if (orDefault) return getAppProcessNoBit();
        return null;
    }

    /**
     * Get the path to the 64-bit app_process binary.<br>
     * <br>
     * It is unlikely you will need to call this method.
     *
     * @see #getAppProcess()
     *
     * @return Path to 64-bit app_process or null
     */
    public static String getAppProcess64Bit() {
        // app_process64 or null if not 64-bit
        for (String candidate : new String[] {
                "/system/bin/app_process64_original",
                "/system/bin/app_process64_init",
                "/system/bin/app_process64"
        }) {
            if ((new File(candidate)).exists()) return candidate;
        }
        return null;
    }

    /**
     * Get the path to the app_process binary with most bits.<br>
     * <br>
     * It is unlikely you will need to call this method.
     *
     * @see #getAppProcess()
     *
     * @return Path to most-bits app_process or null
     */
    public static String getAppProcessMaxBit() {
        String ret = getAppProcess64Bit();
        if (ret == null) ret = getAppProcess32Bit();
        if (ret == null) ret = getAppProcessNoBit();
        return ret;
    }

    /**
     * Get the path to the app_process binary that we are most likely to want to use.<br>
     * <br>
     * This is the most likely variant of the getAppProcessXXX calls to use, if any.
     *
     * @return Path to app_process
     */
    public static String getAppProcess() {
        String app_process = null;

        // If we are currently running as 32-bit but the architecture is 64-bit, we probably
        // only have native libraries for 32-bit. In that case our root code should also run
        // as 32-bit, so the code running as root can load those native libraries too.
        if (!isRunningAs32BitOn64BitArch()) app_process = getAppProcess64Bit();

        if (app_process == null) app_process = getAppProcess32Bit(true);
        return app_process;
    }

    /**
     * Attempts to determine if the app_process binary is 64-bit, most logical guess if unable.<br>
     * <br>
     * It is unlikely you will need to call this method
     *
     * @param app_process Path to app_process binary
     * @return If the app_process binary is 64-bit
     */
    public static boolean guessIfAppProcessIs64Bits(String app_process) {
        if (!is64BitArch()) {
            return false;
        }

        String compare = app_process;
        int sep = compare.lastIndexOf('/');
        if (sep >= 0) {
            compare = compare.substring(sep + 1);
        }
        if (compare.contains("32")) return false;
        if (compare.contains("64")) return true;

        try {
            compare = (new File(app_process)).getCanonicalFile().getName();
            if (compare.contains("32")) return false;
            if (compare.contains("64")) return true;
        } catch (Exception e) {
            Logger.ex(e);
        }

        // No 32 or 64 in the name? Check ELF header
        Boolean elf = checkELFHeaderFor64Bits(app_process);
        if (elf != null) {
            return elf;
        }

        // If we're currently running in 32-bits mode on a 64-bit architecture, chances are
        // we want to run root in 32-bit as well, because we're missing 64-bit libs
        return !isRunningAs32BitOn64BitArch();
    }

    /**
     * Should app_process be relocated ?<br>
     * <br>
     * On older Android versions we must relocate the app_process binary to prevent it from
     * running in a restricted SELinux context. On Q this presents us with the linker error:
     * "<i>Error finding namespace of apex: no namespace called runtime</i>". However, at least
     * on the first preview release of Q, running straight from /system/bin works and does
     * <i>not</i> give us a restricted SELinux context, so we skip relocation.
     *
     * TODO: Revisit on new Q preview and production releases. Maybe spend some time figuring out what causes the namespace error and if we can fix it.
     *
     * @see #getAppProcessRelocate(Context, String, List, List, String)
     *
     * @return should app_process be relocated ?
     */
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean shouldAppProcessBeRelocated() {
        return !(
            (Build.VERSION.SDK_INT >= 29) ||
            ((Build.VERSION.SDK_INT == 28) && (Build.VERSION.PREVIEW_SDK_INT != 0))
        );
    }

    /**
     * Create script to relocate specified app_process binary to a different location.<br>
     * <br>
     * On many Android versions and roots, executing app_process directly will force an
     * SELinux context that we do not want. Relocating it bypasses that.<br>
     *
     * @see #getAppProcess()
     * @see #shouldAppProcessBeRelocated()
     *
     * @param context Application or activity context
     * @param appProcessBase Path to original app_process or null for default
     * @param preLaunch List that retrieves commands to execute to perform the relocation
     * @param postExecution List that retrieves commands to execute to clean-up after execution
     * @param path Path to relocate to - must exist prior to script execution - or null for default
     * @return Path to relocated app_process
     */
    public static String getAppProcessRelocate(Context context, String appProcessBase, List<String> preLaunch, List<String> postExecution, String path) {
        if (appProcessBase == null) appProcessBase = getAppProcess();
        if (path == null) {
            if (!shouldAppProcessBeRelocated()) {
                return appProcessBase;
            }

            path = "/dev";
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0) {
                File cacheDir = context.getCacheDir();
                try {
                    //noinspection ResultOfMethodCallIgnored
                    cacheDir.mkdirs();
                } catch (Exception e) {
                    // just in case
                }
                if (cacheDir.exists()) {
                    try {
                        path = cacheDir.getCanonicalPath();
                    } catch (IOException e) {
                        // should never happen
                    }
                }
            }
        }

        boolean onData = path.startsWith("/data/");

        String appProcessCopy;
        if (guessIfAppProcessIs64Bits(appProcessBase)) {
            appProcessCopy = path + "/.app_process64_" + UUID;
        } else {
            appProcessCopy = path + "/.app_process32_" + UUID;
        }
        preLaunch.add(String.format(Locale.ENGLISH, "%s cp %s %s >/dev/null 2>/dev/null", BOX, appProcessBase, appProcessCopy));
        preLaunch.add(String.format(Locale.ENGLISH, "%s chmod %s %s >/dev/null 2>/dev/null", BOX, onData ? "0766" : "0700", appProcessCopy));
        if (onData) preLaunch.add(String.format(Locale.ENGLISH, "restorecon %s >/dev/null 2>/dev/null", appProcessCopy));
        postExecution.add(String.format(Locale.ENGLISH, "%s rm %s >/dev/null 2>/dev/null", BOX, appProcessCopy));
        return appProcessCopy;
    }
}
