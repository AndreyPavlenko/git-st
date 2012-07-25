package com.googlecode.gitst;

import java.io.Console;
import java.io.OutputStream;
import java.io.PrintWriter;

/**
 * @author Andrey Pavlenko
 */
public class Logger {
    private final PrintWriter _logWriter;
    private final boolean _progressBarSupported;
    private PBar _pbar;

    public Logger(final OutputStream logWriter,
            final boolean progressBarSupported) {
        this(new PrintWriter(logWriter), progressBarSupported);
    }

    public Logger(final PrintWriter logWriter,
            final boolean progressBarSupported) {
        _logWriter = logWriter;
        _progressBarSupported = progressBarSupported;
    }

    public static Logger createConsoleLogger() {
        final Console c = System.console();

        if (c != null) {
            return new Logger(c.writer(), true);
        } else {
            return new Logger(System.out, false);
        }
    }

    public boolean isProgressBarSupported() {
        return _progressBarSupported;
    }

    public synchronized void echo() {
        echo("");
    }

    public void warn(final Object msg) {
        echo("Warning! " + msg);
    }

    public synchronized void echo(final Object msg) {
        if (_pbar != null) {
            _pbar.clear();
            _logWriter.println(msg);
            _pbar.print();
        } else {
            _logWriter.println(msg);
        }

        _logWriter.flush();
    }

    public synchronized void error(final Object msg, final Throwable ex) {
        if (_pbar != null) {
            _pbar.clear();
            _logWriter.println(msg);

            if (ex != null) {
                ex.printStackTrace(_logWriter);
            }

            _pbar.print();
        } else {
            _logWriter.println(msg);

            if (ex != null) {
                ex.printStackTrace(_logWriter);
            }
        }

        _logWriter.flush();
    }

    public synchronized ProgressBar createProgressBar(final String message,
            final int total) {
        if (!isProgressBarSupported()) {
            return new DummyProgressBar();
        }
        if (_pbar != null) {
            throw new IllegalStateException("There is an active ProgressBar");
        }

        _pbar = new PBar(message, total);
        _pbar.print();
        return _pbar;
    }

    public static interface ProgressBar {

        public void done(int count);

        public void close();
    }

    public static class DummyProgressBar implements ProgressBar {

        @Override
        public void done(final int count) {
        }

        @Override
        public void close() {
        }
    }

    public class PBar implements ProgressBar {
        private final String _message;
        private final int _total;
        private int _done;
        private boolean _closed;
        private int _lastMessageLen;

        public PBar(final String message, final int total) {
            _message = message;
            _total = total;
        }

        @Override
        public void done(final int count) {
            synchronized (Logger.this) {
                if (_closed) {
                    throw new IllegalStateException(
                            "ProgressBar is already closed");
                }
                _done += count;
                print();

                if (_done >= _total) {
                    close();
                }
            }
        }

        @Override
        public void close() {
            synchronized (Logger.this) {
                if (!_closed) {
                    _closed = true;
                    _pbar = null;
                    _logWriter.println();
                    _logWriter.flush();
                }
            }
        }

        // @GuardedBy("this")
        void print() {
            final String msg = _message + ((_done * 100) / _total) + '%';
            _lastMessageLen = msg.length();
            _logWriter.print('\r');
            _logWriter.print(msg);
            _logWriter.flush();
        }

        // @GuardedBy("this")
        void clear() {
            _logWriter.print('\r');
            for (int i = 0; i < _lastMessageLen; i++) {
                _logWriter.print(' ');
            }
            _logWriter.print('\r');
        }
    }

    public static void main(final String[] args) throws InterruptedException {
        final Logger l = new Logger(System.out, true);
        l.echo("start");
        final ProgressBar b = l
                .createProgressBar("Importing to git...    ", 10);

        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            l.echo(i);
            b.done(1);
        }
    }
}
