package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.extraction.ReminderHint;
import com.example.homedocsregistrar.reminder.NotifierApiException;
import com.example.homedocsregistrar.reminder.NotifierClient;
import com.example.homedocsregistrar.reminder.NotifierEvent;
import com.example.homedocsregistrar.reminder.NotifierException;
import com.example.homedocsregistrar.reminder.NotifierUnavailableException;
import com.example.homedocsregistrar.reminder.NotifierUser;
import com.example.homedocsregistrar.reminder.ReminderPlanner;
import com.example.homedocsregistrar.reminder.ReminderProposal;
import com.example.homedocsregistrar.reminder.ReminderService;
import com.example.homedocsregistrar.reminder.ReminderService.ReminderDraft;
import com.example.homedocsregistrar.retrieval.DocumentRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reminder side of the bot, kept out of {@link DocumentIntakeBot} (which only routes {@code rem:*}
 * callbacks and the form's typed input here):
 * <ul>
 *   <li>LLM suggestions on the «Сохранено ✅» card — ✅ Создать / ✏️ Изменить / ✖ Не надо, edited in place;</li>
 *   <li>⏰ Напоминания (card button or {@code /remind <id>}) — this user's reminders for the document, fresh
 *       from notifier, each with 🗑, plus ➕ Новое;</li>
 *   <li>the create form: name → date → time (optional) → repeat → ✅ Создать, one message edited in place.</li>
 * </ul>
 * notifier is the source of truth; only short-lived dialog state lives here. Each suggestion/form gets its
 * externalId when it appears, so a repeated tap or a retry after a timeout never creates a duplicate.
 * State is in memory and benign to lose on restart (a stale button just says so).
 */
@Component
public class ReminderDialog {

    private static final Logger log = LoggerFactory.getLogger(ReminderDialog.class);

    /** Pending suggestions/forms older than this are dropped (lazily, on the next put). */
    private static final Duration PENDING_TTL = Duration.ofHours(24);

    private static final int NAG_HOURLY = 60;
    private static final int NAG_DAILY = 1440;

    /** Suggested deadlines nag daily until «Готово» — the point is not to miss them; ✏️ can change it. */
    private static final int SUGGESTION_NAG = NAG_DAILY;

    /** Telegram's limit on a button's callback data. */
    private static final int MAX_CALLBACK_BYTES = 64;

    /** Telegram's limit on a callback answer (toast). */
    private static final int MAX_TOAST = 200;

    private final TelegramSender sender;
    private final ReminderService reminders;
    private final DocumentRetrievalService retrievalService;

    /** The create form in progress, per chat (one at a time; a new one replaces it). */
    private final Map<Long, Form> forms = new ConcurrentHashMap<>();
    /** Saved-document cards carrying suggestions, per chat + document. */
    private final Map<CardKey, Card> cards = new ConcurrentHashMap<>();
    /** The reminders last listed in each chat (externalId → name), to name one in the 🗑 confirmation. */
    private final Map<Long, ListView> lists = new ConcurrentHashMap<>();

    public ReminderDialog(TelegramSender sender, ReminderService reminders, DocumentRetrievalService retrievalService) {
        this.sender = sender;
        this.reminders = reminders;
        this.retrievalService = retrievalService;
    }

    private enum Step { NAME, DATE, TIME, NAG, CONFIRM }

    private enum SuggestionState { OPEN, CREATED, DISMISSED }

    private record CardKey(long chatId, long docId) {
    }

    private record ListView(long docId, Map<String, String> names, Instant createdAt) {
    }

    /** One suggested reminder on a card; its externalId is fixed when it's offered. */
    private static final class Suggestion {
        final String externalId;
        final ReminderProposal proposal;
        SuggestionState state = SuggestionState.OPEN;
        String createdLine;

        Suggestion(String externalId, ReminderProposal proposal) {
            this.externalId = externalId;
            this.proposal = proposal;
        }
    }

    /** A «Сохранено ✅» card with suggestions; base text/rows are kept so the card can be re-rendered. */
    private static final class Card {
        final long docId;
        final String baseText;
        final List<InlineKeyboardRow> baseRows;
        final List<Suggestion> items;
        final Instant createdAt = Instant.now();
        int messageId;
        boolean notRegistered;

        Card(long docId, String baseText, List<InlineKeyboardRow> baseRows, List<Suggestion> items) {
            this.docId = docId;
            this.baseText = baseText;
            this.baseRows = baseRows;
            this.items = items;
        }

        boolean anyOpen() {
            return items.stream().anyMatch(s -> s.state == SuggestionState.OPEN);
        }
    }

    /** The create form. {@code origin*} link it to the card suggestion it edits (null/-1 for ➕ Новое). */
    private static final class Form {
        final long docId;
        final String externalId;
        final CardKey originCard;
        final int originIndex;
        final Instant createdAt = Instant.now();
        int messageId;
        NotifierUser user;
        String name;
        LocalDate date;
        LocalTime time;
        Integer nag;
        Step step = Step.NAME;
        String notice;

        Form(long docId, String externalId, CardKey originCard, int originIndex) {
            this.docId = docId;
            this.externalId = externalId;
            this.originCard = originCard;
            this.originIndex = originIndex;
        }

        boolean awaitsText() {
            return step == Step.NAME || step == Step.DATE || step == Step.TIME;
        }
    }

    // ----- entry points used by DocumentIntakeBot -----

    public boolean isEnabled() {
        return reminders.isEnabled();
    }

    /** Today's date for the vision prompt when suggestions are on; null means «don't ask the model». */
    public LocalDate suggestionsAsOf() {
        return reminders.suggestionsEnabled() ? reminders.today(null) : null;
    }

    /** The card's «⏰ Напоминания» button; empty when the integration is off. */
    public Optional<InlineKeyboardButton> listButton(long docId) {
        return isEnabled() ? Optional.of(button("⏰ Напоминания", "rem:list:" + docId)) : Optional.empty();
    }

    /**
     * Send the «Сохранено ✅» card. When the model suggested deadlines (and suggestions are on), they go into
     * this same message — a text block plus a button row each — instead of a separate one, so the chat after
     * a recognition stays «card + section offer». Without suggestions it's the plain card.
     */
    public void sendSavedCard(long chatId, Document document, String text, List<InlineKeyboardRow> baseRows,
                              List<ReminderHint> hints) {
        long docId = document.getId();
        List<ReminderProposal> proposals = List.of();
        boolean notRegistered = false;
        if (reminders.suggestionsEnabled() && hints != null && !hints.isEmpty()) {
            proposals = ReminderPlanner.plan(hints, document.getTitle(), document.getDocDate(), reminders.today(null));
            if (!proposals.isEmpty()) {
                try {
                    Optional<NotifierUser> user = reminders.user(chatId);
                    notRegistered = user.isEmpty();
                    if (user.isPresent()) { // re-validate against the user's own «today»
                        proposals = ReminderPlanner.plan(hints, document.getTitle(), document.getDocDate(),
                                reminders.today(user.get()));
                    }
                } catch (NotifierException e) {
                    // Offer the buttons anyway: a tap retries and explains a persistent failure.
                    log.warn("notifier unavailable while offering reminders for document {}: {}", docId, e.getMessage());
                }
            }
        }
        if (proposals.isEmpty()) {
            sender.send(chatId, text, keyboard(baseRows));
            return;
        }
        List<Suggestion> items = proposals.stream()
                .map(proposal -> new Suggestion(ReminderService.newExternalId(), proposal))
                .toList();
        Card card = new Card(docId, text, List.copyOf(baseRows), items);
        card.notRegistered = notRegistered;
        Integer messageId = sender.send(chatId, cardText(card), cardKeyboard(card));
        if (messageId == null) {
            return;
        }
        card.messageId = messageId;
        purgeStale();
        cards.put(new CardKey(chatId, docId), card);
    }

    /** {@code /remind <id>}: the document's reminders as a fresh message. */
    public void openList(long chatId, long docId) {
        if (!isEnabled()) {
            sender.send(chatId, "Напоминания не настроены.");
            return;
        }
        renderList(chatId, null, docId, null);
    }

    /**
     * Typed input for a form waiting for a name / date / time, and {@code /cancel} for it. Returns true when
     * consumed. Any other command abandons such a form (like the bot's other dialogs) and isn't consumed. A
     * form on a button step (repeat / confirm) doesn't take text — its ✖ Отмена button closes it.
     */
    public boolean onText(long chatId, int messageId, String text) {
        Form form = forms.get(chatId);
        if (form == null || !form.awaitsText()) {
            return false;
        }
        String trimmed = text.strip();
        String command = trimmed.toLowerCase(Locale.ROOT);
        if (command.startsWith("/cancel")) {
            forms.remove(chatId);
            sender.deleteMessage(chatId, messageId);
            sender.edit(chatId, form.messageId, "Отменено.", null);
            return true;
        }
        if (command.startsWith("/")) {
            forms.remove(chatId);
            return false;
        }
        sender.deleteMessage(chatId, messageId); // the typed value disappears; the form message updates
        applyInput(form, trimmed);
        showForm(chatId, form.messageId, form);
        return true;
    }

    /** Another dialog of the bot starts waiting for text: drop a form that was waiting for text too. */
    public void cancelInput(long chatId) {
        forms.computeIfPresent(chatId, (id, form) -> form.awaitsText() ? null : form);
    }

    /**
     * Route a {@code rem:*} callback. Returns true when afterwards the form waits for typed input, so the bot
     * drops its own pending text inputs (one text consumer at a time).
     */
    public boolean onCallback(CallbackQuery callback) {
        var message = callback.getMessage();
        Long chatId = message == null ? null : message.getChatId();
        Integer messageId = message == null ? null : message.getMessageId();
        // rem:<action>:<arg>[:<arg>[:<arg>]]; for del/dy the 4th part is the externalId as-is.
        String[] parts = callback.getData().split(":", 5);
        if (chatId == null || parts.length < 2) {
            answer(callback, null);
            return false;
        }
        if (!isEnabled()) {
            answer(callback, "Напоминания не настроены.");
            return false;
        }
        Long docId = parts.length > 2 ? parseLong(parts[2]) : null;
        switch (parts[1]) {
            case "list" -> { // from a card: a fresh message, so the card stays above
                answer(callback, null);
                if (docId != null) {
                    renderList(chatId, null, docId, null);
                }
            }
            case "ref" -> {
                answer(callback, null);
                if (docId != null) {
                    renderList(chatId, messageId, docId, null);
                }
            }
            case "new" -> {
                if (docId != null) {
                    startForm(callback, chatId, messageId, docId);
                } else {
                    answer(callback, null);
                }
            }
            case "del", "dy" -> {
                String[] target = callback.getData().split(":", 4);
                if (docId == null || target.length < 4 || target[3].isBlank()) {
                    answer(callback, null);
                } else if (parts[1].equals("del")) {
                    confirmDelete(callback, chatId, messageId, docId, target[3]);
                } else {
                    performDelete(callback, chatId, messageId, docId, target[3]);
                }
            }
            case "close" -> {
                answer(callback, null);
                forms.computeIfPresent(chatId, (id, form) -> messageId != null && form.messageId == messageId ? null : form);
                render(chatId, messageId, "Закрыто.", null);
            }
            case "f" -> onFormCallback(callback, chatId, messageId, parts);
            case "s" -> onSuggestionCallback(callback, chatId, parts);
            default -> answer(callback, null);
        }
        Form form = forms.get(chatId);
        return form != null && form.awaitsText();
    }

    // ----- suggestions on the saved card: rem:s:<ok|ed|no>:<docId>:<index> | rem:s:<none|chk>:<docId> -----

    private void onSuggestionCallback(CallbackQuery callback, long chatId, String[] parts) {
        String action = parts.length > 2 ? parts[2] : "";
        Long docId = parts.length > 3 ? parseLong(parts[3]) : null;
        CardKey key = docId == null ? null : new CardKey(chatId, docId);
        Card card = key == null ? null : cards.get(key);
        if (card == null) {
            answer(callback, "Подсказка устарела — откройте ⏰ Напоминания.");
            return;
        }
        Long index = parts.length > 4 ? parseLong(parts[4]) : null;
        Suggestion suggestion = index != null && index >= 0 && index < card.items.size()
                ? card.items.get(index.intValue())
                : null;
        switch (action) {
            case "ok" -> createSuggestion(callback, chatId, key, card, suggestion);
            case "ed" -> editSuggestion(callback, chatId, key, card, index, suggestion);
            case "no" -> {
                if (suggestion != null && suggestion.state == SuggestionState.OPEN) {
                    suggestion.state = SuggestionState.DISMISSED;
                }
                answer(callback, null);
                refreshCard(chatId, key, card);
            }
            case "none" -> {
                card.items.stream().filter(s -> s.state == SuggestionState.OPEN)
                        .forEach(s -> s.state = SuggestionState.DISMISSED);
                answer(callback, null);
                refreshCard(chatId, key, card);
            }
            case "chk" -> recheckRegistration(callback, chatId, key, card);
            default -> answer(callback, null);
        }
    }

    /** ✅ Создать: create the suggestion as offered (fire date, default time, daily nag). */
    private void createSuggestion(CallbackQuery callback, long chatId, CardKey key, Card card, Suggestion suggestion) {
        if (suggestion == null || suggestion.state != SuggestionState.OPEN) {
            answer(callback, null);
            refreshCard(chatId, key, card);
            return;
        }
        ReminderProposal proposal = suggestion.proposal;
        ReminderDraft draft = new ReminderDraft(suggestion.externalId, proposal.name(), proposal.fireDate(), null,
                SUGGESTION_NAG);
        try {
            NotifierClient.CreateResult result = reminders.create(chatId, card.docId, draft);
            suggestion.state = SuggestionState.CREATED;
            // A 200 repeat returns the event as it was first created (e.g. via ✏️) — show that, not the proposal.
            suggestion.createdLine = result.event() != null ? eventSummary(result.event()) : proposalLine(proposal);
            answer(callback, "Напоминание создано");
            refreshCard(chatId, key, card);
        } catch (NotifierApiException e) {
            switch (e.code()) {
                case USER_NOT_REGISTERED -> {
                    card.notRegistered = true;
                    answer(callback, null);
                    refreshCard(chatId, key, card);
                }
                case FIRE_TIME_IN_PAST -> answer(callback, "Дата уже прошла — нажмите ✏️, чтобы выбрать другую.");
                default -> answer(callback, problem(e));
            }
        } catch (NotifierUnavailableException e) {
            answer(callback, "Сервис напоминаний не ответил — нажмите ещё раз.");
        }
    }

    /** ✏️ Изменить: the form, prefilled and with the suggestion's externalId, as a fresh message under the card. */
    private void editSuggestion(CallbackQuery callback, long chatId, CardKey key, Card card, Long index,
                                Suggestion suggestion) {
        if (suggestion == null || suggestion.state != SuggestionState.OPEN) {
            answer(callback, null);
            refreshCard(chatId, key, card);
            return;
        }
        NotifierUser user = null;
        try {
            Optional<NotifierUser> found = reminders.user(chatId);
            if (found.isEmpty()) {
                card.notRegistered = true;
                answer(callback, null);
                refreshCard(chatId, key, card);
                return;
            }
            user = found.get();
        } catch (NotifierException e) {
            // Let the user fill the form anyway; ✅ Создать retries and explains a persistent failure.
            log.warn("notifier unavailable while opening the reminder form: {}", e.getMessage());
        }
        answer(callback, null);
        Form form = new Form(card.docId, suggestion.externalId, key, index.intValue());
        form.user = user;
        form.name = suggestion.proposal.name();
        form.date = suggestion.proposal.fireDate();
        form.nag = SUGGESTION_NAG;
        showForm(chatId, null, form);
    }

    /** 🔄 Я подключился: re-check notifier registration and, if so, bring back the create buttons. */
    private void recheckRegistration(CallbackQuery callback, long chatId, CardKey key, Card card) {
        try {
            if (reminders.user(chatId).isPresent()) {
                card.notRegistered = false;
                answer(callback, "Готово — можно создавать");
                refreshCard(chatId, key, card);
            } else {
                answer(callback, "Пока не вижу вас в " + reminders.notifierBotMention() + " — нажмите там /start.");
            }
        } catch (NotifierException e) {
            answer(callback, problem(e));
        }
    }

    private void refreshCard(long chatId, CardKey key, Card card) {
        sender.edit(chatId, card.messageId, cardText(card), cardKeyboard(card));
        if (!card.anyOpen()) {
            cards.remove(key); // nothing left to act on
        }
    }

    private String cardText(Card card) {
        StringBuilder text = new StringBuilder(card.baseText);
        if (card.items.stream().allMatch(s -> s.state == SuggestionState.DISMISSED)) {
            return text.toString();
        }
        boolean anyOpen = card.anyOpen();
        boolean numbered = card.items.size() > 1;
        text.append("\n\n⏰ ").append(anyOpen ? "Предлагаю напомнить:" : "Напоминания:");
        for (int i = 0; i < card.items.size(); i++) {
            Suggestion suggestion = card.items.get(i);
            switch (suggestion.state) {
                case CREATED -> text.append("\n✅ ").append(suggestion.createdLine);
                case OPEN -> text.append('\n').append(numbered ? (i + 1) + ") " : "• ")
                        .append(proposalLine(suggestion.proposal));
                case DISMISSED -> { }
            }
        }
        if (anyOpen && card.notRegistered) {
            text.append("\n\n").append(reminders.connectHint());
        }
        return text.toString();
    }

    private InlineKeyboardMarkup cardKeyboard(Card card) {
        var keyboard = InlineKeyboardMarkup.builder();
        card.baseRows.forEach(keyboard::keyboardRow);
        if (!card.anyOpen()) {
            return keyboard.build();
        }
        String doc = String.valueOf(card.docId);
        if (card.notRegistered) {
            keyboard.keyboardRow(row(button("🔄 Я подключился", "rem:s:chk:" + doc),
                    button("✖ Не надо", "rem:s:none:" + doc)));
            return keyboard.build();
        }
        boolean numbered = card.items.size() > 1;
        for (int i = 0; i < card.items.size(); i++) {
            if (card.items.get(i).state != SuggestionState.OPEN) {
                continue;
            }
            String n = numbered ? " " + (i + 1) : "";
            String target = doc + ":" + i;
            keyboard.keyboardRow(row(
                    button("✅ Создать" + n, "rem:s:ok:" + target),
                    button(numbered ? "✏️" + n : "✏️ Изменить", "rem:s:ed:" + target),
                    button(numbered ? "✖" + n : "✖ Не надо", "rem:s:no:" + target)));
        }
        return keyboard.build();
    }

    /** «08.03.2027 — Истекает гарантия: … (срок 22.03.2027) · 🔁 каждый день». */
    private static String proposalLine(ReminderProposal proposal) {
        return ReminderPlanner.formatDate(proposal.fireDate()) + " — " + proposal.name() + " · 🔁 " + nagText(SUGGESTION_NAG);
    }

    // ----- the document's reminders: list, 🗑 delete -----

    private void renderList(long chatId, Integer messageId, long docId, String notice) {
        Optional<Document> document = retrievalService.byId(docId);
        if (document.isEmpty()) {
            render(chatId, messageId, "Документ #" + docId + " не найден.", null);
            return;
        }
        StringBuilder text = new StringBuilder("⏰ Напоминания · документ #").append(docId)
                .append(titleSuffix(document.get()));
        if (notice != null) {
            text.append('\n').append(notice);
        }
        try {
            Optional<NotifierUser> user = reminders.user(chatId);
            if (user.isEmpty()) {
                text.append("\n\n").append(reminders.connectHint());
                render(chatId, messageId, text.toString(), keyboard(List.of(
                        row(button("🔄 Я подключился", "rem:ref:" + docId)), closeRow())));
                return;
            }
            ZoneId zone = reminders.zone(user.get());
            List<NotifierEvent> events = reminders.list(chatId, docId).stream()
                    .sorted(Comparator.comparing((NotifierEvent e) -> e.status() == NotifierEvent.Status.FINISHED)
                            .thenComparing(NotifierEvent::fireAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            var keyboard = InlineKeyboardMarkup.builder();
            Map<String, String> names = new HashMap<>();
            if (events.isEmpty()) {
                text.append("\n\nПока нет напоминаний.");
            } else {
                text.append('\n');
                for (NotifierEvent event : events) {
                    text.append('\n').append(eventLine(event, zone));
                    String data = event.externalId() == null ? null : "rem:del:" + docId + ":" + event.externalId();
                    if (data != null && fits(data)) {
                        names.put(event.externalId(), nameOf(event));
                        keyboard.keyboardRow(row(button("🗑 " + truncate(nameOf(event), 30), data)));
                    }
                }
            }
            keyboard.keyboardRow(row(button("➕ Новое", "rem:new:" + docId), button("✖ Закрыть", "rem:close")));
            render(chatId, messageId, text.toString(), keyboard.build());
            purgeStale();
            lists.put(chatId, new ListView(docId, names, Instant.now()));
        } catch (NotifierException e) {
            text.append("\n\n⚠️ ").append(problem(e));
            render(chatId, messageId, text.toString(), keyboard(List.of(
                    row(button("🔄 Повторить", "rem:ref:" + docId), button("✖ Закрыть", "rem:close")))));
        }
    }

    private void confirmDelete(CallbackQuery callback, long chatId, Integer messageId, long docId, String externalId) {
        answer(callback, null);
        String name = listedName(chatId, docId, externalId);
        String question = name == null ? "🗑 Удалить это напоминание?" : "🗑 Удалить напоминание «" + name + "»?";
        render(chatId, messageId, question + "\nИз notifier оно тоже исчезнет.", keyboard(List.of(row(
                button("✅ Удалить", "rem:dy:" + docId + ":" + externalId),
                button("✖ Нет", "rem:ref:" + docId)))));
    }

    private void performDelete(CallbackQuery callback, long chatId, Integer messageId, long docId, String externalId) {
        String name = listedName(chatId, docId, externalId);
        try {
            reminders.delete(externalId);
            answer(callback, "Удалено");
            renderList(chatId, messageId, docId, "🗑 Удалено" + (name == null ? "." : ": «" + name + "»"));
        } catch (NotifierUnavailableException e) {
            answer(callback, "Сервис напоминаний не ответил — нажмите ещё раз.");
        } catch (NotifierApiException e) {
            answer(callback, problem(e));
        }
    }

    private String listedName(long chatId, long docId, String externalId) {
        ListView view = lists.get(chatId);
        return view == null || view.docId() != docId ? null : view.names().get(externalId);
    }

    /** «⏰ 08.03.2027 10:00 — name · 🔁 каждый день», with the status for paused/finished ones. */
    private static String eventLine(NotifierEvent event, ZoneId zone) {
        NotifierEvent.Status status = event.status() == null ? NotifierEvent.Status.UNKNOWN : event.status();
        String icon = switch (status) {
            case ACTIVE -> "⏰";
            case PAUSED -> "⏸";
            case FINISHED -> "✅";
            case UNKNOWN -> "•";
        };
        StringBuilder line = new StringBuilder(icon).append(' ');
        LocalDateTime local = event.fireAtLocal() != null ? event.fireAtLocal()
                : event.fireAt() == null ? null : LocalDateTime.ofInstant(event.fireAt(), zone);
        if (local != null) {
            line.append(ReminderPlanner.formatDate(local.toLocalDate())).append(' ')
                    .append(ReminderPlanner.formatTime(local.toLocalTime())).append(" — ");
        }
        line.append(nameOf(event));
        Integer nag = event.nagIntervalMinutes();
        if (nag != null && nag > 0) {
            line.append(" · 🔁 ").append(nagText(nag));
        }
        switch (status) {
            case PAUSED -> line.append(" (на паузе)");
            case FINISHED -> line.append(" (выполнено)");
            case ACTIVE -> {
                // Already fired and nagging: show when the next ping comes.
                if (event.nextPingAt() != null && event.fireAt() != null && event.nextPingAt().isAfter(event.fireAt())) {
                    LocalDateTime next = LocalDateTime.ofInstant(event.nextPingAt(), zone);
                    line.append(" · след. ").append(ReminderPlanner.formatDate(next.toLocalDate())).append(' ')
                            .append(ReminderPlanner.formatTime(next.toLocalTime()));
                }
            }
            case UNKNOWN -> { }
        }
        return line.toString();
    }

    private static String nameOf(NotifierEvent event) {
        return event.name() == null || event.name().isBlank() ? "без названия" : event.name();
    }

    // ----- the create form: rem:f:<keep|deft|nag:<min>|back|cancel|ok> -----

    /** ➕ Новое (from the list): the list message turns into the form. */
    private void startForm(CallbackQuery callback, long chatId, Integer messageId, long docId) {
        Optional<Document> document = retrievalService.byId(docId);
        if (document.isEmpty()) {
            answer(callback, null);
            render(chatId, messageId, "Документ #" + docId + " не найден.", null);
            return;
        }
        NotifierUser user;
        try {
            Optional<NotifierUser> found = reminders.user(chatId);
            if (found.isEmpty()) {
                answer(callback, null);
                renderList(chatId, messageId, docId, null); // the list shows the «запусти @…» hint
                return;
            }
            user = found.get();
        } catch (NotifierException e) {
            answer(callback, problem(e));
            return;
        }
        answer(callback, null);
        Form form = new Form(docId, ReminderService.newExternalId(), null, -1);
        form.user = user;
        form.name = defaultName(document.get());
        showForm(chatId, messageId, form);
    }

    private void onFormCallback(CallbackQuery callback, long chatId, Integer messageId, String[] parts) {
        Form form = forms.get(chatId);
        if (form == null || messageId == null || form.messageId != messageId) {
            answer(callback, "Форма устарела — откройте ⏰ Напоминания заново.");
            return;
        }
        String action = parts.length > 2 ? parts[2] : "";
        form.notice = null;
        switch (action) {
            case "keep" -> keepCurrent(form);
            case "deft" -> { // no time: notifier uses the start of the user's working hours
                form.time = null;
                form.step = Step.NAG;
            }
            case "nag" -> {
                Long minutes = parts.length > 3 ? parseLong(parts[3]) : null;
                if (minutes != null && (minutes == 0 || minutes == NAG_HOURLY || minutes == NAG_DAILY)) {
                    form.nag = minutes == 0 ? null : minutes.intValue();
                    form.step = Step.CONFIRM;
                }
            }
            case "back" -> form.step = switch (form.step) {
                case NAME, DATE -> Step.NAME;
                case TIME -> Step.DATE;
                case NAG -> Step.TIME;
                case CONFIRM -> Step.NAG;
            };
            case "cancel" -> {
                forms.remove(chatId);
                answer(callback, null);
                render(chatId, messageId, "Отменено.", null);
                return;
            }
            case "ok" -> {
                createFromForm(callback, chatId, form);
                return;
            }
            default -> { }
        }
        answer(callback, null);
        showForm(chatId, messageId, form);
    }

    /** «✅ Оставить»: accept the current value of the step (re-validated — a prefilled date may have passed). */
    private void keepCurrent(Form form) {
        switch (form.step) {
            case NAME -> {
                if (form.name != null && !form.name.isBlank()) {
                    form.step = Step.DATE;
                }
            }
            case DATE -> {
                if (form.date != null) {
                    String problem = ReminderPlanner.dateProblem(form.date, reminders.today(form.user));
                    if (problem != null) {
                        form.notice = problem + " Введите другую дату.";
                    } else {
                        form.step = Step.TIME;
                    }
                }
            }
            case TIME -> {
                if (form.time != null && ReminderPlanner.isPast(form.date, form.time, reminders.now(form.user))) {
                    form.notice = "Это время уже прошло — укажите другое.";
                } else {
                    form.step = Step.NAG;
                }
            }
            case NAG, CONFIRM -> { }
        }
    }

    /** Apply a typed value for the current step; an invalid one keeps the step with a notice. */
    private void applyInput(Form form, String text) {
        form.notice = null;
        switch (form.step) {
            case NAME -> {
                if (text.length() > ReminderPlanner.MAX_NAME_LENGTH) {
                    form.notice = "Слишком длинное название (до " + ReminderPlanner.MAX_NAME_LENGTH + " символов).";
                } else {
                    form.name = ReminderPlanner.formatName(text);
                    form.step = Step.DATE;
                }
            }
            case DATE -> {
                LocalDate today = reminders.today(form.user);
                Optional<LocalDate> date = ReminderPlanner.parseUserDate(text);
                String problem = date.map(d -> ReminderPlanner.dateProblem(d, today)).orElse(null);
                if (date.isEmpty()) {
                    form.notice = "Не понял дату. Формат ДД.ММ.ГГГГ, например "
                            + ReminderPlanner.formatDate(today.plusDays(7)) + ".";
                } else if (problem != null) {
                    form.notice = problem;
                } else {
                    form.date = date.get();
                    form.step = Step.TIME;
                }
            }
            case TIME -> {
                Optional<LocalTime> time = ReminderPlanner.parseUserTime(text);
                if (time.isEmpty()) {
                    form.notice = "Не понял время. Формат ЧЧ:ММ, например 10:30.";
                } else if (ReminderPlanner.isPast(form.date, time.get(), reminders.now(form.user))) {
                    form.notice = "Это время уже прошло — укажите другое.";
                } else {
                    form.time = time.get();
                    form.step = Step.NAG;
                }
            }
            case NAG, CONFIRM -> { }
        }
    }

    private void createFromForm(CallbackQuery callback, long chatId, Form form) {
        if (form.step != Step.CONFIRM) {
            answer(callback, null);
            return;
        }
        ReminderDraft draft = new ReminderDraft(form.externalId, form.name, form.date, form.time, form.nag);
        try {
            NotifierClient.CreateResult result = reminders.create(chatId, form.docId, draft);
            forms.remove(chatId);
            answer(callback, "Напоминание создано");
            // A 200 repeat (same externalId, e.g. the card's ✅ was tapped first) returns the event as it was
            // first created — report what actually exists in notifier.
            NotifierEvent event = result.event();
            markSuggestionCreated(chatId, form, event != null ? eventSummary(event) : formSummaryLine(form));
            String createdName = event != null && event.name() != null ? event.name() : form.name;
            // The form turns into the document's reminder list, with the new one in it.
            renderList(chatId, form.messageId, form.docId, "✅ Создано: «" + createdName + "»");
            return;
        } catch (NotifierApiException e) {
            switch (e.code()) {
                case FIRE_TIME_IN_PAST -> {
                    form.step = Step.DATE;
                    form.notice = "Это время уже прошло — выберите другую дату или время.";
                }
                case USER_NOT_REGISTERED -> form.notice = reminders.connectHint() + ", затем снова «✅ Создать».";
                case VALIDATION_FAILED -> form.notice = problem(e);
                default -> {
                    answer(callback, problem(e));
                    return;
                }
            }
            answer(callback, null);
        } catch (NotifierUnavailableException e) {
            form.notice = "Сервис напоминаний не ответил. Нажмите «✅ Создать» ещё раз — дубля не будет.";
            answer(callback, "Сервис напоминаний не ответил — нажмите ещё раз.");
        }
        showForm(chatId, form.messageId, form);
    }

    /** A form opened from a card suggestion (✏️) was created: mark that suggestion done on the card. */
    private void markSuggestionCreated(long chatId, Form form, String createdLine) {
        if (form.originCard == null) {
            return;
        }
        Card card = cards.get(form.originCard);
        if (card == null || form.originIndex < 0 || form.originIndex >= card.items.size()) {
            return;
        }
        Suggestion suggestion = card.items.get(form.originIndex);
        suggestion.state = SuggestionState.CREATED;
        suggestion.createdLine = createdLine;
        refreshCard(chatId, form.originCard, card);
    }

    /** A created event for the card line: «10.03.2027 10:00 — name · 🔁 каждый день» (local time from notifier). */
    private static String eventSummary(NotifierEvent event) {
        StringBuilder line = new StringBuilder();
        if (event.fireAtLocal() != null) {
            line.append(ReminderPlanner.formatDate(event.fireAtLocal().toLocalDate())).append(' ')
                    .append(ReminderPlanner.formatTime(event.fireAtLocal().toLocalTime())).append(" — ");
        }
        line.append(nameOf(event));
        Integer nag = event.nagIntervalMinutes();
        if (nag != null && nag > 0) {
            line.append(" · 🔁 ").append(nagText(nag));
        }
        return line.toString();
    }

    /** Send or edit the form message, and (re)register the form under the id it now lives in. */
    private void showForm(long chatId, Integer messageId, Form form) {
        Integer shown = render(chatId, messageId, formText(form), formKeyboard(form));
        if (shown == null) {
            return; // couldn't send — nothing to wait on
        }
        form.messageId = shown;
        purgeStale();
        forms.put(chatId, form);
    }

    private String formText(Form form) {
        StringBuilder text = new StringBuilder("⏰ Новое напоминание · документ #").append(form.docId);
        if (form.step.compareTo(Step.NAME) > 0) {
            text.append("\nНазвание: ").append(form.name);
        }
        if (form.step.compareTo(Step.DATE) > 0) {
            text.append("\nДата: ").append(ReminderPlanner.formatDate(form.date));
        }
        if (form.step.compareTo(Step.TIME) > 0) {
            text.append("\nВремя: ").append(timeText(form));
        }
        if (form.step.compareTo(Step.NAG) > 0) {
            text.append("\nПовтор: ").append(nagText(form.nag));
        }
        text.append("\n\n");
        if (form.notice != null) {
            text.append("⚠️ ").append(form.notice).append('\n');
        }
        switch (form.step) {
            case NAME -> text.append("Шаг 1/4 · Название")
                    .append(form.name == null || form.name.isBlank() ? ":" : ": «" + form.name + "»")
                    .append("\nОтправьте новое название")
                    .append(form.name == null || form.name.isBlank() ? "" : " или оставьте это")
                    .append(" (или /cancel).");
            case DATE -> text.append("Шаг 2/4 · Дата напоминания (ДД.ММ.ГГГГ)")
                    .append(form.date == null ? ":\nОтправьте дату (или /cancel)."
                            : ": " + ReminderPlanner.formatDate(form.date) + "\nОтправьте другую дату или оставьте эту (или /cancel).");
            case TIME -> text.append("Шаг 3/4 · Время (ЧЧ:ММ) — необязательно")
                    .append(form.time == null ? "" : ": " + ReminderPlanner.formatTime(form.time))
                    .append("\nОтправьте время или выберите «без времени» — тогда ").append(defaultTimeText(form))
                    .append(" (или /cancel).");
            case NAG -> text.append("Шаг 4/4 · Повторять, пока не нажмёте «Готово» в notifier?");
            case CONFIRM -> text.append("Создать напоминание?");
        }
        return text.toString();
    }

    private InlineKeyboardMarkup formKeyboard(Form form) {
        var keyboard = InlineKeyboardMarkup.builder();
        switch (form.step) {
            case NAME -> {
                if (form.name != null && !form.name.isBlank()) {
                    keyboard.keyboardRow(row(button("✅ Оставить", "rem:f:keep")));
                }
                keyboard.keyboardRow(row(button("✖ Отмена", "rem:f:cancel")));
                return keyboard.build();
            }
            case DATE -> {
                if (form.date != null) {
                    keyboard.keyboardRow(row(button("✅ Оставить " + ReminderPlanner.formatDate(form.date), "rem:f:keep")));
                }
            }
            case TIME -> {
                keyboard.keyboardRow(row(button("⏭ Без времени" + (form.user != null && form.user.workStart() != null
                        ? " (" + ReminderPlanner.formatTime(form.user.workStart()) + ")" : ""), "rem:f:deft")));
                if (form.time != null) {
                    keyboard.keyboardRow(row(button("✅ Оставить " + ReminderPlanner.formatTime(form.time), "rem:f:keep")));
                }
            }
            case NAG -> keyboard.keyboardRow(row(
                    button(mark(form.nag == null) + "Нет", "rem:f:nag:0"),
                    button(mark(Integer.valueOf(NAG_HOURLY).equals(form.nag)) + "Каждый час", "rem:f:nag:" + NAG_HOURLY),
                    button(mark(Integer.valueOf(NAG_DAILY).equals(form.nag)) + "Каждый день", "rem:f:nag:" + NAG_DAILY)));
            case CONFIRM -> keyboard.keyboardRow(row(button("✅ Создать", "rem:f:ok")));
        }
        keyboard.keyboardRow(row(button("⬅ Назад", "rem:f:back"), button("✖ Отмена", "rem:f:cancel")));
        return keyboard.build();
    }

    private static String mark(boolean current) {
        return current ? "• " : "";
    }

    private String timeText(Form form) {
        return form.time != null ? ReminderPlanner.formatTime(form.time) : "без времени — " + defaultTimeText(form);
    }

    private static String defaultTimeText(Form form) {
        return form.user != null && form.user.workStart() != null
                ? "в " + ReminderPlanner.formatTime(form.user.workStart()) + ", начало вашего дня в notifier"
                : "в начале вашего рабочего дня в notifier";
    }

    /** What was created from the form, for the card line: «10.03.2027 10:30 — name · 🔁 каждый день». */
    private static String formSummaryLine(Form form) {
        String line = ReminderPlanner.formatDate(form.date)
                + (form.time == null ? "" : " " + ReminderPlanner.formatTime(form.time)) + " — " + form.name;
        return form.nag == null ? line : line + " · 🔁 " + nagText(form.nag);
    }

    private static String nagText(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return "без повтора";
        }
        return switch (minutes) {
            case NAG_HOURLY -> "каждый час";
            case NAG_DAILY -> "каждый день";
            default -> "каждые " + minutes + " мин";
        };
    }

    /** Default form name: the document's title, else its type, else «Документ #id». */
    private static String defaultName(Document document) {
        if (document.getTitle() != null && !document.getTitle().isBlank()) {
            return ReminderPlanner.formatName(document.getTitle());
        }
        if (document.getDocType() != null && !document.getDocType().isBlank()) {
            return ReminderPlanner.formatName(document.getDocType());
        }
        return "Документ #" + document.getId();
    }

    private static String titleSuffix(Document document) {
        String title = document.getTitle();
        return title == null || title.isBlank() ? "" : " «" + truncate(title.strip(), 60) + "»";
    }

    // ----- shared helpers -----

    /** A user-facing explanation of a notifier failure (also used as a toast, so kept short). */
    private String problem(NotifierException e) {
        return switch (e) {
            case NotifierApiException api -> switch (api.code()) {
                case UNAUTHORIZED -> "Сервис напоминаний отклонил доступ (проверьте NOTIFIER_API_TOKEN).";
                case API_DISABLED -> "Сервис напоминаний сейчас выключен.";
                case USER_NOT_REGISTERED -> reminders.connectHint();
                case EVENT_NOT_FOUND -> "Напоминание не найдено — возможно, его уже удалили.";
                case VALIDATION_FAILED -> "Сервис напоминаний отклонил данные"
                        + (api.detail() == null || api.detail().isBlank() ? "." : ": " + api.detail());
                case FIRE_TIME_IN_PAST -> "Время напоминания уже прошло.";
                case UNKNOWN -> "Сервис напоминаний вернул ошибку (HTTP " + api.httpStatus() + ").";
            };
            case NotifierUnavailableException unavailable -> "Сервис напоминаний не ответил. Попробуйте ещё раз чуть позже.";
        };
    }

    /** Edit the dialog message in place (callback context) or send a new one; returns the message id. */
    private Integer render(long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId != null) {
            sender.edit(chatId, messageId, text, keyboard);
            return messageId;
        }
        return sender.send(chatId, text, keyboard);
    }

    private void answer(CallbackQuery callback, String text) {
        sender.answerCallback(callback.getId(), text == null ? null : truncate(text, MAX_TOAST));
    }

    private void purgeStale() {
        Instant cutoff = Instant.now().minus(PENDING_TTL);
        forms.values().removeIf(form -> form.createdAt.isBefore(cutoff));
        cards.values().removeIf(card -> card.createdAt.isBefore(cutoff));
        lists.values().removeIf(view -> view.createdAt().isBefore(cutoff));
    }

    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardRow> rows) {
        var keyboard = InlineKeyboardMarkup.builder();
        rows.forEach(keyboard::keyboardRow);
        return keyboard.build();
    }

    private static InlineKeyboardRow closeRow() {
        return row(button("✖ Закрыть", "rem:close"));
    }

    private static InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private static boolean fits(String callbackData) {
        return callbackData.getBytes(StandardCharsets.UTF_8).length <= MAX_CALLBACK_BYTES;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
