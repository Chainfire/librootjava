package eu.chainfire.librootjava_example.root;

interface IPingCallback {
    void pong(long rootMainThreadId, long rootCallThreadId);
}
