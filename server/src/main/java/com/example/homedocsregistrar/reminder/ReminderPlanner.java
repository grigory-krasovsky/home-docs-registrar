package com.example.homedocsregistrar.reminder;

import com.example.homedocsregistrar.extraction.ReminderHint;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure date logic for reminders: validates the model's suggested deadlines, applies the lead time, and
 * parses/validates what the user types into the reminder form. No I/O — unit-tested directly.
 */
public final class ReminderPlanner {

    /** At most this many suggestions per document — more would clutter the card. */
    public static final int MAX_SUGGESTIONS = 3;

    /** Deadlines further out than this are implausible for a reminder (or a misread year) and are dropped. */
    public static final Period MAX_HORIZON = Period.ofYears(10);

    /** notifier's limit on the event name. */
    public static final int MAX_NAME_LENGTH = 255;

    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern USER_DATE = Pattern.compile("(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})");
    private static final Pattern USER_TIME = Pattern.compile("(\\d{1,2})[:.](\\d{2})");

    private ReminderPlanner() {
    }

    /**
     * Turn the model's hints into proposals: drop malformed / past / too-distant deadlines and ones before
     * the document's own date, compute each fire date, de-duplicate (same kind + deadline), and keep the
     * {@link #MAX_SUGGESTIONS} soonest.
     *
     * @param documentTitle used in the name when the model gave no title; may be null
     * @param docDate       the document's date, if known; a deadline before it is a misreading
     * @param today         today in the user's timezone
     */
    public static List<ReminderProposal> plan(List<ReminderHint> hints, String documentTitle, LocalDate docDate,
                                              LocalDate today) {
        if (hints == null || hints.isEmpty()) {
            return List.of();
        }
        Map<String, ReminderProposal> unique = new LinkedHashMap<>();
        for (ReminderHint hint : hints) {
            if (hint == null) {
                continue;
            }
            Optional<LocalDate> due = validDueDate(hint.date(), docDate, today);
            if (due.isEmpty()) {
                continue;
            }
            ReminderKind kind = ReminderKind.of(hint.kind());
            LocalDate fire = fireDate(kind, due.get(), today);
            String name = name(hint.title(), kind, documentTitle, due.get(), fire);
            unique.putIfAbsent(kind + "|" + due.get(), new ReminderProposal(kind, name, due.get(), fire));
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(ReminderProposal::dueDate))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /** A usable deadline: strict ISO {@code yyyy-MM-dd}, not past, within the horizon, not before the document. */
    static Optional<LocalDate> validDueDate(String iso, LocalDate docDate, LocalDate today) {
        if (iso == null || iso.isBlank()) {
            return Optional.empty();
        }
        LocalDate due;
        try {
            due = LocalDate.parse(iso.strip());
        } catch (DateTimeParseException e) {
            return Optional.empty(); // relative («через год») or garbled — only absolute ISO dates are accepted
        }
        if (due.isBefore(today) || due.isAfter(today.plus(MAX_HORIZON))) {
            return Optional.empty();
        }
        if (docDate != null && due.isBefore(docDate)) {
            return Optional.empty();
        }
        return Optional.of(due);
    }

    /** When to remind: {@code lead} days before the deadline, but not earlier than today. */
    public static LocalDate fireDate(ReminderKind kind, LocalDate due, LocalDate today) {
        LocalDate early = due.minusDays(kind.leadDays());
        return early.isBefore(today) ? today : early;
    }

    /** The model's title (or a default from the kind + document), plus the deadline when firing earlier. */
    static String name(String title, ReminderKind kind, String documentTitle, LocalDate due, LocalDate fire) {
        String base = title == null || title.isBlank()
                ? kind.defaultTitle() + (documentTitle == null || documentTitle.isBlank() ? "" : ": " + documentTitle.strip())
                : title.strip().replaceAll("\\s+", " ");
        String suffix = fire.isBefore(due) ? " (срок " + formatDate(due) + ")" : "";
        return truncate(base, MAX_NAME_LENGTH - suffix.length()) + suffix;
    }

    /** A date typed as ДД.ММ.ГГГГ (also «8.3.2027», «08-03-2027», «08/03/2027»); empty if not a real date. */
    public static Optional<LocalDate> parseUserDate(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = USER_DATE.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1))));
        } catch (DateTimeException e) {
            return Optional.empty(); // e.g. 31.02.2027
        }
    }

    /** A time typed as ЧЧ:ММ (also «9:30», «09.30»); empty if not a real time. */
    public static Optional<LocalTime> parseUserTime(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = USER_TIME.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        } catch (DateTimeException e) {
            return Optional.empty(); // e.g. 25:00
        }
    }

    /** Problem with a reminder date the user typed, or null when it's fine. */
    public static String dateProblem(LocalDate date, LocalDate today) {
        if (date.isBefore(today)) {
            return "Эта дата уже прошла.";
        }
        if (date.isAfter(today.plus(MAX_HORIZON))) {
            return "Слишком далеко — не дальше " + formatDate(today.plus(MAX_HORIZON)) + ".";
        }
        return null;
    }

    /** True when {@code time} on {@code date} is not after {@code now} (same rule as notifier's FIRE_TIME_IN_PAST). */
    public static boolean isPast(LocalDate date, LocalTime time, LocalDateTime now) {
        LocalDateTime fire = date.atTime(time);
        return !fire.isAfter(now.truncatedTo(ChronoUnit.MINUTES));
    }

    /** A reminder name as notifier accepts it: single-spaced, trimmed, at most {@link #MAX_NAME_LENGTH}. */
    public static String formatName(String name) {
        return truncate(name.strip().replaceAll("\\s+", " "), MAX_NAME_LENGTH);
    }

    public static String formatDate(LocalDate date) {
        return date.format(RU_DATE);
    }

    public static String formatTime(LocalTime time) {
        return time.format(TIME);
    }

    /** {@code date} as notifier's {@code yyyy-MM-dd}. */
    static String isoDate(LocalDate date) {
        return date.toString();
    }

    static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int cut = max - 1;
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--; // don't split an emoji / surrogate pair
        }
        return value.substring(0, cut) + "…";
    }
}
