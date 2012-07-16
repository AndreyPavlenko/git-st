package com.googlecode.gitst;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Andrey Pavlenko
 */
public class Executor {
    private final ProcessBuilder _builder;

    public Executor(final File dir, final String... command) {
        _builder = new ProcessBuilder(command);
        _builder.directory(dir);
    }

    public ProcessBuilder getProcessBuilder() {
        return _builder;
    }

    public Process exec() throws IOException {
        final ProcessBuilder b = getProcessBuilder();
        final Process proc = b.start();
        new StreamRedirector(proc.getInputStream(), System.out).start();
        new StreamRedirector(proc.getErrorStream(), System.err).start();
        return proc;
    }

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
}
