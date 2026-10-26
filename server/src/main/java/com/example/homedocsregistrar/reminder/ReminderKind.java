package com.example.homedocsregistrar.reminder;

import java.util.Locale;

/**
 * The kind of deadline a reminder is for, and how many days before it to remind (the lead time). The
 * registrar applies the lead itself — notifier only ever gets the final fire date.
 */
public enum ReminderKind {
    WARRANTY("Истекает гарантия", 14),
    SERVICE("Пора на ТО", 14),
    CONTRACT_END("Заканчивается договор", 30),
    INSURANCE_END("Заканчивается страховка", 14),
    PAYMENT("Срок оплаты", 3),
    EXPIRY("Истекает срок действия", 30),
    OTHER("Срок по документу", 7);

    private final String defaultTitle;
    private final int leadDays;

    ReminderKind(String defaultTitle, int leadDays) {
        this.defaultTitle = defaultTitle;
        this.leadDays = leadDays;
    }

    /** Name prefix when the model gave no title of its own. */
    public String defaultTitle() {
        return defaultTitle;
    }

    public int leadDays() {
        return leadDays;
    }

    /** Lenient lookup of the model's {@code kind}; anything unrecognized is {@link #OTHER}. */
    public static ReminderKind of(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
