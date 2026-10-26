package com.example.homedocsregistrar.reminder;

/**
 * A failed call to the notifier API. Either notifier couldn't be reached / didn't answer in time
 * ({@link NotifierUnavailableException} — worth retrying), or it answered with a typed error
 * ({@link NotifierApiException} — see its {@link NotifierErrorCode}).
 */
public abstract sealed class NotifierException extends RuntimeException
        permits NotifierUnavailableException, NotifierApiException {

    protected NotifierException(String message, Throwable cause) {
        super(message, cause);
    }
}
