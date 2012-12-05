package com.googlecode.gitst.fastexport;

/**
 * @author Andrey Pavlenko
 */
public class UnsupportedCommandException extends FastExportException {
    private static final long serialVersionUID = 8300493146421835642L;

    public UnsupportedCommandException(final String message) {
        super(message);
    }

    public UnsupportedCommandException(final Throwable cause) {
        super(cause);
    }

    public UnsupportedCommandException(final String message,
            final Throwable cause) {
        super(message, cause);
    }

    public UnsupportedCommandException(final String message,
            final Throwable cause, final boolean enableSuppression,
            final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
