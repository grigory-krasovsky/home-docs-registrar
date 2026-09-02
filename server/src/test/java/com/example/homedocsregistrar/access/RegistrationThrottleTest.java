package com.example.homedocsregistrar.access;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationThrottleTest {

    /** A hand-advanced monotonic clock so cooldown/window behaviour is testable without sleeping. */
    private final long[] now = {0L};
    private final LongSupplier clock = () -> now[0];

    @Test
    void oneUserIsLimitedToOneNotificationPerCooldown() {
        RegistrationThrottle throttle = new RegistrationThrottle(clock);

        assertThat(throttle.allowNotification(1L)).isTrue();   // first request goes through
        assertThat(throttle.allowNotification(1L)).isFalse();  // immediate repeat is throttled

        now[0] = RegistrationThrottle.USER_COOLDOWN.toNanos() - 1;
        assertThat(throttle.allowNotification(1L)).isFalse();  // still inside the cooldown

        now[0] = RegistrationThrottle.USER_COOLDOWN.toNanos();
        assertThat(throttle.allowNotification(1L)).isTrue();   // cooldown elapsed -> allowed again
    }

    @Test
    void globalWindowCapsNotificationsAcrossManyAccounts() {
        RegistrationThrottle throttle = new RegistrationThrottle(clock);

        // A flood of distinct user ids saturates the global window...
        for (int userId = 1; userId <= RegistrationThrottle.GLOBAL_MAX_PER_WINDOW; userId++) {
            assertThat(throttle.allowNotification(userId)).isTrue();
        }
        assertThat(throttle.allowNotification(9999L)).isFalse(); // cap reached, a fresh account is blocked

        // ...until the window rolls over.
        now[0] = RegistrationThrottle.GLOBAL_WINDOW.toNanos();
        assertThat(throttle.allowNotification(9999L)).isTrue();
    }
}
