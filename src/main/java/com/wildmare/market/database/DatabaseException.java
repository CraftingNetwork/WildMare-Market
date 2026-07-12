package com.wildmare.market.database;

/** Wraps database initialization and runtime persistence failures. */
public final class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
