# libRootJava

Run Java (and Kotlin) code as root!

- Runs code directly from your APK
- Access to all the classes in your projects
- Access to Android classes
- Easy Binder-based IPC/RPC

## License

Copyright &copy; 2018 Jorrit *Chainfire* Jongma

This code is released under the [Apache License version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

If you use this library (or code inspired by it) in your projects,
crediting me is appreciated.

If you modify the library itself when you use it in your projects,
you are kindly requested to share the sources of those modifications.

## Spaghetti Sauce Project

This library is part of the [Spaghetti Sauce Project](https://github.com/Chainfire/spaghetti_sauce_project).

## About

For a long time most of my root things were based on shell commands
and C(++) code. Some things are just much easier (and quicker) to do
in Java, and some things (such as calling into the Android framework)
are extremely cumbersome or downright impossible from native code.

So this library was born of both convenience and necessity. It's pretty
well known you can run Java code by *dex*ing it, *jar*ing it up, and
passing it to *dalvikvm*, but getting the setup right to access
Android classes properly across several different Android versions can
get tricky fast. Instead we let *app_process* and the Android runtime do
most of the heavy lifting, and execute code directly from our APK.

This means you can directly access all of the classes in your APK, as
well as the Android framework. Of course this doesn't mean all the
Android framework calls actually work - it doesn't expect you to run as
root, outside of an application it knows is running (missing
ProcessRecord), and without a proper Context.

The Binder-based IPC parts of this library are new (as of the first
public GitHub release), and though the
[example project](../librootjava_example) has been tested on dozens of
devices, there may still be some issues with it.

Annoyed with using stdin/stdout and sockets for communications,
I had long thought this IPC mechanism possible, but never quite got it
to work. I always got stuck at transferring the interface between
the root code and the non-root code. I've tried many different ways
that didn't work (such as handle dumping) or required dangerous SELinux
policy adjustments (registering system services), in the end the
solution is so simple I'm almost embarrassed to admit a simple intent
broadcast (combined with benign SELinux policy adjustments for newer
Android versions) will do the trick.

## Compatibility

I have used iterations of this code (without Binder-based IPC) in my
root apps for years, so it has been battle tested pretty well across
many devices and Android versions. Apps such as *LiveBoot*,
*Recently* and *FlashFire* all use(d) this library at their core.

While this library was originally built to support Android 4.2+ devices,
it only officially supports 5.0+. The first public GitHub release was
tested specifically on 5.0, 7.0, 8.0, and 9.0.

## Debugging

Debugging the code running as root is currently not supported. I made
some headway getting the jdwp server running, but I've not been able
to successfully connect jdb or AndroidStudio to it. If you want to take
a stab at it, there are some comments in
```RootJava.getLaunchString()``` related to it.

## Recommended reading

I strongly recommend you read the library's source code in its entirety
before using it extensively, as you should understand the basics of
what is going on behind the scenes. It is liberally commented.

I also strongly recommend not just running, but actually reading the
[example project](../librootjava_example)'s code. The app itself is
barely interesting to look at, but the code is heavily commented, and
points out several pitfalls. It also demonstrates the basics of the
thread model, AIDL interfaces, wrapping objects, Parcelables, passing
callbacks, accessing APK resources, etc.

If you're going to use Binder-based IPC and you are not familiar with
Binder or AIDL, you may want to read up on
[Bound Services](https://developer.android.com/guide/components/bound-services)
and especially
[AIDL](https://developer.android.com/guide/components/aidl) on
the Android site. This implementation is not exactly a bound service,
and thus the example code from the Bound Services page is not strictly
relevant, but the mechanisms are very similar.

## Getting started

For the barest implementation, you really only need to do two things:

- Create a class in your project with a static *main* method that will
be executed as root
- Run the script provided by ```RootJava.getLaunchScript()``` in a
root shell

Image your app's package is *com.example.myapp*, create a new
package *com.example.myapp.root*, and create a new class called
*RootMain* in it:

```
package com.example.myapp.root;

// imports

public class RootMain {
    public static void main(String[] args) {
        RootJava.restoreOriginalLdLibraryPath(); // required

        System.out.println("Hello World!");
        android.util.Log.i("myapp:root", "Hello World!");
    }
}
```

(See [RootMain.java](../librootjava_example/src/main/java/eu/chainfire/librootjava_example/root/RootMain.java)
from the [example project](../librootjava_example) for a more elaborate example)

Next, call ```RootJava.getLaunchScript()``` from your app and run the
output in a root shell:

```
public class MyActivity {
    private Shell.Interactive rootShell = null;

    // you still actually need to call this method somewhere
    private void launchRootProcess() {
        if ((rootShell == null) || !rootShell.isRunning()) {
            rootShell = (new Shell.Builder())
                            .useSU()
                            .open();
        }
        rootShell.addCommand(RootJava.getLaunchScript(this, RootMain.class, null, null, null, BuildConfig.APPLICATION_ID + ":root"));
    }
}
```

(See [MainActivity.java](../librootjava_example/src/main/java/eu/chainfire/librootjava_example/MainActivity.java)
from the [example project](../librootjava_example) for a more elaborate
example)

The ```Shell``` class is from [libsuperuser](https://github.com/Chainfire/libsuperuser),
but this can be done with any root shell library.

At this point, you can do work in RootMain based on passed arguments,
communicate with the parent (non-root) app via stdin/stdout/stderr,
setup socket-based communications, or use:

#### Binder-based IPC

Binder gives us a relatively easy way to make calls to code running
inside another process. While you may not need to use it directly
often as an app developer, it is used throughout the Android framework.

If you just want your code running as root to perform a simple
task and exit, using parameters and stdin/stdout may be easier,
but for more complex use-cases Binder is a perfect fit.

To use Binder-based IPC we need to:

- Define an interface in AIDL
- Implement that interface on the root end
- Transfer the interface to the non-root end

After doing this, if you call one of the interface's methods on the
non-root end, the code in the interface implementation on the root
end is executed (as root).

We start by defining a simple interface. Create a new AIDL file,
IMyIPC:

```
package com.example.myapp.root;

interface IMyIPC {
    int getPid();
}
```

Note that Android Studio by default creates the AIDL file in the app's
main package. You may need to move it around yourself to keep it in the
.root package. This is not necessary for the functionality itself.

Make sure to do a build before continuing, as AIDL files generate
Java code during the build process.

Next, we implement the interface in our RootMain class, and send it
over to the non-root end:

```
    public static void main(String[] args) {
        // ...

        // implement interface
        IBinder ipc = new IMyIPC.Stub() {
            @Override
            public int getPid() {
                return android.os.Process.myPid();
            }
        }

        // send it to the non-root end
        try {
            new RootIPC(BuildConfig.APPLICATION_ID, ipc, 0, 30 * 1000, true);
        } catch (RootIPC.TimeoutException e) {
            // a connection wasn't established
        }
    }

```

On the non-root end, we have to receive the interface, then we can call
it:

```
public class MyActivity {
    // ... (you still need to start the root process somewhere)

    private final RootIPCReceiver<IMyIPC> ipcReceiver = new RootIPCReceiver<IMyIPC>(null, 0) {
        @Override
        public void onConnect(IMyIPC ipc) {
            android.util.Log.i("myappRoot", "Remote PID: " + String.valueOf(ipc.getPid()));
        }

        @Override
        public void onDisconnect(IMyIPC ipc) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ...

        ipcReceiver.setContext(this);
    }

    @Override
    protected void onDestroy() {
        ipcReceiver.release();

        // ...

        super.onDestroy();
    }
}
```

Behind the scenes, the *RootIPC* class send a broadcast containing a
wrapper of your *IMyIPC* interface. The *RootIPCReceiver* class sets
up a broadcast receiver that catches this broadcast, does some
connection management, and unwraps the interface to be used by your
non-root code.

(See the [example project](../librootjava_example) for a more elaborate
example for this entire process)

#### Daemonizing

For some use-cases you may want to run your root process as a daemon,
so it can keep running independently of the state of the non-root
process.

This can be done with the [libRootJavaDaemon](../librootjavadaemon)
add-on library.

As some native code is involved to make this work correctly, I decided
to split it off into a separate library so users who do not use that
specific functionality do not suddenly need to start worrying about
*abiFilters*.

#### Process records

Process records are mentioned a number of times throughout the docs and
comments. A ProcessRecord is what Android uses to keep track of running
apps and processes. When you use this library, you don't have one.

Without a ProcessRecord we cannot do things such as create Activities,
register BroadcastReceivers, etc. It *might* be possible to have a
ProcessRecord created for our process, but then our process would also
be susceptible to the wiles of the Android system: we could be
randomly killed to save memory, save battery, or just because the
system feels like it (and the system feels like that quite often).

This also means it is on you to make sure whatever you make with this
library doesn't use excessive CPU, RAM, or other system resources.

## ProGuard

The required ProGuard rules are included in the library, and no further
action should be needed for everything to work with minifying enabled.
If for some reason Gradle does not pick up these rules automatically,
copy/paste them from the ```proguard.txt``` file into your own ProGuard
ruleset.

## Gradle

```
implementation 'eu.chainfire:librootjava:1.0.0'
```

## Notes

This library includes its own Logger class that is used
throughout, which should probably have been refactored out.
It wasn't.