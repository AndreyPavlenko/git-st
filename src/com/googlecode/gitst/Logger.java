package com.googlecode.gitst;

import java.io.Console;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Andrey Pavlenko
 */
public class Logger {
    private final PrintWriter _logWriter;
    private final List<PBar> _pbar = new ArrayList<PBar>(3);
    private volatile Level _level;
    private volatile boolean _progressBarSupported;

    public Logger(final OutputStream logWriter,
            final boolean progressBarSupported) {
        this(logWriter, progressBarSupported, Level.INFO);
    }

    public Logger(final OutputStream logWriter,
            final boolean progressBarSupported, final Level level) {
        this(new PrintWriter(logWriter), progressBarSupported, level);
    }

    public Logger(final PrintWriter logWriter,
            final boolean progressBarSupported) {
        this(logWriter, progressBarSupported, Level.INFO);
    }

    public Logger(final PrintWriter logWriter,
            final boolean progressBarSupported, final Level level) {
        _logWriter = logWriter;
        _progressBarSupported = progressBarSupported;
        _level = level;
    }

    public static Logger createConsoleLogger(final Level level) {
        final Console c = System.console();
        final String env = System.getenv("GITST_PB");
        final boolean enable = "true".equalsIgnoreCase(env);
        final boolean disable = "false".equalsIgnoreCase(env);

        if (c != null) {
            return new Logger(c.writer(), !disable, level);
        } else {
            return new Logger(System.out, enable, level);
        }
    }

    public boolean isProgressBarSupported() {
        return _progressBarSupported;
    }

    public void setProgressBarSupported(final boolean progressBarSupported) {
        _progressBarSupported = progressBarSupported;
    }

    public Level getLevel() {
        return _level;
    }

    public void setLevel(final Level level) {
        _level = level;
    }

    public void debug(final Object msg) {
        if (isDebugEnabled()) {
            log(msg);
        }
    }

    public void info(final Object msg) {
        if (isInfoEnabled()) {
            log(msg);
        }
    }

    public void warn(final Object msg) {
        if (isWarnEnabled()) {
            log(msg);
        }
    }

    public void error(final Object msg) {
        error(msg, null);
    }

    public void error(final Object msg, final Throwable ex) {
        if (isErrorEnabled()) {
            synchronized (this) {
                if (!_pbar.isEmpty()) {
                    clearPogress();
                    _logWriter.println(msg);

                    if (ex != null) {
                        ex.printStackTrace(_logWriter);
                    }

                    printProgress();
                } else {
                    _logWriter.println(msg);

                    if (ex != null) {
                        ex.printStackTrace(_logWriter);
                    }
                }

                _logWriter.flush();
            }
        }
    }

    public ProgressBar createProgressBar(final Object message, final int total) {
        if (!isProgressBarSupported()) {
            return new DummyProgressBar();
        }

        synchronized (this) {
            final PBar pbar = new PBar(message, total);
            clearPogress();
            _pbar.add(pbar);
            printProgress();
            return pbar;
        }
    }

    public boolean isDebugEnabled() {
        return getLevel() == Level.DEBUG;
    }

    public boolean isInfoEnabled() {
        return getLevel().ordinal() <= Level.INFO.ordinal();
    }

    public boolean isWarnEnabled() {
        return getLevel().ordinal() <= Level.WARNING.ordinal();
    }

    public boolean isErrorEnabled() {
        return getLevel().ordinal() <= Level.ERROR.ordinal();
    }

    private synchronized void log(final Object msg) {
        if (!_pbar.isEmpty()) {
            clearPogress();
            _logWriter.println(msg);
            printProgress();
        } else {
            _logWriter.println(msg);
        }

        _logWriter.flush();
    }

    // @GuardedBy("this")
    private void clearPogress() {
        _logWriter.print('\r');
        for (final PBar b : _pbar) {
            for (int i = 0; i < b._lastMessageLen; i++) {
                _logWriter.print(' ');
            }
            _logWriter.print(' ');
        }
        _logWriter.print('\r');
    }

    // @GuardedBy("this")
    private void printProgress() {
        for (final PBar b : _pbar) {
            b.printMessage();
            _logWriter.print(' ');
        }
        _logWriter.flush();
    }

    public static enum Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        OFF;
    }

    public static interface ProgressBar {

        public void done(int count);

        public void complete();

        public void close();
    }

    public static class DummyProgressBar implements ProgressBar {

        @Override
        public void done(final int count) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void close() {
        }
    }

    public class PBar implements ProgressBar {
        private final Object _message;
        private final int _total;
        private int _done;
        private boolean _closed;
        private int _lastMessageLen;

        public PBar(final Object message, final int total) {
            _message = message;
            _total = total;
        }

        @Override
        public void done(final int count) {
            synchronized (Logger.this) {
                if (!_closed) {
                    _done += count;
                    clearPogress();
                    printProgress();

                    if (_done >= _total) {
                        close();
                    }
                }
            }
        }

        @Override
        public void complete() {
            synchronized (Logger.this) {
                done(_total - _done);
            }
        }

        @Override
        public void close() {
            synchronized (Logger.this) {
                if (!_closed) {
                    _closed = true;
                    clearPogress();
                    _pbar.remove(this);
                    printProgress();
                }
            }
        }

        // @GuardedBy("Logger.this")
        void printMessage() {
            final String msg = createMessage();
            _lastMessageLen = msg.length();
            _logWriter.print(msg);
        }

        private String createMessage() {
            return '[' + String.valueOf(_message) + ": "
                    + ((_done * 100) / _total) + "% (" + _done + '/' + _total
                    + ")]";
        }
    }
}