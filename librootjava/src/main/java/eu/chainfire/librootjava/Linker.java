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

import android.annotation.TargetApi;
import android.os.Build;
import android.system.Os;

import java.io.File;
import java.io.IOException;

/**
 * Internal utility methods that deal with LD_LIBRARY_PATH
 */
@SuppressWarnings({"WeakerAccess", "SameParameterValue"})
class Linker {
    /**
     * Cross-API method to get environment variable
     *
     * @param name Name of variable
     * @return Value of variable
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static String getenv(String name) {
        if (haveLinkerNamespaces()) {
            // real OS call
            return Os.getenv(name);
        } else {
            // cached by JVM at startup
            return System.getenv(name);
        }
    }

    /**
     * Set environment variable on newer API levels.<br>
     * <br>
     * The updated value is set at OS level, but System.getenv() calls may still return
     * the old values, as those are cached at JVM startup.
     *
     * @param name Name of variable
     * @param value Value of variable
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void setenv(String name, String value) {
        if (haveLinkerNamespaces()) {
            // note: restored path may not show up in methods depending on System.getenv
            try {
                if (value == null) {
                    Os.unsetenv(name);
                } else {
                    Os.setenv(name, value, true);
                }
            } catch (Exception e) {
                Logger.ex(e);
            }
        } // if we don't have linker namespaces this call isn't needed
    }

    /**
     * Are linker namespaces used?<br>
     * <br>
     * Android 7.0 (API level 24) and up use linker namespaces, which prevent apps from loading
     * native libraries outside of that namespace.<br>
     *
     * @see #getPatchedLdLibraryPath(boolean, String[])
     *
     * @return If linker namespaces are used
     */
    @TargetApi(23)
    private static boolean haveLinkerNamespaces() {
        return (
                (Build.VERSION.SDK_INT >= 24) ||

                // 7.0 preview
                ((Build.VERSION.SDK_INT == 23) && (Build.VERSION.PREVIEW_SDK_INT != 0))
        );
    }

    /**
     * Returns a value for LD_LIBRARY_PATH fit to bypass linker namespace restrictions and load
     * system as well as our own native libraries.<br>
     * <br>
     * Android 7.0 (API level 24) and up use linker namespaces, which prevent apps from loading
     * native libraries outside of that namespace.<br>
     * <br>
     * These are also employed for Java code running as root through app_process. One way to
     * bypass linker namespace is to explicitly set the LD_LIBRARY_PATH variable. Getting that
     * to work properly is trickier than it sounds with several edge-cases, do not modify this
     * code without testing excessively on different Android devices versions!<br>
     * <br>
     * We also add a marker and include the original LD_LIBRARY_PATH, so it's value may be
     * restored after load. Otherwise, executing other binaries may fail.
     *
     * @see #restoreOriginalLdLibraryPath()
     *
     * @param use64bit Use 64-bit paths
     * @param extraPaths Additional paths to include
     * @return Patched value for LD_LIBRARY_PATH
     */
    static String getPatchedLdLibraryPath(boolean use64bit, String[] extraPaths) {
        String LD_LIBRARY_PATH = getenv("LD_LIBRARY_PATH");
        if (!haveLinkerNamespaces()) {
            if (LD_LIBRARY_PATH != null) {
                // some firmwares have this, some don't, launch at boot may fail without, or with,
                // so just copy what is the current situation
                return LD_LIBRARY_PATH;
            }
            return null;
        } else {
            StringBuilder paths = new StringBuilder();

            // these default paths are taken from linker code in AOSP, and are normally used
            // when LD_LIBRARY_PATH isn't set explicitly
            String[] scan;
            if (use64bit) {
                scan = new String[]{
                        "/system/lib64",
                        "/data/lib64",
                        "/vendor/lib64",
                        "/data/vendor/lib64"
                };
            } else {
                scan = new String[]{
                        "/system/lib",
                        "/data/lib",
                        "/vendor/lib",
                        "/data/vendor/lib"
                };
            }

            for (String path : scan) {
                File file = (new File(path));
                if (file.exists()) {
                    try {
                        paths.append(file.getCanonicalPath());
                        paths.append(':');

                        // This part can trigger quite a few SELinux policy violations, they
                        // are harmless for our purpose, but if you're trying to trace SELinux
                        // related issues in your Binder calls, you may want to comment this part
                        // out. It is rarely (but still sometimes) actually required for your code
                        // to run.

                        File[] files = file.listFiles();
                        if (files != null) {
                            for (File dir : files) {
                                if (dir.isDirectory()) {
                                    paths.append(dir.getCanonicalPath());
                                    paths.append(':');
                                }
                            }
                        }
                    } catch (IOException e) {
                        // failed to resolve canonical path
                    }
                }
            }

            if (extraPaths != null) {
                for (String path : extraPaths) {
                    paths.append(path);
                    paths.append(':');
                }
            }

            paths.append("/librootjava"); // for detection

            if (LD_LIBRARY_PATH != null) {
                paths.append(':');
                paths.append(LD_LIBRARY_PATH);
            }

            return paths.toString();
        }
    }

    /**
     * Retrieve the pre-patched value of LD_LIBRARY_PATH.
     *
     * @see #getPatchedLdLibraryPath(boolean, String[])
     *
     * @return Original value of LD_LIBRARY_PATH
     */
    private static String getOriginalLdLibraryPath() {
        String LD_LIBRARY_PATH = System.getenv("LD_LIBRARY_PATH");
        if (LD_LIBRARY_PATH == null)
            return null;

        if (LD_LIBRARY_PATH.endsWith(":/librootjava"))
            return null;

        if (LD_LIBRARY_PATH.contains(":/librootjava:"))
            return LD_LIBRARY_PATH.substring(LD_LIBRARY_PATH.indexOf(":/librootjava:") + ":/librootjava:".length());

        return LD_LIBRARY_PATH;
    }

    /**
     * Restores correct LD_LIBRARY_PATH environment variable.
     *
     * @see Linker#getPatchedLdLibraryPath(boolean, String[])
     */
    static void restoreOriginalLdLibraryPath() {
        setenv("LD_LIBRARY_PATH", getOriginalLdLibraryPath());
    }
}
