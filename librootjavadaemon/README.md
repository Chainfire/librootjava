# libRootJavaDaemon

Add-on for [libRootJava](../librootjava) to run the root process as a
daemon.

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

For some use-cases you may want to run your root process as a daemon,
so it can keep running independently of the state of the non-root
process.

This library adds that functionality to [libRootJava](../librootjava).

By adding a couple of lines to your [libRootJava](../librootjava) code,
this library will take care of starting the root process in daemon mode,
replacing older versions of the daemon already running, or triggering
an already running instance of the daemon of the same version to
re-broadcast its IPC interfaces (if any).

Please consider if your project really needs this functionality before
using this library, as it will be using additional device resources.

## Recommended reading

You should already be familiar with the workings of
[libRootJava](../librootjava). If not, this is the time to read up.

I strongly recommend you read the library's source code in its entirety
before using it extensively, as you should understand the basics of
what is going on behind the scenes. It is liberally commented.

I also strongly recommend not just running, but actually reading the
[example project](../librootjavadaemon_example)'s code.

## Getting started

In the class serving as your entry-point for the code running as root,
add a call to ```RootDaemon.daemonize()``` as one of the first
statements (generally after setting up logging):

```
    public static void main(String[] args) {
        // setup Logger

        // If a daemon of the same version is already running, this
        // call will trigger process termination, and will thus
        // never return.
        RootDaemon.daemonize(BuildConfig.APPLICATION_ID, 0, false, null);

        // ...

        RootJava.restoreOriginalLdLibraryPath();

        // ...
```

Then, instead of calling ```new RootIPC()``` for each of your
implemented IPC classes, call ```RootDaemon.register()```, closing
with ```RootDaemon.run()```:

```
        // ...

        IBinder ipc = new IMyIPC.Stub() {
            // ...

            @Override
            public void terminate() {
                RootDaemon.exit();
            }
        }

        RootDaemon.register(BuildConfig.APPLICATION_ID, ipc, 0);

        // ...

        RootDaemon.run();
    }
```

On the non-root side, simply replace your calls to
```RootJava.getLaunchScript()``` with calls to
```RootDaemon.getLaunchScript()```. Everything will work the same
as with normal use of [libRootJava](../librootjava), aside from that
the root process isn't tied to the lifecycle of the non-root end,
and you might want to explicitly tell the daemon to terminate at
some point (or not).

See the [example project](../librootjavadaemon_example) for further
details.

You can of course use this library without Binder-based IPC, in which
case you would skip the ```RootDaemon.register()``` and
```RootDaemon.run()``` calls in the code running as root, and replace
them with your own handling.

#### Termination

This daemon process will only terminate when explicitly told to do so,
either through IPC, a Linux kill signal, if an unhandled
exception occurs, or (if so configured) when the Android framework
dies. This is why in the example above we add a
```terminate()``` method to our IPC interface which calls
```RootDaemon.exit()```. This way you can tell the daemon to
stop running from your non-root app through IPC.

Note that this method will always trigger a ```RemoteException``` on the
non-root end when called through IPC.

See the [example project](../librootjavadaemon_example) for further
details.

#### Cleanup

As with running code as root in normal (non-daemon) mode, files may need
to be created in our app's cache directory. The chances of leftover
files are actually higher in daemon mode, and the number of files is
higher too.

To clean up, call ```RootDaemon.cleanupCache()``` instead of
```RootJava.cleanupCache()```. It is *not* needed to call both.

## abiFilters

This library includes native code for all platforms the NDK supports.
If your APK does not support all of these platforms, you need to use
*abiFilters* in your Gradle script to filter the unwanted libraries
out. Failure to do this may lead the Play Store to think your APK is
compatible with all the platforms this library supports, rather than
the ones you intended to support.

See [Specify ABIs](https://developer.android.com/studio/projects/gradle-external-native-builds#specify-abi)
on the Android site.

## Gradle

```
implementation 'eu.chainfire:librootjavadaemon:1.3.1'
```

You should update to the latest libRootJava and libRootJavaDaemon at the
same time.
