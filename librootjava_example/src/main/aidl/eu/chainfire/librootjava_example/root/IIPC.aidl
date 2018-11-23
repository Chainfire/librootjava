package eu.chainfire.librootjava_example.root;

import eu.chainfire.librootjava_example.root.IRemoteInputStream;
import eu.chainfire.librootjava_example.root.PassedData;
import eu.chainfire.librootjava_example.root.IPingCallback;

interface IIPC {
    int getPid();
    List<String> run(String command);
    IRemoteInputStream openFileForReading(String filename);
    PassedData getSomeData();
    Bitmap getIcon();
    void ping(IPingCallback pong);
}
