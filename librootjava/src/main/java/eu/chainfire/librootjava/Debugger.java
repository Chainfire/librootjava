package eu.chainfire.librootjava;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Utility methods to support debugging
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class Debugger {
    /**
     * Is debugging enabled ?
     */
    static volatile boolean enabled = false;

    /**
     * Is debugging enabled ?<br>
     * <br>
     * If called from non-root, this will return if we are launching new processes with debugging
     * enabled. If called from root, this will return if the current process was launched
     * with debugging enabled.
     *
     * @return Debugging enabled
     */
    public static boolean isEnabled() {
        if (android.os.Process.myUid() >= 10000) {
            return enabled;
        } else {
            return Reflection.isDebuggingEnabled();
        }
    }

    /**
     * Launch root processes with debugging enabled ?
     * <br>
     * To prevent issues on release builds, BuildConfig.DEBUG should be respected. So instead
     * of passing <em>true</em> you would pass <em>BuildConfig.DEBUG</em>, while <em>false</em>
     * remains <em>false</em>.
     *
     * @param enabled Enable debugging (default: false)
     */
    public static void setEnabled(boolean enabled) {
        Debugger.enabled = enabled;
    }

    /**
     * Cache for name to present to debugger. Really only used to determine if we have manually
     * set a name already.
     */
    private static volatile String name = null;

    /**
     * Set name to present to debugger<br>
     * <br>
     * This method should only be called from the process running as root.<br>
     * <br>
     * Debugging will <strong>not</strong> work if this method has not been called, but the
     * {@link #waitFor(boolean)} method will call it for you, if used.<br>
     * <br>
     * {@link RootJava#restoreOriginalLdLibraryPath()} should have been called before calling
     * this method.<br>
     * <br>
     * To prevent issues with release builds, this call should be wrapped in a BuildConfig.DEBUG
     * check.
     *
     * @param name Name to present to debugger, or null to use process name
     *
     * @see #waitFor(boolean)
     */
    public static void setName(String name) {
        if (Debugger.name == null) {
            if (name == null) {
                final File cmdline = new File("/proc/" + android.os.Process.myPid() + "/cmdline");
                try (BufferedReader reader = new BufferedReader(new FileReader(cmdline))) {
                    name = reader.readLine();
                    if (name.indexOf(' ') > 0) name = name.substring(0, name.indexOf(' '));
                    if (name.indexOf('\0') > 0) name = name.substring(0, name.indexOf('\0'));
                } catch (IOException e) {
                    name = "librootjava:unknown";
                }
            }
            Debugger.name = name;
            Reflection.setAppName(name);
        }
    }

    /**
     * Wait for debugger to connect<br>
     * <br>
     * This method should only be called from the process running as root.<br>
     * <br>
     * If {@link #setName(String)} has not been called manually, the display name for the
     * debugger will be set to the current process name.<br>
     * <br>
     * After this method has been called, you can connect AndroidStudio's debugger to the root
     * process via <em>Run-&gt;Attach Debugger to Android process</em>.<br>
     * <br>
     * {@link RootJava#restoreOriginalLdLibraryPath()} should have been called before calling
     * this method.<br>
     * <br>
     * Android's internal debugger code will print to STDOUT during this call using System.println,
     * which may be annoying if your non-root process communicates with the root process through
     * STDIN/STDOUT/STDERR. If the <em>swallowOutput</em> parameter is set to true, System.println
     * will be temporarily redirected, and reset back to STDOUT afterwards.<br>
     * <br>
     * To prevent issues with release builds, this call should be wrapped in a BuildConfig.DEBUG
     * check:
     *
     * <pre>
     * {@code
     * if (BuildConfig.DEBUG) {
     *     Debugger.waitFor(true);
     * }
     * }
     * </pre>
     *
     * @param swallowOutput Temporarily redirect STDOUT ?
     */
    public static void waitFor(boolean swallowOutput) {
        if (Reflection.isDebuggingEnabled()) {
            if (swallowOutput) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                System.setOut(new PrintStream(buffer));
            }
            setName(null);
            android.os.Debug.waitForDebugger();
            if (swallowOutput) {
                System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
            }
        }
    }
}
