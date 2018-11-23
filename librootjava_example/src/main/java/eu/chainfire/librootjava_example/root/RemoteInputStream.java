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

package eu.chainfire.librootjava_example.root;

import android.os.RemoteException;
import android.support.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import eu.chainfire.librootjava.Logger;

/**
 * Example helper class to pass InputStreams through Binder
 *
 * InputStream is a pretty basic class that is easily wrapped, because it really only requires
 * a handful of base methods.
 *
 * We cannot make an InputStream into a Binder-passable interface directly, because its definitions
 * includes throwing IOExceptions. It also defines multiple methods with the same name and a
 * different parameter list, which is not supported in aidl.
 *
 * You should never throw an exception in your Binder interface. We catch the exceptions and
 * return -2 instead, because conveniently all the methods we override should return values >= -1.
 * More complex classes would require more complex solutions.
 */
@SuppressWarnings("WeakerAccess")
public class RemoteInputStream {
    /**
     * Wraps an InputStream in an IRemoteInputStream
     *
     * Use this on the root side
     *
     * @param is InputStream
     * @return IRemoteInputStream
     */
    public static IRemoteInputStream.Stub fromInputStream(final InputStream is) {
        return new IRemoteInputStream.Stub() {
            // these methods are declared in IRemoteInputStream.aidl

            @Override
            public int available() {
                try {
                    return is.available();
                } catch (IOException e) {
                    Logger.ex(e);
                    return -2;
                }
            }

            @Override
            public int read() {
                try {
                    return is.read();
                } catch (IOException e) {
                    Logger.ex(e);
                    return -2;
                }
            }

            @Override
            public int readBuf(byte[] b, int off, int len) {
                try {
                    return is.read(b, off, len);
                } catch (IOException e) {
                    Logger.ex(e);
                    return -2;
                }
            }

            @Override
            public void close() {
                try {
                    is.close();
                } catch (IOException e) {
                    // no action required
                }
            }
        };
    }

    /**
     * Wraps an IRemoteInputStream in an InputStream
     *
     * We throw an IOException if we receive a -2 result, because we know we caught one on the
     * other end in that case. The logcat output will obviously not show the real reason for the
     * exception.
     *
     * We also wrap the InputStream we create inside a BufferedInputStream, to potentially reduce
     * the number of calls made. We increase the buffer size to 64kb in case this is ever used
     * to actually read large files, which is quite a bit faster than the default 8kb.
     *
     * Use this on the non-root side.
     *
     * @param ris IRemoteInputStream received through Binder
     * @return InputStream
     */
    public static InputStream toInputStream(final IRemoteInputStream ris) {
        if (ris == null) return null;
        return new BufferedInputStream(new InputStream() {
            private int throwIO(int r) throws IOException {
                if (r == -2) throw new IOException("Remote Exception");
                return r;
            }

            @Override
            public int available() throws IOException {
                // Basic InputStream works without overriding this method, but many methods that
                // take InputStreams depend on this method returning non-0 (the base method
                // always returns 0)
                try {
                    return throwIO(ris.available());
                } catch (RemoteException e) {
                    throw new IOException("Remote Exception");
                }
            }

            @Override
            public int read() throws IOException {
                // This is the only method you *really* need to override
                try {
                    return throwIO(ris.read());
                } catch (RemoteException e) {
                    throw new IOException("Remote Exception");
                }
            }

            @Override
            public int read(@NonNull byte[] b) throws IOException {
                // Overriding this one too will make reading much faster than just having read()
                return read(b, 0, b.length);
            }

            @Override
            public int read(@NonNull byte[] b, int off, int len) throws IOException {
                // Overriding this one too will make reading much faster than just having read()
                try {
                    return throwIO(ris.readBuf(b, off, len));
                } catch (RemoteException e) {
                    throw new IOException("Remote Exception");
                }
            }

            @Override
            public void close() throws IOException {
                // This method too is an optional override, but we wouldn't want to leave our
                // files open, would we?
                try {
                    ris.close();
                } catch (RemoteException e) {
                    throw new IOException("Remote Exception");
                }
            }
        }, 64 * 1024);
    }
}
