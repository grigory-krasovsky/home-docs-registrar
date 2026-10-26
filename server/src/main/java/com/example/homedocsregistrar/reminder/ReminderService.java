package com.example.homedocsregistrar.reminder;

import com.example.homedocsregistrar.telegram.TelegramSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reminders for documents, kept in the sibling notifier bot (the source of truth — the registrar stores no
 * copy). Every reminder is personal (created for the Telegram user who asks; in a private chat the chat id
 * is that user's id) and tied to a document via {@code ref = document:<id>}; it carries a deep link back to
 * the document card in this bot. Disabled when {@code NOTIFIER_URL} is blank.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    /** Our {@code source} in notifier; externalIds are unique within it. */
    public static final String SOURCE = "home-docs";

    private final NotifierClient client;
    private final NotifierProperties properties;
    private final TelegramSender sender;
    private final boolean suggest;
    private final ZoneId defaultZone;

    public ReminderService(NotifierClient client, NotifierProperties properties, TelegramSender sender,
                           @Value("${reminders.suggest:true}") boolean suggest,
                           @Value("${reminders.default-zone:Europe/Moscow}") String defaultZone) {
        this.client = client;
        this.properties = properties;
        this.sender = sender;
        this.suggest = suggest;
        this.defaultZone = ZoneId.of(defaultZone);
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    /** LLM reminder suggestions at intake: needs the integration on and {@code reminders.suggest}. */
    public boolean suggestionsEnabled() {
        return isEnabled() && suggest;
    }

    public static String ref(long documentId) {
        return "document:" + documentId;
    }

    /** A fresh externalId — generated when a suggestion/form appears, so retries reuse it (no duplicates). */
    public static String newExternalId() {
        return UUID.randomUUID().toString();
    }

    /** The user's notifier profile; empty when they never pressed /start there. Throws when notifier fails. */
    public Optional<NotifierUser> user(long telegramUserId) {
        return client.findUser(telegramUserId);
    }

    /** This user's reminders for the document, fresh from notifier (FINISHED included). */
    public List<NotifierEvent> list(long telegramUserId, long documentId) {
        return client.listEvents(SOURCE, ref(documentId), telegramUserId);
    }

    /** Create (or idempotently re-create, same externalId) a reminder for the document. */
    public NotifierClient.CreateResult create(long telegramUserId, long documentId, ReminderDraft draft) {
        NewEvent event = new NewEvent(
                telegramUserId,
                SOURCE,
                draft.externalId(),
                ref(documentId),
                ReminderPlanner.formatName(draft.name()),
                ReminderPlanner.isoDate(draft.date()),
                draft.time() == null ? null : ReminderPlanner.formatTime(draft.time()),
                draft.nagIntervalMinutes(),
                link(documentId).orElse(null));
        return client.createEvent(event);
    }

    public void delete(String externalId) {
        client.deleteEvent(SOURCE, externalId);
    }

    /**
     * Best-effort cleanup after a document is deleted: drop its reminders for every user. Never throws — the
     * document is already gone, so a notifier failure only becomes a warning for the user.
     */
    public ReminderCleanup deleteAllForDocument(long documentId) {
        if (!isEnabled()) {
            return ReminderCleanup.ofSkipped();
        }
        try {
            return ReminderCleanup.ofDone(client.deleteEventsByRef(SOURCE, ref(documentId)));
        } catch (RuntimeException e) {
            log.warn("Failed to delete notifier reminders of document {}", documentId, e);
            return ReminderCleanup.ofFailed();
        }
    }

    /**
     * Deep link to the document card in this bot ({@code https://t.me/<bot>?start=doc_<id>}), which notifier
     * shows as a «📄 Документ» button. Empty while the bot's username is unknown (the field is optional).
     */
    public Optional<NotifierLink> link(long documentId) {
        String username = sender.botUsername();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new NotifierLink("Документ #" + documentId,
                "https://t.me/" + username + "?start=doc_" + documentId));
    }

    /** The user's timezone (from notifier), or the configured default when unknown. */
    public ZoneId zone(NotifierUser user) {
        return user == null ? defaultZone : user.zone(defaultZone);
    }

    public LocalDate today(NotifierUser user) {
        return LocalDate.now(zone(user));
    }

    public LocalDateTime now(NotifierUser user) {
        return LocalDateTime.now(zone(user));
    }

    /** Shown instead of the create button when the user isn't connected to notifier. */
    public String connectHint() {
        return "Чтобы получать напоминания, запусти " + notifierBotMention() + " и нажми /start";
    }

    /** {@code @NotifierBot} from config, or a generic name when it isn't configured. */
    public String notifierBotMention() {
        String username = properties.botUsername();
        if (username == null || username.isBlank()) {
            return "бота-напоминалку";
        }
        String bare = username.strip();
        return bare.startsWith("@") ? bare : "@" + bare;
    }

    /**
     * What to create.
     *
     * @param time               local time, or null for the start of the user's working hours
     * @param nagIntervalMinutes repeat until «Готово» every N minutes, or null for no repeats
     */
    public record ReminderDraft(String externalId, String name, LocalDate date, LocalTime time,
                                Integer nagIntervalMinutes) {
    }

    /** How the reminder cleanup after a document deletion went. */
    public record ReminderCleanup(Outcome outcome, int deleted) {

        public enum Outcome {
            /** Integration off (or nothing to clean up). */
            SKIPPED,
            DONE,
            /** notifier failed — its reminders for the document may remain. */
            FAILED
        }

        public static ReminderCleanup ofSkipped() {
            return new ReminderCleanup(Outcome.SKIPPED, 0);
        }

        public static ReminderCleanup ofDone(int deleted) {
            return new ReminderCleanup(Outcome.DONE, deleted);
        }

        public static ReminderCleanup ofFailed() {
            return new ReminderCleanup(Outcome.FAILED, 0);
        }

        public boolean isFailed() {
            return outcome == Outcome.FAILED;
        }
    }
}
