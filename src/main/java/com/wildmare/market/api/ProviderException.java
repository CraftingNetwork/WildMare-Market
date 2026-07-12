package com.wildmare.market.api;

/** Signals a provider request, response, or availability failure. */
public final class ProviderException extends RuntimeException {
    private final int statusCode;

    public ProviderException(String message) {
        this(message, -1, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public ProviderException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public ProviderException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
