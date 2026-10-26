package com.example.homedocsregistrar.reminder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Connection to the sibling notifier bot's HTTP API (reminders), bound from {@code notifier.*}. A blank
 * {@code url} turns the whole integration off — no reminder buttons or suggestions, and the app boots as
 * usual (same as a blank {@code ANTHROPIC_API_KEY} disables extraction).
 *
 * @param url            base URL, e.g. {@code http://notifier-app:8080} on the VPS (env {@code NOTIFIER_URL})
 * @param apiToken       bearer token shared with notifier (env {@code NOTIFIER_API_TOKEN}); never in git
 * @param botUsername    the notifier bot's @username, for the «запусти @… и нажми /start» hint
 * @param connectTimeout short on purpose: both apps share the VPS, so a slow connect means notifier is down
 * @param readTimeout    how long a single API call may take before the user is told notifier didn't answer
 */
@ConfigurationProperties(prefix = "notifier")
public record NotifierProperties(String url,
                                 String apiToken,
                                 String botUsername,
                                 @DefaultValue("2s") Duration connectTimeout,
                                 @DefaultValue("5s") Duration readTimeout) {

    public boolean isEnabled() {
        return url != null && !url.isBlank();
    }
}
