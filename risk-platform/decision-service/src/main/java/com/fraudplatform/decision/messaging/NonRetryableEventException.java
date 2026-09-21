package com.fraudplatform.decision.messaging;

/** Processing can never succeed for this record (bad payload, permanent downstream rejection): send to the DLT at once. */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
