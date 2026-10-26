package com.example.homedocsregistrar.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Wires the {@link NotifierClient} over the JDK {@link HttpClient} with short connect/read timeouts. The
 * builder is assembled here (spring-boot-starter-web 4.x doesn't auto-configure a {@code RestClient.Builder}),
 * which also keeps the transport separate from the client so tests can swap it for a mock server.
 */
@Configuration
@EnableConfigurationProperties(NotifierProperties.class)
public class NotifierClientConfig {

    private static final Logger log = LoggerFactory.getLogger(NotifierClientConfig.class);

    @Bean
    public NotifierClient notifierClient(NotifierProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("NOTIFIER_URL is not set - reminders (notifier integration) are disabled");
        } else if (properties.apiToken() == null || properties.apiToken().isBlank()) {
            log.warn("NOTIFIER_URL is set but NOTIFIER_API_TOKEN is blank - notifier will reject every call (401)");
        } else {
            log.info("Reminders enabled via notifier at {}", properties.url());
        }
        return new NotifierClient(restClientBuilder(properties), properties);
    }

    /**
     * A builder whose transport enforces the configured timeouts. HTTP/1.1 is pinned: on plain http the
     * JDK client would otherwise try an h2c upgrade, which some servers mishandle on requests with a body.
     */
    static RestClient.Builder restClientBuilder(NotifierProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
