package eu.chainfire.librootjavadaemon;

// Used internally by RootDaemon

interface IRootDaemonIPC {
    String getVersion();
    void terminate();
    void broadcast();
}
