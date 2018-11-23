package eu.chainfire.librootjavadaemon_example.root;

interface IIPC {
    int getPid();
    int getLaunchedByPid();
    void terminate();
}
