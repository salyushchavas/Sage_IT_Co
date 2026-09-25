package com.spire.backend.exception;

/** 503: the document store didn't answer (a passing problem, not a missing file). */
public class StorageUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
