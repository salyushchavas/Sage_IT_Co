package com.spire.backend.exception;

/** 503: the document store didn't answer (a passing problem, not a missing file). */
public class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
