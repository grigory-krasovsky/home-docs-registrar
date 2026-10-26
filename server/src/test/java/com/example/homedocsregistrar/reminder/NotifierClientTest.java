package com.example.homedocsregistrar.reminder;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The notifier API v1 client against a mock server: auth, request/response mapping, every error code. */
class NotifierClientTest {

    private static final String BASE = "http://notifier.test";
    private static final String TOKEN = "s3cret";

    private static final String EVENT_JSON = """
            {"id": 17, "source": "home-docs", "externalId": "3f1c2a9e", "ref": "document:42",
             "telegramUserId": 123456789, "name": "Истекает гарантия: холодильник Bosch", "status": "ACTIVE",
             "fireAt": "2027-02-22T07:00:00Z", "fireAtLocal": "2027-02-22T10:00",
             "nextPingAt": "2027-02-22T07:00:00Z", "nagIntervalMinutes": 1440,
             "link": {"label": "Документ #42", "url": "https://t.me/HomeDocsBot?start=doc_42"},
             "someFutureField": true}""";

    private MockRestServiceServer server;
    private NotifierClient client;

    private static NotifierProperties properties(String url, Duration connect, Duration read) {
        return new NotifierProperties(url, TOKEN, "NotifierBot", connect, read);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NotifierClient(builder, properties(BASE, Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    private static String error(String code, String message) {
        return "{\"code\": \"" + code + "\", \"message\": \"" + message + "\"}";
    }

    @Test
    void findUserParsesTheProfileAndSendsTheBearerToken() {
        server.expect(requestTo(BASE + "/api/v1/users/123456789"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {"telegramUserId": 123456789, "timezone": "Europe/Moscow",
                         "workStart": "09:00", "workEnd": "22:00"}""", MediaType.APPLICATION_JSON));

        NotifierUser user = client.findUser(123456789L).orElseThrow();

        assertThat(user.telegramUserId()).isEqualTo(123456789L);
        assertThat(user.timezone()).isEqualTo("Europe/Moscow");
        assertThat(user.workStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(user.workEnd()).isEqualTo(LocalTime.of(22, 0));
        server.verify();
    }

    @Test
    void findUserIsEmptyWhenNotRegistered() {
        server.expect(requestTo(BASE + "/api/v1/users/5"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(error("USER_NOT_REGISTERED", "user 5 did not /start")));

        assertThat(client.findUser(5L)).isEmpty();
        server.verify();
    }

    @Test
    void createSendsTheContractBodyAndReportsCreated() {
        server.expect(requestTo(BASE + "/api/v1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"telegramUserId": 123456789, "source": "home-docs", "externalId": "3f1c2a9e",
                         "ref": "document:42", "name": "Истекает гарантия: холодильник Bosch",
                         "fireDate": "2027-02-22", "fireTime": "10:00", "nagIntervalMinutes": 1440,
                         "link": {"label": "Документ #42", "url": "https://t.me/HomeDocsBot?start=doc_42"}}""",
                        JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(EVENT_JSON));

        NotifierClient.CreateResult result = client.createEvent(new NewEvent(123456789L, "home-docs", "3f1c2a9e",
                "document:42", "Истекает гарантия: холодильник Bosch", "2027-02-22", "10:00", 1440,
                new NotifierLink("Документ #42", "https://t.me/HomeDocsBot?start=doc_42")));

        assertThat(result.created()).isTrue();
        NotifierEvent event = result.event();
        assertThat(event.id()).isEqualTo(17L);
        assertThat(event.status()).isEqualTo(NotifierEvent.Status.ACTIVE);
        assertThat(event.fireAt()).isEqualTo(Instant.parse("2027-02-22T07:00:00Z"));
        assertThat(event.fireAtLocal()).isEqualTo(LocalDateTime.of(2027, 2, 22, 10, 0));
        assertThat(event.nagIntervalMinutes()).isEqualTo(1440);
        assertThat(event.link().url()).isEqualTo("https://t.me/HomeDocsBot?start=doc_42");
        server.verify();
    }

    @Test
    void createOmitsOptionalFieldsAndTreats200AsAnIdempotentRepeat() {
        server.expect(requestTo(BASE + "/api/v1/events"))
                .andExpect(content().json("""
                        {"telegramUserId": 1, "source": "home-docs", "externalId": "x", "ref": "document:7",
                         "name": "Оплата", "fireDate": "2027-01-10"}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));

        NotifierClient.CreateResult result = client.createEvent(
                new NewEvent(1L, "home-docs", "x", "document:7", "Оплата", "2027-01-10", null, null, null));

        assertThat(result.created()).isFalse();
        assertThat(result.event().externalId()).isEqualTo("3f1c2a9e");
        server.verify();
    }

    @Test
    void listParsesEventsIncludingFinishedAndUnknownStatus() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v1/events?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(queryParam("source", "home-docs"))
                .andExpect(queryParam("ref", "document:42"))
                .andExpect(queryParam("telegramUserId", "123456789"))
                .andRespond(withSuccess("[" + EVENT_JSON + """
                        , {"id": 18, "externalId": "b", "name": "ТО", "status": "FINISHED",
                           "fireAt": "2026-01-01T07:00:00Z", "nextPingAt": null, "nagIntervalMinutes": null},
                          {"id": 19, "externalId": "c", "name": "Новое", "status": "SNOOZED"}]""",
                        MediaType.APPLICATION_JSON));

        List<NotifierEvent> events = client.listEvents("home-docs", "document:42", 123456789L);

        assertThat(events).extracting(NotifierEvent::status).containsExactly(NotifierEvent.Status.ACTIVE,
                NotifierEvent.Status.FINISHED, NotifierEvent.Status.UNKNOWN);
        assertThat(events.get(1).nextPingAt()).isNull();
        assertThat(events.get(1).nagIntervalMinutes()).isNull();
        assertThat(events.get(1).link()).isNull();
        server.verify();
    }

    @Test
    void deleteOneAcceptsNoContentAndAlreadyGone() {
        server.expect(requestTo(BASE + "/api/v1/events/home-docs/abc"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/api/v1/events/home-docs/gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(error("EVENT_NOT_FOUND", "no such event")));

        client.deleteEvent("home-docs", "abc");
        client.deleteEvent("home-docs", "gone"); // already deleted (e.g. by the user in notifier) = success
        server.verify();
    }

    @Test
    void deleteOneDoesNotTreatACodeless404AsDeleted() {
        // E.g. an older notifier without the API: the endpoint itself is missing, nothing was deleted.
        server.expect(requestTo(BASE + "/api/v1/events/home-docs/abc")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.deleteEvent("home-docs", "abc"))
                .isInstanceOfSatisfying(NotifierApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(NotifierErrorCode.UNKNOWN);
                    assertThat(e.httpStatus()).isEqualTo(404);
                });
    }

    @Test
    void deleteByRefReturnsTheCount() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v1/events?")))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(queryParam("source", "home-docs"))
                .andExpect(queryParam("ref", "document:42"))
                .andRespond(withSuccess("{\"deleted\": 2}", MediaType.APPLICATION_JSON));

        assertThat(client.deleteEventsByRef("home-docs", "document:42")).isEqualTo(2);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "401, UNAUTHORIZED",
            "503, API_DISABLED",
            "404, USER_NOT_REGISTERED",
            "404, EVENT_NOT_FOUND",
            "422, VALIDATION_FAILED",
            "422, FIRE_TIME_IN_PAST",
            "400, SOMETHING_NEW",
    })
    void mapsApiErrorCodes(int status, String code) {
        server.expect(requestTo(BASE + "/api/v1/events"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body(error(code, "fields: name, fireDate")));

        assertThatThrownBy(() -> client.createEvent(
                new NewEvent(1L, "home-docs", "x", "document:7", "n", "2027-01-10", null, null, null)))
                .isInstanceOfSatisfying(NotifierApiException.class, e -> {
                    NotifierErrorCode expected = code.equals("SOMETHING_NEW") ? NotifierErrorCode.UNKNOWN
                            : NotifierErrorCode.valueOf(code);
                    assertThat(e.code()).isEqualTo(expected);
                    assertThat(e.httpStatus()).isEqualTo(status);
                    assertThat(e.detail()).isEqualTo("fields: name, fireDate"); // VALIDATION_FAILED lists the fields
                });
    }

    @Test
    void codeless401IsUnauthorized() {
        server.expect(requestTo(BASE + "/api/v1/users/1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.findUser(1L))
                .isInstanceOfSatisfying(NotifierApiException.class,
                        e -> assertThat(e.code()).isEqualTo(NotifierErrorCode.UNAUTHORIZED));
    }

    @Test
    void serverErrorWithoutCodeMeansUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/users/1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_HTML)
                        .body("<html><body>Whitelabel Error Page</body></html>"));
        server.expect(requestTo(BASE + "/api/v1/users/2")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.findUser(1L)).isInstanceOf(NotifierUnavailableException.class);
        assertThatThrownBy(() -> client.findUser(2L)).isInstanceOf(NotifierUnavailableException.class);
    }

    @Test
    void transportTimeoutMeansUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/users/1"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.findUser(1L)).isInstanceOf(NotifierUnavailableException.class);
    }

    @Test
    void unreadableSuccessBodyMeansUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/users/1"))
                .andRespond(withSuccess("<html>not the API</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.findUser(1L)).isInstanceOf(NotifierUnavailableException.class);
    }

    @Test
    void disabledWithoutUrl() {
        NotifierClient disabled = new NotifierClient(RestClient.builder(), properties(" ", null, null));

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(client.isEnabled()).isTrue();
    }

    // ----- the real transport from NotifierClientConfig: are the timeouts actually wired? -----

    @Test
    void realTransportGivesUpAfterTheReadTimeout() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(3000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            NotifierProperties props = properties("http://127.0.0.1:" + slow.getAddress().getPort(),
                    Duration.ofSeconds(2), Duration.ofMillis(300));
            NotifierClient real = new NotifierClient(NotifierClientConfig.restClientBuilder(props), props);

            long started = System.nanoTime();
            assertThatThrownBy(() -> real.findUser(1L))
                    .isInstanceOf(NotifierUnavailableException.class)
                    .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void realTransportReportsARefusedConnectionAsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // free a port nothing listens on
        }
        NotifierProperties props = properties("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2), Duration.ofSeconds(5));
        NotifierClient real = new NotifierClient(NotifierClientConfig.restClientBuilder(props), props);

        assertThatThrownBy(() -> real.listEvents("home-docs", "document:1", null))
                .isInstanceOf(NotifierUnavailableException.class)
                // ConnectException; its own root cause differs by platform (e.g. ClosedChannelException on Windows)
                .hasRootCauseInstanceOf(IOException.class);
    }
}
