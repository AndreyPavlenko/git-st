package com.googlecode.gitst;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Andrey Pavlenko
 */
public class StreamRedirector extends Thread {
    private final InputStream _from;
    private final OutputStream _to;

    public StreamRedirector(final InputStream from, final OutputStream to) {
        _from = from;
        _to = to;
    }

    @Override
    public void run() {
        try {
            final byte[] buf = new byte[1024];

            for (int i = _from.read(buf); i != -1; i = _from.read(buf)) {
                _to.write(buf, 0, i);
            }
        } catch (final IOException e) {
        }
    }
}
