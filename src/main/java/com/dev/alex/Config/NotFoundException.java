package com.dev.alex.Config;

/** A requested record does not exist: answered 404 by GlobalExceptionHandler, not 500. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
