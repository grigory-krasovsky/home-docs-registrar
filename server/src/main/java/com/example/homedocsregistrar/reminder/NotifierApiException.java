package com.example.homedocsregistrar.reminder;

/**
 * Notifier answered with an API error {@code {"code": "...", "message": "..."}}.
 *
 * @see NotifierErrorCode
 */
public final class NotifierApiException extends NotifierException {

    private final NotifierErrorCode code;
    private final int httpStatus;
    private final String detail;

    public NotifierApiException(NotifierErrorCode code, int httpStatus, String detail) {
        super("notifier API error " + code + " (HTTP " + httpStatus + ")" + (detail == null ? "" : ": " + detail), null);
        this.code = code;
        this.httpStatus = httpStatus;
        this.detail = detail;
    }

    public NotifierErrorCode code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** notifier's own {@code message} (e.g. the invalid fields for VALIDATION_FAILED); may be null. */
    public String detail() {
        return detail;
    }
}
