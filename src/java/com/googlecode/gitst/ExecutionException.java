package com.googlecode.gitst;

/**
 * @author Andrey Pavlenko
 */
public class ExecutionException extends Exception {
    private static final long serialVersionUID = 7688385756129919233L;
    private final int _exitCode;

    public ExecutionException(final String message, final int exitCode) {
        super(message);
        _exitCode = exitCode;
    }

    public int getExitCode() {
        return _exitCode;
    }
}
