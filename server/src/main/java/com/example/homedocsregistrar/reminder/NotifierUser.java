package com.example.homedocsregistrar.reminder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

/** A user registered in notifier ({@code GET /api/v1/users/{id}}): their timezone and working hours. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifierUser(Long telegramUserId, String timezone, LocalTime workStart, LocalTime workEnd) {

    /** The user's timezone, or {@code fallback} when notifier sent none or an unknown zone id. */
    public ZoneId zone(ZoneId fallback) {
        if (timezone == null || timezone.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(timezone.strip());
        } catch (DateTimeException e) {
            return fallback;
        }
    }
}
