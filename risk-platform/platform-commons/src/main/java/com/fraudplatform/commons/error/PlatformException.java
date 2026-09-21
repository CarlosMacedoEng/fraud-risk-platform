package com.fraudplatform.commons.error;

import java.util.Map;

/** Business exception carrying a stable {@link ErrorCode} and optional structured details. */
public class PlatformException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public PlatformException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public PlatformException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public PlatformException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message == null ? code.defaultMessage() : message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
