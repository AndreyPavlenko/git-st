package com.aap.gitst.fastexport;

public class FastExportException extends Exception {
    private static final long serialVersionUID = -5609061315701478197L;

    public FastExportException(final String message) {
        super(message);
    }

    public FastExportException(final Throwable cause) {
        super(cause);
    }

    public FastExportException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public FastExportException(final String message, final Throwable cause,
            final boolean enableSuppression, final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
