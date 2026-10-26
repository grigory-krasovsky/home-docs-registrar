package com.example.homedocsregistrar.reminder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * A reminder as notifier reports it. notifier is the source of truth — the registrar keeps no copy, since
 * the user may pause, finish or delete it right in notifier.
 *
 * @param fireAtLocal the fire moment in the user's own timezone (only notifier knows it)
 * @param nextPingAt  the next notification (the fire itself or a nag); null when nothing is ahead
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifierEvent(Long id,
                            String source,
                            String externalId,
                            String ref,
                            Long telegramUserId,
                            String name,
                            Status status,
                            Instant fireAt,
                            LocalDateTime fireAtLocal,
                            Instant nextPingAt,
                            Integer nagIntervalMinutes,
                            NotifierLink link) {

    public enum Status {
        /** Waiting to fire, or fired and nagging until «Готово». */
        ACTIVE,
        /** Paused by the user in notifier. */
        PAUSED,
        /** The user pressed «Готово». */
        FINISHED,
        UNKNOWN;

        @JsonCreator
        public static Status of(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }
}
