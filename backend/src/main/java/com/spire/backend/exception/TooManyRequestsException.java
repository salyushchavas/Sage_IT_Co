package com.spire.backend.exception;

/** 429: too many attempts in a short time (see RateLimiter). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
