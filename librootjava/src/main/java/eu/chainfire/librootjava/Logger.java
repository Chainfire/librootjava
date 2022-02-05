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

import java.util.Locale;

/**
 * Provide logging functionality
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class Logger {
    private static String getDefaultLogTag(){
        String tag = BuildConfig.LIBRARY_PACKAGE_NAME;
        int p;
        while ((p = tag.indexOf('.')) >= 0) {
            tag = tag.substring(p + 1);
        }
        return tag;
    }

    private static String LOG_TAG = getDefaultLogTag();

    private static boolean log = false;
    
    /**
     * Set LOG_TAG
     * @param logTag LOG_TAG to use
     */
    public static void setLogTag(String logTag) {
        LOG_TAG = logTag;
    }
    
    /**
     * @return LOG_TAG
     */
    public static String getLogTag() {
        return LOG_TAG;
    }

    /**
     * @param enabled Enable debug logging
     */
    public static void setDebugLogging(boolean enabled) {
        log = enabled;
    }

    /**
     * Log on debug level
     *
     * @param message Message to format
     * @param args Format arguments
     */
    public static void d(String message, Object... args) {
        if (log) {
            if ((args != null) && (args.length > 0)) {
                message = String.format(Locale.ENGLISH, message, args);
            }
            android.util.Log.d(LOG_TAG, message);
        }
    }

    /**
     * Log on debug level with prefix
     *
     * @param prefix Prefix to prepend
     * @param message Message to format
     * @param args Format arguments
     */
    public static void dp(String prefix, String message, Object... args) {
        if (log) {
            if ((args != null) && (args.length > 0)) {
                message = String.format(Locale.ENGLISH, message, args);
            }
            android.util.Log.d(LOG_TAG, String.format(Locale.ENGLISH, "[%s]%s%s", prefix,
                    (message.startsWith("[") || message.startsWith(" ")) ? "" : " ", message));
        }
    }

    /**
     * Log exception (debug level)
     *
     * @param e Exception to log
     */
    public static void ex(Exception e) {
        if (log) {
            dp("EXCEPTION", "%s: %s", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Log on verbose level
     *
     * @param message Message to format
     * @param args Format arguments
     */
    public static void v(String message, Object... args) {
        android.util.Log.v(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }

    /**
     * Log on verbose level with prefix
     *
     * @param prefix Prefix to prepend
     * @param message Message to format
     * @param args Format arguments
     */
    public static void vp(String prefix, String message, Object... args) {
        message = String.format(Locale.ENGLISH, message, args);
        android.util.Log.v(LOG_TAG, String.format(Locale.ENGLISH, "[%s]%s%s", prefix,
                (message.startsWith("[") || message.startsWith(" ")) ? "" : " ", message));
    }

    /**
     * Log on info level
     *
     * @param message Message to format
     * @param args Format arguments
     */
    public static void i(String message, Object... args) {
        android.util.Log.i(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }

    /**
     * Log on info level with prefix
     *
     * @param prefix Prefix to prepend
     * @param message Message to format
     * @param args Format arguments
     */
    public static void ip(String prefix, String message, Object... args) {
        message = String.format(Locale.ENGLISH, message, args);
        android.util.Log.i(LOG_TAG, String.format(Locale.ENGLISH, "[%s]%s%s", prefix,
                (message.startsWith("[") || message.startsWith(" ")) ? "" : " ", message));
    }

    /**
     * Log on warning level
     *
     * @param message Message to format
     * @param args Format arguments
     */
    public static void w(String message, Object... args) {
        android.util.Log.w(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }

    /**
     * Log on warning level with prefix
     *
     * @param prefix Prefix to prepend
     * @param message Message to format
     * @param args Format arguments
     */
    public static void wp(String prefix, String message, Object... args) {
        message = String.format(Locale.ENGLISH, message, args);
        android.util.Log.w(LOG_TAG, String.format(Locale.ENGLISH, "[%s]%s%s", prefix,
                (message.startsWith("[") || message.startsWith(" ")) ? "" : " ", message));
    }

    /**
     * Log on error level
     *
     * @param message Message to format
     * @param args Format arguments
     */
    public static void e(String message, Object... args) {
        android.util.Log.e(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }

    /**
     * Log on error level with prefix
     *
     * @param prefix Prefix to prepend
     * @param message Message to format
     * @param args Format arguments
     */
    public static void ep(String prefix, String message, Object... args) {
        message = String.format(Locale.ENGLISH, message, args);
        android.util.Log.e(LOG_TAG, String.format(Locale.ENGLISH, "[%s]%s%s", prefix,
                (message.startsWith("[") || message.startsWith(" ")) ? "" : " ", message));
    }
}
