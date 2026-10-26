package com.example.homedocsregistrar.reminder;

import java.util.Locale;

/** The {@code code} of a notifier API v1 error; {@link #UNKNOWN} for anything this client doesn't know. */
public enum NotifierErrorCode {
    /** 401 — missing or wrong bearer token. */
    UNAUTHORIZED,
    /** 503 — the API is switched off on notifier's side. */
    API_DISABLED,
    /** 404 — the user never pressed /start in notifier. */
    USER_NOT_REGISTERED,
    /** 404 — no such event (incl. one the user deleted in notifier). */
    EVENT_NOT_FOUND,
    /** 422 — the message lists the invalid fields. */
    VALIDATION_FAILED,
    /** 422 — the fire moment is already in the past. */
    FIRE_TIME_IN_PAST,
    UNKNOWN;

    static NotifierErrorCode of(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
