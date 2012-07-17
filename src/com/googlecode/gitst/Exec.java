package com.googlecode.gitst;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * @author Andrey Pavlenko
 */
public class Exec {
    private final ProcessBuilder _builder;
    private OutputStream _outStream = System.out;
    private OutputStream _errStream = System.err;
    private Process _process;
    private StreamRedirector _outRedirector;
    private StreamRedirector _errRedirector;

    public Exec(final File dir, final String... command) {
        _builder = new ProcessBuilder(command);
        _builder.directory(dir);
    }

    public Exec(final File dir, final List<String> command) {
        _builder = new ProcessBuilder(command);
        _builder.directory(dir);
    }

    public Exec exec() throws IOException {
        final ProcessBuilder b = getProcessBuilder();
        final Process proc = b.start();
        _process = proc;
        _outRedirector = new StreamRedirector(proc.getInputStream(), _outStream);
        _errRedirector = new StreamRedirector(proc.getErrorStream(), _errStream);
        _outRedirector.start();
        _errRedirector.start();
        return this;
    }

    public ProcessBuilder getProcessBuilder() {
        return _builder;
    }

    public OutputStream getOutStream() {
        return _outStream;
    }

    public OutputStream getErrStream() {
        return _errStream;
    }

    public void setOutStream(final OutputStream outStream) {
        _outStream = outStream;
    }

    public void setErrStream(final OutputStream errStream) {
        _errStream = errStream;
    }

    public Process getProcess() {
        return _process;
    }

    public int waitFor() throws InterruptedException {
        final Process p = _process;
        int exitCode = 0;

        if (p != null) {
            final StreamRedirector out = _outRedirector;
            final StreamRedirector err = _errRedirector;
            exitCode = p.waitFor();
            out.waitFor();
            err.waitFor();
        }

        return exitCode;
    }

    private class StreamRedirector extends Thread {
        private final InputStream _from;
        private final OutputStream _to;
        private boolean _done;

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
            } finally {
                synchronized (this) {
                    _done = true;
                    notifyAll();
                }
            }
        }

        public synchronized void waitFor() throws InterruptedException {
            while (!_done) {
                wait();
            }
        }
    }
}
