package com.example.homedocsregistrar.access;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Anti-flood throttle for {@code /register}: a single {@code /register} fans out a notification to every
 * admin, so an unauthenticated user could spam the owner with access requests. Two limits combine:
 * <ul>
 *   <li><b>per user</b> — at most one notification per {@link #USER_COOLDOWN}, so one account can't spam;</li>
 *   <li><b>global</b> — at most {@link #GLOBAL_MAX_PER_WINDOW} notifications per {@link #GLOBAL_WINDOW}, a
 *       backstop against a flood from many accounts (each Telegram user id is otherwise distinct).</li>
 * </ul>
 * State is in-memory and based on a monotonic clock; losing it on restart is benign (worst case a few
 * extra notifications right after a restart). Under an active flood the global cap may briefly delay a
 * genuine request — the owner can still add a user directly in the database.
 */
@Component
public class RegistrationThrottle {

    static final Duration USER_COOLDOWN = Duration.ofMinutes(30);
    static final int GLOBAL_MAX_PER_WINDOW = 20;
    static final Duration GLOBAL_WINDOW = Duration.ofMinutes(10);
    /** Bound the per-user map so a flood of unique ids can't grow it without limit. */
    private static final int MAX_TRACKED_USERS = 10_000;

    private final LongSupplier nanoClock;
    private final Map<Long, Long> lastNotifiedNanos = new HashMap<>();
    private long windowStartNanos;
    private int windowCount;

    public RegistrationThrottle() {
        this(System::nanoTime);
    }

    RegistrationThrottle(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.windowStartNanos = nanoClock.getAsLong();
    }

    /**
     * Whether a {@code /register} from this user should notify admins now. Returns {@code false} (and
     * changes nothing) while the user is in their cooldown or the global window is saturated; otherwise
     * records the grant and returns {@code true}.
     */
    public synchronized boolean allowNotification(long userId) {
        long now = nanoClock.getAsLong();
        Long last = lastNotifiedNanos.get(userId);
        if (last != null && now - last < USER_COOLDOWN.toNanos()) {
            return false;
        }
        if (now - windowStartNanos >= GLOBAL_WINDOW.toNanos()) {
            windowStartNanos = now;
            windowCount = 0;
        }
        if (windowCount >= GLOBAL_MAX_PER_WINDOW) {
            return false;
        }
        windowCount++;
        pruneIfLarge(now);
        lastNotifiedNanos.put(userId, now);
        return true;
    }

    /** Drop entries older than the cooldown once the map grows large, so it stays bounded under a flood. */
    private void pruneIfLarge(long now) {
        if (lastNotifiedNanos.size() < MAX_TRACKED_USERS) {
            return;
        }
        long cooldownNanos = USER_COOLDOWN.toNanos();
        Iterator<Map.Entry<Long, Long>> it = lastNotifiedNanos.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= cooldownNanos) {
                it.remove();
            }
        }
    }
}
