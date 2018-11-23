package eu.chainfire.librootjava_example.root;

interface IRemoteInputStream {
    int available();
    int read();
    int readBuf(out byte[] b, int off, int len);
    void close();
}
