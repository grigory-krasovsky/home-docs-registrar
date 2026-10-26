package com.example.homedocsregistrar.reminder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Typed client for the notifier HTTP API v1 (reminders). Every request carries
 * {@code Authorization: Bearer <NOTIFIER_API_TOKEN>}. Failures surface as a {@link NotifierException}:
 * {@link NotifierApiException} for an API error {@code {"code","message"}}, {@link NotifierUnavailableException}
 * when notifier can't be reached, times out, or answers with something that isn't the API (5xx without a code).
 */
public class NotifierClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;
    private final boolean enabled;

    /**
     * @param builder carries the transport (timeouts; a mock server in tests) — this adds the base URL,
     *                the auth header and the error mapping on top of it
     */
    public NotifierClient(RestClient.Builder builder, NotifierProperties properties) {
        this.enabled = properties.isEnabled();
        String token = properties.apiToken() == null ? "" : properties.apiToken().strip();
        this.rest = builder
                // A disabled client is never called (see isEnabled); the placeholder only keeps the build valid.
                .baseUrl(enabled ? properties.url().strip() : "http://notifier.invalid")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw toException(response);
                })
                .build();
    }

    /** False when {@code NOTIFIER_URL} is blank — the integration is off and nothing should call notifier. */
    public boolean isEnabled() {
        return enabled;
    }

    /** {@code GET /users/{id}}: the user's timezone and working hours; empty if they never did /start in notifier. */
    public Optional<NotifierUser> findUser(long telegramUserId) {
        try {
            return Optional.ofNullable(call(() -> rest.get()
                    .uri("/api/v1/users/{id}", telegramUserId)
                    .retrieve()
                    .body(NotifierUser.class)));
        } catch (NotifierApiException e) {
            if (e.code() == NotifierErrorCode.USER_NOT_REGISTERED) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * {@code POST /events}: create a one-off reminder. {@code created} is true for 201; false for 200, which
     * means an event with this source+externalId already existed (an idempotent retry — the body was ignored).
     */
    public CreateResult createEvent(NewEvent event) {
        ResponseEntity<NotifierEvent> response = call(() -> rest.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toEntity(NotifierEvent.class));
        return new CreateResult(response.getBody(), response.getStatusCode().isSameCodeAs(HttpStatus.CREATED));
    }

    /** {@code GET /events?source&ref[&telegramUserId]}: the document's events (FINISHED included). */
    public List<NotifierEvent> listEvents(String source, String ref, Long telegramUserId) {
        NotifierEvent[] events = call(() -> rest.get()
                .uri(uri -> {
                    uri.path("/api/v1/events").queryParam("source", source).queryParam("ref", ref);
                    if (telegramUserId != null) {
                        uri.queryParam("telegramUserId", telegramUserId);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(NotifierEvent[].class));
        return events == null ? List.of() : List.of(events);
    }

    /** {@code DELETE /events/{source}/{externalId}}; an already-gone event (EVENT_NOT_FOUND) counts as deleted. */
    public void deleteEvent(String source, String externalId) {
        try {
            call(() -> rest.delete()
                    .uri("/api/v1/events/{source}/{externalId}", source, externalId)
                    .retrieve()
                    .toBodilessEntity());
        } catch (NotifierApiException e) {
            if (e.code() != NotifierErrorCode.EVENT_NOT_FOUND) {
                throw e;
            }
        }
    }

    /** {@code DELETE /events?source&ref}: drop every event of a document, for all users; returns how many. */
    public int deleteEventsByRef(String source, String ref) {
        DeletedCount deleted = call(() -> rest.delete()
                .uri(uri -> uri.path("/api/v1/events").queryParam("source", source).queryParam("ref", ref).build())
                .retrieve()
                .body(DeletedCount.class));
        return deleted == null || deleted.deleted() == null ? 0 : deleted.deleted();
    }

    /** Run a request, folding transport problems into {@link NotifierUnavailableException}. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (NotifierException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Connection refused, DNS failure, connect/read timeout.
            throw new NotifierUnavailableException("notifier is unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            // A body the API contract doesn't describe (e.g. an HTML page from something else on the port).
            throw new NotifierUnavailableException("unexpected notifier response: " + e.getMessage(), e);
        }
    }

    /** Map an error response to a typed exception by its {@code code}; codeless 5xx = notifier is unwell. */
    private static NotifierException toException(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        ErrorBody error = parseError(response.getBody().readAllBytes());
        if (error != null && error.code() != null && !error.code().isBlank()) {
            return new NotifierApiException(NotifierErrorCode.of(error.code()), status, error.message());
        }
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return new NotifierApiException(NotifierErrorCode.UNAUTHORIZED, status, null);
        }
        if (status >= 500) {
            return new NotifierUnavailableException("notifier answered HTTP " + status + " without an error code", null);
        }
        return new NotifierApiException(NotifierErrorCode.UNKNOWN, status, null);
    }

    private static ErrorBody parseError(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return JSON.readValue(body, ErrorBody.class);
        } catch (JacksonException e) {
            return null; // not the API's error JSON (proxy page, empty body, …)
        }
    }

    /** Outcome of a create: the event, and whether it was new (201) or an idempotent repeat (200). */
    public record CreateResult(NotifierEvent event, boolean created) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeletedCount(Integer deleted) {
    }
}
