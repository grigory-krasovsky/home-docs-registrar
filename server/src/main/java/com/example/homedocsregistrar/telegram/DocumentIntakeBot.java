package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.access.AccessService;
import com.example.homedocsregistrar.domain.AllowedUser;
import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.extraction.ApiUsageTracker;
import com.example.homedocsregistrar.extraction.DocumentExtractionService;
import com.example.homedocsregistrar.extraction.ExtractedFields;
import com.example.homedocsregistrar.extraction.Extraction;
import com.example.homedocsregistrar.extraction.UsageEstimator;
import com.example.homedocsregistrar.intake.DocumentIntakeService;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IntakeResult;
import com.example.homedocsregistrar.retrieval.DocumentRetrievalService;
import com.example.homedocsregistrar.search.DocumentSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Receives Telegram updates (long polling) and drives document intake: a document sent as a file is
 * downloaded, read by the vision model (fields + full text), archived to the private channel and
 * saved. While the archive channel id is unknown, channel posts are logged so the operator can
 * capture {@code ARCHIVE_CHANNEL_ID}.
 */
@Component
public class DocumentIntakeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeBot.class);

    private final TelegramSender sender;
    private final TelegramFileService fileService;
    private final DocumentExtractionService extractionService;
    private final DocumentIntakeService intakeService;
    private final DocumentRetrievalService retrievalService;
    private final DocumentSearchService searchService;
    private final ApiUsageTracker usageTracker;
    private final UsageEstimator usageEstimator;
    private final AccessService accessService;
    private final TelegramProperties telegram;

    public DocumentIntakeBot(TelegramSender sender, TelegramFileService fileService,
                             DocumentExtractionService extractionService, DocumentIntakeService intakeService,
                             DocumentRetrievalService retrievalService, DocumentSearchService searchService,
                             ApiUsageTracker usageTracker, UsageEstimator usageEstimator,
                             AccessService accessService, TelegramProperties telegram) {
        this.sender = sender;
        this.fileService = fileService;
        this.extractionService = extractionService;
        this.intakeService = intakeService;
        this.retrievalService = retrievalService;
        this.searchService = searchService;
        this.usageTracker = usageTracker;
        this.usageEstimator = usageEstimator;
        this.accessService = accessService;
        this.telegram = telegram;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
                return;
            }
            if (update.hasChannelPost()) {
                logChannelIdForSetup(update.getChannelPost());
                return;
            }
            if (!update.hasMessage()) {
                return;
            }
            Message message = update.getMessage();
            long chatId = message.getChatId();
            User from = message.getFrom();
            Long fromId = userId(from);

            // Access-management commands are answered BEFORE the allow-list check, so a new user can
            // bootstrap (/claim) or request access (/register), and anyone can look up their id.
            if (message.hasText()) {
                String command = message.getText().strip().toLowerCase(Locale.ROOT);
                if (command.startsWith("/whoami")) {
                    sender.send(chatId, "Ваш Telegram ID: " + fromId);
                    return;
                }
                if (command.startsWith("/claim")) {
                    handleClaim(chatId, fromId, displayName(from));
                    return;
                }
                if (command.startsWith("/register")) {
                    handleRegister(chatId, fromId, displayName(from));
                    return;
                }
            }
            if (!accessService.isAllowed(fromId)) {
                sender.send(chatId, "Доступ к этому боту закрыт. Нажмите /register, чтобы запросить доступ.");
                return;
            }

            if (message.hasDocument()) {
                handleDocument(chatId, message.getDocument().getFileId(), message.getDocument().getFileName());
            } else if (message.hasPhoto()) {
                sender.send(chatId, "Пришлите документ как ФАЙЛ (вложение), а не как фото — "
                        + "иначе Telegram сожмёт изображение и пострадает распознавание.");
            } else if (message.hasText()) {
                handleText(chatId, message.getText());
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    private void handleCallbackQuery(CallbackQuery callback) {
        String data = callback.getData();
        if (data != null && (data.startsWith("approve:") || data.startsWith("reject:"))) {
            handleApproval(callback);
            return;
        }
        if (!accessService.isAllowed(userId(callback.getFrom()))) {
            sender.answerCallback(callback.getId(), "Доступ закрыт.");
            return;
        }
        handleCallback(callback);
    }

    /** /claim: the first user to run it becomes the admin; it does nothing once an admin exists. */
    private void handleClaim(long chatId, Long userId, String displayName) {
        if (accessService.claim(userId, displayName)) {
            sender.send(chatId, "Готово — вы владелец бота. Теперь можно одобрять запросы доступа.");
        } else {
            sender.send(chatId, "Владелец уже назначен.");
        }
    }

    /** /register: notify every admin with approve/reject buttons so they can grant access in one tap. */
    private void handleRegister(long chatId, Long userId, String displayName) {
        if (userId == null) {
            return;
        }
        if (accessService.isAllowed(userId)) {
            sender.send(chatId, "У вас уже есть доступ.");
            return;
        }
        List<AllowedUser> admins = accessService.admins();
        if (admins.isEmpty()) {
            sender.send(chatId, "Владелец бота ещё не настроен. Попробуйте позже.");
            return;
        }
        var keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ Разрешить").callbackData("approve:" + userId).build(),
                        InlineKeyboardButton.builder().text("❌ Отклонить").callbackData("reject:" + userId).build()))
                .build();
        String request = "Запрос доступа: " + displayName + " (id=" + userId + ")";
        for (AllowedUser admin : admins) {
            sender.send(admin.getTelegramUserId(), request, keyboard);
        }
        sender.send(chatId, "Запрос отправлен владельцу. Ожидайте подтверждения.");
    }

    /** An admin tapped ✅/❌ on an access request: grant or decline, then notify both sides. */
    private void handleApproval(CallbackQuery callback) {
        Long adminId = userId(callback.getFrom());
        if (!accessService.isAdmin(adminId)) {
            sender.answerCallback(callback.getId(), "Только владелец может одобрять доступ.");
            return;
        }
        String data = callback.getData();
        boolean approve = data.startsWith("approve:");
        Long requesterId = parseLong(data.substring(data.indexOf(':') + 1));
        if (requesterId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        if (approve) {
            boolean added = accessService.approve(requesterId, null);
            sender.answerCallback(callback.getId(), added ? "Доступ выдан." : "Пользователь уже имел доступ.");
            if (added) {
                sender.send(requesterId, "Доступ открыт ✅ Пришлите документ как файл или введите запрос для поиска.");
            }
        } else {
            sender.answerCallback(callback.getId(), "Запрос отклонён.");
            sender.send(requesterId, "В доступе отказано.");
        }
    }

    private static Long userId(User from) {
        return from == null ? null : from.getId();
    }

    /** Human-readable name for logs/requests: first+last name, falling back to @username or the id. */
    private static String displayName(User from) {
        if (from == null) {
            return "неизвестный";
        }
        StringBuilder name = new StringBuilder();
        if (from.getFirstName() != null) {
            name.append(from.getFirstName());
        }
        if (from.getLastName() != null && !from.getLastName().isBlank()) {
            name.append(name.isEmpty() ? "" : " ").append(from.getLastName());
        }
        if (from.getUserName() != null && !from.getUserName().isBlank()) {
            name.append(name.isEmpty() ? "" : " ").append("@").append(from.getUserName());
        }
        return name.isEmpty() ? String.valueOf(from.getId()) : name.toString();
    }

    private void handleDocument(long chatId, String fileId, String fileName) {
        sender.send(chatId, "Файл получен, распознаю…");
        try {
            byte[] bytes = fileService.download(fileId);

            // Dedupe by content hash before the (paid) vision call so re-sends cost nothing.
            Optional<Document> duplicate = intakeService.findExisting(bytes);
            if (duplicate.isPresent()) {
                sender.send(chatId, "Этот документ уже сохранён (id=" + duplicate.get().getId() + ").");
                return;
            }

            Extraction extraction = extractionService.extract(bytes).orElse(null);
            ExtractedFields fields = extraction == null ? null : extraction.fields();
            IntakeResult result = intakeService.save(fileId, fileName, bytes, fields);
            if (result.duplicate()) {
                sender.send(chatId, "Этот документ уже сохранён (id=" + result.document().getId() + ").");
                return;
            }

            String text = summary(result.document(), fields);
            if (extraction != null) {
                text += "\n\n" + tokenStatus(extraction);
            }
            sender.send(chatId, text, openFileKeyboard(result.document().getId(), "📎 Открыть файл"));
        } catch (Exception e) {
            log.error("Intake failed for document {}", fileId, e);
            sender.send(chatId, "Не удалось обработать файл. Попробуйте ещё раз.");
        }
    }

    private void handleText(long chatId, String text) {
        String trimmed = text.strip();
        String command = trimmed.toLowerCase(Locale.ROOT);
        if (command.startsWith("/start") || command.startsWith("/help")) {
            sender.send(chatId, helpMessage());
        } else if (command.startsWith("/tokens") || command.startsWith("/usage")) {
            sender.send(chatId, tokensSummary());
        } else if (command.startsWith("/get") || command.startsWith("/doc")) {
            handleGet(chatId, trimmed);
        } else if (command.startsWith("/search") || command.startsWith("/find")) {
            handleSearch(chatId, argument(trimmed));
        } else if (command.startsWith("/")) {
            sender.send(chatId, helpMessage());
        } else {
            // Plain text is treated as a search query — search is the main use case.
            handleSearch(chatId, trimmed);
        }
    }

    /** Full-text search by content; lists ranked matches, each with a one-tap button to open the file. */
    private void handleSearch(long chatId, String query) {
        if (query == null || query.isBlank()) {
            sender.send(chatId, "Введите слова для поиска, например: /search гарантия холодильник");
            return;
        }
        DocumentSearchService.SearchResult result = searchService.search(query);
        List<Document> hits = result.hits();
        if (hits.isEmpty()) {
            sender.send(chatId, "По запросу «" + query + "» ничего не найдено.");
            return;
        }
        StringBuilder text = new StringBuilder("Найдено (" + hits.size() + ") по «" + query + "»");
        if (!result.relatedTerms().isEmpty()) {
            text.append("\nИскал также: ").append(String.join(", ", result.relatedTerms()));
        }
        text.append("\nНажмите кнопку, чтобы открыть файл:");
        var keyboard = InlineKeyboardMarkup.builder();
        for (Document hit : hits) {
            text.append("\n\n").append(resultLine(hit));
            keyboard.keyboardRow(new InlineKeyboardRow(openFileButton(hit.getId(), buttonLabel(hit))));
        }
        sender.send(chatId, text.toString(), keyboard.build());
    }

    /** One search hit: id + the fields we have (the file is opened via the row's button). */
    private String resultLine(Document document) {
        StringBuilder line = new StringBuilder("#").append(document.getId());
        if (document.getDocType() != null && !document.getDocType().isBlank()) {
            line.append(" · ").append(document.getDocType());
        }
        if (document.getTitle() != null && !document.getTitle().isBlank()) {
            line.append(" · ").append(document.getTitle());
        }
        String meta = joinNonBlank(
                document.getCounterparty(),
                document.getDocDate() == null ? null : document.getDocDate().toString(),
                document.getAmount() == null ? null : document.getAmount().toPlainString());
        if (!meta.isBlank()) {
            line.append('\n').append(meta);
        }
        return line.toString();
    }

    private static String joinNonBlank(String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (joined.length() > 0) {
                    joined.append(" · ");
                }
                joined.append(value);
            }
        }
        return joined.toString();
    }

    private String helpMessage() {
        return "Пришлите документ как файл (вложение) — я распознаю и сохраню его.\n"
                + "Поиск: просто напишите слова (или /search <запрос>).\n"
                + "• /get <id> — прислать сохранённый файл документа\n"
                + "• /tokens — сколько токенов израсходовано на распознавание";
    }

    /** Text after the leading command word (e.g. "/search гарантия" -> "гарантия"); "" if none. */
    private static String argument(String text) {
        int space = text.indexOf(' ');
        return space < 0 ? "" : text.substring(space + 1).strip();
    }

    /** Re-send a stored document's file from Telegram by its registry id (resolved to its file_id). */
    private void handleGet(long chatId, String text) {
        Long id = parseDocId(text);
        if (id == null) {
            sender.send(chatId, "Укажите номер документа, например: /get 42");
            return;
        }
        String error = sendDocumentFile(chatId, id);
        if (error != null) {
            sender.send(chatId, error);
        }
    }

    /** A tapped "open file" button (callback data get:&lt;id&gt;): send the file and clear the spinner. */
    private void handleCallback(CallbackQuery callback) {
        Long id = callback.getData() != null && callback.getData().startsWith("get:")
                ? parseLong(callback.getData().substring("get:".length()))
                : null;
        Long chatId = callback.getMessage() == null ? null : callback.getMessage().getChatId();
        if (id == null || chatId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        String error = sendDocumentFile(chatId, id);
        // On success answer silently (the file is the feedback); on failure show the reason as a toast.
        sender.answerCallback(callback.getId(), error);
    }

    /** Send document {@code id}'s file to the chat; returns an error message, or null on success. */
    private String sendDocumentFile(long chatId, long id) {
        Document document = retrievalService.byId(id).orElse(null);
        if (document == null) {
            return "Документ id=" + id + " не найден.";
        }
        String fileId = document.getTelegramFileId();
        if (fileId == null || fileId.isBlank()) {
            return "Для документа id=" + id + " не сохранён файл.";
        }
        Integer sent = sender.sendDocumentByFileId(String.valueOf(chatId), fileId, retrievalCaption(document));
        return sent == null ? "Не удалось отправить файл документа id=" + id + ". Попробуйте позже." : null;
    }

    /** A one-button inline keyboard that opens document {@code id}'s file. */
    private InlineKeyboardMarkup openFileKeyboard(long id, String label) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(openFileButton(id, label)))
                .build();
    }

    private InlineKeyboardButton openFileButton(long id, String label) {
        return InlineKeyboardButton.builder().text(label).callbackData("get:" + id).build();
    }

    /** Compact button label for a search hit: id + a short name. */
    private String buttonLabel(Document document) {
        String name = document.getTitle() != null && !document.getTitle().isBlank()
                ? document.getTitle()
                : (document.getDocType() != null && !document.getDocType().isBlank() ? document.getDocType() : "документ");
        return "📎 #" + document.getId() + " · " + truncate(name, 30);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /** Parse the document id from a "/get 42" (or "/doc 42") command; null if absent or not a number. */
    private static Long parseDocId(String text) {
        String[] parts = text.trim().split("\\s+");
        return parts.length < 2 ? null : parseLong(parts[1]);
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Short caption for a re-sent file: id + type + title when known. */
    private String retrievalCaption(Document document) {
        StringBuilder caption = new StringBuilder("id=").append(document.getId());
        if (document.getDocType() != null && !document.getDocType().isBlank()) {
            caption.append(" · ").append(document.getDocType());
        }
        if (document.getTitle() != null && !document.getTitle().isBlank()) {
            caption.append(" · ").append(document.getTitle());
        }
        return caption.toString();
    }

    private String summary(Document document, ExtractedFields fields) {
        StringBuilder text = new StringBuilder("Сохранено ✅ id=").append(document.getId());
        if (fields == null) {
            text.append("\n\n(распознавание недоступно — файл сохранён)");
            return text.toString();
        }
        append(text, "Тип", document.getDocType());
        append(text, "Название", document.getTitle());
        append(text, "Контрагент", document.getCounterparty());
        LocalDate docDate = document.getDocDate();
        append(text, "Дата", docDate == null ? null : docDate.toString());
        append(text, "№", document.getDocumentNumber());
        BigDecimal amount = document.getAmount();
        append(text, "Сумма", amount == null ? null : amount.toPlainString());
        LocalDate warrantyUntil = document.getWarrantyUntil();
        append(text, "Гарантия до", warrantyUntil == null ? null : warrantyUntil.toString());
        return text.toString();
    }

    private void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append('\n').append(label).append(": ").append(value);
        }
    }

    /** Per-document token spend plus the running cumulative total, appended to a recognition reply. */
    private String tokenStatus(Extraction extraction) {
        ApiUsageTracker.Totals totals = usageTracker.currentTotals();
        StringBuilder text = new StringBuilder()
                .append("🔢 Токены — документ: ").append(fmt(extraction.totalTokens()))
                .append(" (in ").append(fmt(extraction.inputTokens()))
                .append(" / out ").append(fmt(extraction.outputTokens())).append(')')
                .append("\nВсего израсходовано: ").append(fmt(totals.total()));
        if (usageEstimator.hasPool()) {
            text.append("\nОстаток пула: ≈").append(usageEstimator.remainingPercent(totals)).append('%');
        }
        return text.toString();
    }

    /** Reply for the /tokens command: cumulative token spend and the estimated remaining pool. */
    private String tokensSummary() {
        ApiUsageTracker.Totals totals = usageTracker.currentTotals();
        StringBuilder text = new StringBuilder()
                .append("Израсходовано токенов на распознавание: ").append(fmt(totals.total()))
                .append("\n• ввод (in): ").append(fmt(totals.inputTokens()))
                .append("\n• вывод (out): ").append(fmt(totals.outputTokens()));
        if (usageEstimator.hasPool()) {
            text.append("\n\nОстаток пула: ≈").append(usageEstimator.remainingPercent(totals)).append('%')
                    .append(" (≈$").append(fmt2(usageEstimator.remainingUsd(totals))).append(')');
        }
        text.append("\n\nОстаток — оценка (точного баланса в API нет). Кредит — в консоли Anthropic (Billing).");
        return text.toString();
    }

    /** Group digits with spaces (e.g. 23 350) so big token counts stay readable. */
    private static String fmt(long tokens) {
        return String.format(Locale.ROOT, "%,d", tokens).replace(',', ' ');
    }

    /** USD with two decimals (e.g. 3.47). */
    private static String fmt2(double usd) {
        return String.format(Locale.ROOT, "%.2f", usd);
    }

    /** Until ARCHIVE_CHANNEL_ID is configured, log the id of any channel the bot sees so it can be captured. */
    private void logChannelIdForSetup(Message channelPost) {
        String configured = telegram.archiveChannelId();
        if (configured == null || configured.isBlank()) {
            log.info("Channel post seen in chat id={} — set ARCHIVE_CHANNEL_ID to this to enable archiving",
                    channelPost.getChatId());
        }
    }
}
