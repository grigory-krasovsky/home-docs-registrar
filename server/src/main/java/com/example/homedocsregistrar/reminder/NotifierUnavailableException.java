package com.example.homedocsregistrar.reminder;

/**
 * Notifier is down, unreachable, timed out, or answered with something that isn't the API (a 5xx without
 * an error code, an unreadable body). Safe to retry: every create carries a stable externalId.
 */
public final class NotifierUnavailableException extends NotifierException {

    public NotifierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
