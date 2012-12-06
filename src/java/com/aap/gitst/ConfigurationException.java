package com.aap.gitst;

/**
 * @author Andrey Pavlenko
 */
public class ConfigurationException extends RuntimeException {
    private static final long serialVersionUID = 7871291679507972896L;

    public ConfigurationException() {
    }

    public ConfigurationException(final String message) {
        super(message);
    }

    public ConfigurationException(final Throwable cause) {
        super(cause);
    }

    public ConfigurationException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ConfigurationException(final String message, final Throwable cause,
            final boolean enableSuppression, final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
