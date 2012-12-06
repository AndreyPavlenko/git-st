package com.aap.gitst;

import java.io.Console;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

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
        this(logWriter, progressBarSupported, null);
    }

    public Logger(final OutputStream logWriter,
            final boolean progressBarSupported, final Level level) {
        this(new PrintWriter(logWriter), progressBarSupported, level);
    }

    public Logger(final PrintWriter logWriter,
            final boolean progressBarSupported) {
        this(logWriter, progressBarSupported, null);
    }

    public Logger(final PrintWriter logWriter,
            final boolean progressBarSupported, final Level level) {
        _logWriter = logWriter;
        _progressBarSupported = progressBarSupported;

        if (level == null) {
            if ("true".equalsIgnoreCase(System.getenv("GITST_DEBUG"))) {
                _level = Level.DEBUG;
            } else {
                _level = Level.INFO;
            }
        } else {
            _level = level;
        }
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
        return createProgressBar(message, total, false);
    }

    public ProgressBar createProgressBar(final Object message, final int total,
            final boolean closeOnComplete) {
        if (!isProgressBarSupported() || (total == 0)) {
            return new DummyProgressBar();
        }

        synchronized (this) {
            final PBar pbar = new PBar(message, total, closeOnComplete);
            _pbar.add(pbar);
            pbar.start();
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

        public void update();

        public void complete();

        public void close();
    }

    public static class DummyProgressBar implements ProgressBar {

        @Override
        public void done(final int count) {
        }

        @Override
        public void update() {
        }

        @Override
        public void complete() {
        }

        @Override
        public void close() {
        }
    }

    public class PBar extends Thread implements ProgressBar {
        private final Object _message;
        private final int _total;
        private final AtomicInteger _counter;
        private volatile boolean _closeOnComplete;
        private volatile boolean _update;
        private int _lastMessageLen;

        public PBar(final Object message, final int total,
                final boolean closeOnComplete) {
            _message = message;
            _total = total;
            _closeOnComplete = closeOnComplete;
            _counter = new AtomicInteger(total);
        }

        @Override
        public void run() {
            for (int prev = 0; !isInterrupted();) {
                final int current = _counter.get();

                if (current <= 0) {
                    break;
                } else if (!_update && (current == prev)) {
                    LockSupport.park();
                } else {
                    prev = current;
                    _update = false;

                    synchronized (Logger.this) {
                        clearPogress();
                        printProgress();
                    }
                }
            }

            synchronized (Logger.this) {
                clearPogress();
                printProgress();
                clearPogress();
                _pbar.remove(this);

                if (!_closeOnComplete) {
                    printMessage();
                    _logWriter.println();
                }

                printProgress();
            }
        }

        @Override
        public void done(final int count) {
            for (;;) {
                final int current = _counter.get();

                if (current <= 0) {
                    return;
                }

                final int diff = current - count;

                if (diff < 0) {
                    _counter.set(0);
                    LockSupport.unpark(this);
                } else if (_counter.compareAndSet(current, diff)) {
                    LockSupport.unpark(this);
                    return;
                }
            }
        }

        @Override
        public void update() {
            _update = true;
            LockSupport.unpark(this);
        }

        @Override
        public void complete() {
            _counter.set(0);
            LockSupport.unpark(this);
            try {
                join();
            } catch (final InterruptedException ex) {
            }
        }

        @Override
        public void close() {
            _closeOnComplete = true;
            interrupt();
        }

        // @GuardedBy("Logger.this")
        void printMessage() {
            final String msg = createMessage();
            _lastMessageLen = msg.length();
            _logWriter.print(msg);
        }

        private String createMessage() {
            final int done = _total - _counter.get();
            return '[' + String.valueOf(_message) + ": "
                    + ((done * 100) / _total) + "% (" + done + '/' + _total
                    + ")]";
        }
    }
}