package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.access.AccessService;
import com.example.homedocsregistrar.access.RegistrationThrottle;
import com.example.homedocsregistrar.domain.AllowedUser;
import com.example.homedocsregistrar.domain.CatalogSection;
import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.domain.DocumentPage;
import com.example.homedocsregistrar.edit.DocumentDeletionService;
import com.example.homedocsregistrar.edit.DocumentEditService;
import com.example.homedocsregistrar.extraction.ApiUsageTracker;
import com.example.homedocsregistrar.extraction.DocumentExtractionService;
import com.example.homedocsregistrar.extraction.ExtractedFields;
import com.example.homedocsregistrar.extraction.Extraction;
import com.example.homedocsregistrar.extraction.UsageEstimator;
import com.example.homedocsregistrar.intake.DocumentIntakeService;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IncomingPage;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IntakeResult;
import com.example.homedocsregistrar.retrieval.DocumentRetrievalService;
import com.example.homedocsregistrar.search.DocumentSearchService;
import com.example.homedocsregistrar.section.CatalogSectionService;
import com.example.homedocsregistrar.section.SectionSuggestionService;
import com.example.homedocsregistrar.telegram.MediaGroupCollector.BufferedPage;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives Telegram updates (long polling) and drives document intake: a document sent as a file is
 * downloaded, read by the vision model (fields + full text), archived to the private channel and
 * saved. While the archive channel id is unknown, channel posts are logged so the operator can
 * capture {@code ARCHIVE_CHANNEL_ID}.
 */
@Component
public class DocumentIntakeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeBot.class);

    /** Page size for the /manage_sections document browser. */
    private static final int DOC_PAGE_SIZE = 8;

    private final TelegramSender sender;
    private final TelegramFileService fileService;
    private final DocumentExtractionService extractionService;
    private final DocumentIntakeService intakeService;
    private final DocumentRetrievalService retrievalService;
    private final DocumentSearchService searchService;
    private final ApiUsageTracker usageTracker;
    private final UsageEstimator usageEstimator;
    private final AccessService accessService;
    private final RegistrationThrottle registrationThrottle;
    private final MediaGroupCollector mediaGroupCollector;
    private final CatalogSectionService sectionService;
    private final SectionSuggestionService suggestionService;
    private final DocumentEditService editService;
    private final DocumentDeletionService deletionService;
    private final TelegramProperties telegram;

    // Lightweight per-chat dialog state. Benign if lost on restart: the suggested path can be re-picked
    // via the buttons, and a pending name/title can be re-entered via /section or /rename.
    private final Map<Long, SectionSuggestionService.Suggestion> pendingSuggestion = new ConcurrentHashMap<>();
    private final Map<Long, PendingSectionInput> pendingSectionInput = new ConcurrentHashMap<>();
    private final Map<Long, PendingTitleInput> pendingTitleInput = new ConcurrentHashMap<>();

    public DocumentIntakeBot(TelegramSender sender, TelegramFileService fileService,
                             DocumentExtractionService extractionService, DocumentIntakeService intakeService,
                             DocumentRetrievalService retrievalService, DocumentSearchService searchService,
                             ApiUsageTracker usageTracker, UsageEstimator usageEstimator,
                             AccessService accessService, RegistrationThrottle registrationThrottle,
                             MediaGroupCollector mediaGroupCollector,
                             CatalogSectionService sectionService, SectionSuggestionService suggestionService,
                             DocumentEditService editService, DocumentDeletionService deletionService,
                             TelegramProperties telegram) {
        this.sender = sender;
        this.fileService = fileService;
        this.extractionService = extractionService;
        this.intakeService = intakeService;
        this.retrievalService = retrievalService;
        this.searchService = searchService;
        this.usageTracker = usageTracker;
        this.usageEstimator = usageEstimator;
        this.accessService = accessService;
        this.registrationThrottle = registrationThrottle;
        this.mediaGroupCollector = mediaGroupCollector;
        this.sectionService = sectionService;
        this.suggestionService = suggestionService;
        this.editService = editService;
        this.deletionService = deletionService;
        this.telegram = telegram;
    }

    /**
     * A pending typed-in section name: which document to file, the parent (null = a new top-level), and
     * the id of the prompt message to edit in place when the name arrives (null -> send a new message).
     */
    private record PendingSectionInput(long docId, Long parentId, Integer promptMessageId) {
    }

    /** A pending typed-in new title: which document to rename, and the prompt message to edit in place. */
    private record PendingTitleInput(long docId, Integer promptMessageId) {
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
            // Operate only in private chats. In a group the reply (and any sent file) goes to the whole
            // chat, while the allow-list only gates the sender — so a document could reach non-allowed
            // members. Silently ignore anything outside a one-on-one chat. See isPrivateChat.
            if (!isPrivateChat(chatId)) {
                return;
            }
            User from = message.getFrom();
            Long fromId = userId(from);

            // Access-management commands are answered BEFORE the allow-list check, so a new user can
            // request access (/register) and anyone can look up their id (/whoami).
            if (message.hasText()) {
                String command = message.getText().strip().toLowerCase(Locale.ROOT);
                if (command.startsWith("/whoami")) {
                    sender.deleteMessage(chatId, message.getMessageId());
                    sender.send(chatId, "Ваш Telegram ID: " + fromId);
                    return;
                }
                if (command.startsWith("/register")) {
                    sender.deleteMessage(chatId, message.getMessageId());
                    handleRegister(chatId, fromId, displayName(from));
                    return;
                }
            }
            if (!accessService.isAllowed(fromId)) {
                sender.send(chatId, "Доступ к этому боту закрыт. Нажмите /register, чтобы запросить доступ.");
                return;
            }

            if (message.hasDocument()) {
                String mediaGroupId = message.getMediaGroupId();
                if (mediaGroupId != null) {
                    // Part of an album (multi-page): buffer the pages and process them together.
                    BufferedPage page = new BufferedPage(chatId, message.getDocument().getFileId(),
                            message.getDocument().getFileName(), message.getMessageId());
                    boolean first = mediaGroupCollector.add(mediaGroupId, page, this::handleDocumentGroup);
                    if (first) {
                        sender.send(chatId, "Получаю страницы документа, распознаю…");
                        sender.sendChatAction(chatId, "typing");
                    }
                } else {
                    handleDocument(chatId, message.getDocument().getFileId(), message.getDocument().getFileName());
                }
            } else if (message.hasPhoto()) {
                sender.send(chatId, "Пришлите документ как ФАЙЛ (вложение), а не как фото — "
                        + "иначе Telegram сожмёт изображение и пострадает распознавание.");
            } else if (message.hasText()) {
                handleText(chatId, message.getMessageId(), message.getText());
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    private void handleCallbackQuery(CallbackQuery callback) {
        // Same private-chat restriction as for messages: a tapped button in a group would send the file
        // into that group. Every button the bot issues lives in a one-on-one chat, so ignore the rest.
        var callbackMessage = callback.getMessage();
        Long callbackChatId = callbackMessage == null ? null : callbackMessage.getChatId();
        if (callbackChatId == null || !isPrivateChat(callbackChatId)) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        String data = callback.getData();
        if (data != null && (data.startsWith("approve:") || data.startsWith("reject:"))) {
            handleApproval(callback);
            return;
        }
        if (!accessService.isAllowed(userId(callback.getFrom()))) {
            sender.answerCallback(callback.getId(), "Доступ закрыт.");
            return;
        }
        if (data != null && data.startsWith("sec:")) {
            handleSectionCallback(callback);
            return;
        }
        if (data != null && data.startsWith("brw:")) {
            handleBrowseCallback(callback);
            return;
        }
        if (data != null && data.startsWith("title:")) {
            handleTitleCallback(callback);
            return;
        }
        if (data != null && data.startsWith("del:")) {
            handleDeleteCallback(callback);
            return;
        }
        handleCallback(callback);
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
        // Anti-flood: throttle the fan-out to admins so /register can't be used to spam the owner. When
        // throttled (this user already asked recently, or the bot is under a request flood) stay silent —
        // the first request already got a confirmation, and we add nothing further to outbound traffic.
        if (!registrationThrottle.allowNotification(userId)) {
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

    /**
     * The bot operates only in private (one-on-one) chats. In Telegram a private chat's id equals the
     * user's own id and is positive, whereas groups, supergroups and channels have negative ids.
     * Restricting to private chats stops a document or search result from ever landing in a shared chat
     * where a non-allowed member could read it — the allow-list gates the sender, not the whole audience.
     */
    private static boolean isPrivateChat(long chatId) {
        return chatId > 0;
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
        sender.sendChatAction(chatId, "typing"); // keep a live indicator during download + vision call
        try {
            byte[] bytes = fileService.download(fileId);

            // Dedupe by content hash before the (paid) vision call so re-sends cost nothing.
            Optional<Document> duplicate = intakeService.findExisting(List.of(bytes));
            if (duplicate.isPresent()) {
                sender.send(chatId, "Этот документ уже сохранён (id=" + duplicate.get().getId() + ").");
                return;
            }

            Extraction extraction = extractionService.extract(bytes).orElse(null);
            ExtractedFields fields = extraction == null ? null : extraction.fields();
            if (!looksLikeDocument(fields)) {
                sender.send(chatId, notADocumentMessage());
                return;
            }
            IntakeResult result = intakeService.save(List.of(new IncomingPage(fileId, fileName, bytes)), fields);
            replySaved(chatId, result, extraction);
        } catch (Exception e) {
            log.error("Intake failed for document {}", fileId, e);
            sender.send(chatId, "Не удалось обработать файл. Попробуйте ещё раз.");
        }
    }

    /** Process a buffered album as one multi-page document: download every page, extract, save. */
    private void handleDocumentGroup(List<BufferedPage> bufferedPages) {
        if (bufferedPages.isEmpty()) {
            return;
        }
        long chatId = bufferedPages.get(0).chatId();
        try {
            List<IncomingPage> pages = new ArrayList<>();
            List<byte[]> images = new ArrayList<>();
            for (BufferedPage page : bufferedPages) {
                byte[] bytes = fileService.download(page.fileId());
                pages.add(new IncomingPage(page.fileId(), page.fileName(), bytes));
                images.add(bytes);
            }

            // Dedupe by the whole-pack hash before the (paid) vision call.
            Optional<Document> duplicate = intakeService.findExisting(images);
            if (duplicate.isPresent()) {
                sender.send(chatId, "Этот документ уже сохранён (id=" + duplicate.get().getId() + ").");
                return;
            }

            Extraction extraction = extractionService.extract(images).orElse(null);
            ExtractedFields fields = extraction == null ? null : extraction.fields();
            if (!looksLikeDocument(fields)) {
                sender.send(chatId, notADocumentMessage());
                return;
            }
            IntakeResult result = intakeService.save(pages, fields);
            replySaved(chatId, result, extraction);
        } catch (Exception e) {
            log.error("Group intake failed ({} pages)", bufferedPages.size(), e);
            sender.send(chatId, "Не удалось обработать документ. Попробуйте ещё раз.");
        }
    }

    /**
     * Accept only things that look like a document. When extraction ran, trust the model's own verdict
     * (isDocument) and require some recognized text. When extraction is off/failed (fields == null) we
     * can't judge, so we keep the file rather than drop it.
     */
    private static boolean looksLikeDocument(ExtractedFields fields) {
        if (fields == null) {
            return true;
        }
        if (Boolean.FALSE.equals(fields.isDocument())) {
            return false;
        }
        return fields.fullText() != null && !fields.fullText().isBlank();
    }

    private static String notADocumentMessage() {
        return "Это не похоже на документ — я не вижу в нём текста. "
                + "Пришлите фотографию документа: чек, договор, гарантию, свидетельство и т.п.";
    }

    /** Common reply after an intake: dedupe notice, or the saved summary + token status + open button. */
    private void replySaved(long chatId, IntakeResult result, Extraction extraction) {
        Document document = result.document();
        if (result.duplicate()) {
            sender.send(chatId, "Этот документ уже сохранён (id=" + document.getId() + ").");
            offerSectionIfUnfiled(chatId, document);
            return;
        }
        ExtractedFields fields = extraction == null ? null : extraction.fields();
        String text = summary(document, fields);
        if (extraction != null) {
            text += "\n\n" + tokenStatus(extraction);
        }
        int pageCount = document.getPageCount();
        String buttonLabel = pageCount > 1 ? "📎 Открыть (" + pageCount + " стр.)" : "📎 Открыть файл";
        var savedKeyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        openFileButton(document.getId(), buttonLabel), renameButton(document.getId())))
                .build();
        sender.send(chatId, text, savedKeyboard);
        offerSection(chatId, document);
    }

    private void handleText(long chatId, int messageId, String text) {
        String trimmed = text.strip();
        String command = trimmed.toLowerCase(Locale.ROOT);

        // A pending "type a new section name" input consumes the next plain message; a command cancels it.
        PendingSectionInput pending = pendingSectionInput.get(chatId);
        if (pending != null) {
            pendingSectionInput.remove(chatId);
            if (!command.startsWith("/")) {
                sender.deleteMessage(chatId, messageId); // the typed name disappears; the dialog message updates
                createSectionFromInput(chatId, pending, trimmed);
                return;
            }
            if (command.startsWith("/cancel")) { // cancel in place: turn the prompt into «Отменено.»
                sender.deleteMessage(chatId, messageId);
                render(chatId, pending.promptMessageId(), "Отменено.", null);
                return;
            }
            // any other command cancels the pending input silently and is handled normally below
        }

        // A pending "type a new title" input consumes the next plain message; a command cancels it.
        PendingTitleInput titleEdit = pendingTitleInput.get(chatId);
        if (titleEdit != null) {
            pendingTitleInput.remove(chatId);
            if (!command.startsWith("/")) {
                sender.deleteMessage(chatId, messageId); // the typed title disappears; the prompt updates
                applyTitle(chatId, titleEdit, trimmed);
                return;
            }
            if (command.startsWith("/cancel")) {
                sender.deleteMessage(chatId, messageId);
                render(chatId, titleEdit.promptMessageId(), "Отменено.", null);
                return;
            }
        }

        if (command.startsWith("/start") || command.startsWith("/help")) {
            sender.send(chatId, helpMessage());
        } else if (command.startsWith("/tokens") || command.startsWith("/usage")) {
            sender.send(chatId, tokensSummary());
        } else if (command.startsWith("/get") || command.startsWith("/doc")) {
            handleGet(chatId, trimmed);
        } else if (command.startsWith("/rename") || command.startsWith("/title")) {
            handleRenameCommand(chatId, trimmed);
        } else if (command.startsWith("/delete")) {
            handleDeleteCommand(chatId, trimmed);
        } else if (command.startsWith("/browse") || command.startsWith("/catalog")) {
            browseTop(chatId, null);
        } else if (command.startsWith("/manage_sections") || command.startsWith("/manage")) {
            handleManageSections(chatId);
        } else if (command.startsWith("/sections")) { // must precede /section (a prefix of /sections)
            handleSectionsList(chatId);
        } else if (command.startsWith("/section")) {
            handleSectionReassign(chatId, trimmed);
        } else if (command.startsWith("/cancel")) {
            sender.send(chatId, "Отменено.");
        } else if (command.startsWith("/search") || command.startsWith("/find")) {
            handleSearch(chatId, argument(trimmed));
        } else if (command.startsWith("/")) {
            sender.send(chatId, helpMessage());
        } else {
            // Plain text is treated as a search query — search is the main use case; keep the query message.
            handleSearch(chatId, trimmed);
            return;
        }
        // Any recognized command: remove the user's command message so only its result remains in the chat.
        sender.deleteMessage(chatId, messageId);
    }

    // ----- card-catalog section filing -----

    /** After saving, propose a subsection (or show the picker) so the user files the document. */
    private void offerSection(long chatId, Document document) {
        List<String> leafPaths = sectionService.leafPaths();
        if (leafPaths.isEmpty()) {
            return; // catalog not set up yet — nothing to file into
        }
        long docId = document.getId();
        Optional<SectionSuggestionService.Suggestion> suggestion = suggestionService.isEnabled()
                ? suggestionService.suggest(documentSummaryForSection(document), leafPaths)
                : Optional.empty();
        if (suggestion.isPresent()) {
            pendingSuggestion.put(docId, suggestion.get());
            var keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text("✅ Да").callbackData("sec:acc:" + docId).build(),
                            InlineKeyboardButton.builder().text("📁 Другая…").callbackData("sec:pick:" + docId).build()))
                    .keyboardRow(cancelRow(docId))
                    .build();
            sender.send(chatId, "В какую секцию положить? Предлагаю: 📁 " + suggestion.get().path(), keyboard);
        } else {
            sender.send(chatId, "В какую секцию положить документ #" + docId + "?", topLevelKeyboard(docId));
        }
    }

    /** For a re-sent (duplicate) document: show its section, or offer the picker if it isn't filed yet. */
    private void offerSectionIfUnfiled(long chatId, Document document) {
        Optional<String> current = sectionService.currentSectionPath(document.getId());
        if (current.isPresent()) {
            sender.send(chatId, "📁 Уже в секции: " + current.get());
            return;
        }
        if (sectionService.topLevel().isEmpty()) {
            return;
        }
        sender.send(chatId, "Документ пока без секции. Выбрать?", topLevelKeyboard(document.getId()));
    }

    /** Route a sec:* callback: {@code sec:<action>:<docId>[:<sectionId>]}. */
    private void handleSectionCallback(CallbackQuery callback) {
        String[] parts = callback.getData().split(":");
        var message = callback.getMessage();
        Long chatId = message == null ? null : message.getChatId();
        Integer messageId = message == null ? null : message.getMessageId(); // the message to edit in place
        if (chatId == null || parts.length < 3) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        if (parts[1].equals("list")) { // sec:list:<page> — the /manage_sections document browser
            sender.answerCallback(callback.getId(), null);
            Long page = parseLong(parts[2]);
            promptDocumentList(chatId, messageId, page == null ? 0 : page.intValue());
            return;
        }
        Long docId = parseLong(parts[2]);
        if (docId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        Long sectionId = parts.length > 3 ? parseLong(parts[3]) : null;
        switch (parts[1]) {
            case "acc" -> acceptSuggestion(chatId, messageId, callback, docId);
            case "pick" -> {
                sender.answerCallback(callback.getId(), null);
                promptTopLevel(chatId, messageId, docId); // edit the current message in place (list/offer -> picker)
            }
            case "top" -> {
                sender.answerCallback(callback.getId(), null);
                promptSubsections(chatId, messageId, docId, sectionId);
            }
            case "sub" -> finishAssign(chatId, messageId, callback, docId, sectionId);
            case "new" -> awaitNewSubsection(chatId, messageId, callback, docId, sectionId);
            case "newtop" -> awaitNewTopLevel(chatId, messageId, callback, docId);
            case "cancel" -> cancelSectionDialog(chatId, messageId, callback, docId);
            default -> sender.answerCallback(callback.getId(), null);
        }
    }

    /** Update the dialog message in place (callback context) or send a new one (command/entry context). */
    private void render(long chatId, Integer editMessageId, String text, InlineKeyboardMarkup keyboard) {
        if (editMessageId != null) {
            sender.edit(chatId, editMessageId, text, keyboard);
        } else {
            sender.send(chatId, text, keyboard);
        }
    }

    /** ✅ accept: resolve the suggested path (creating the subsection if new) and file the document. */
    private void acceptSuggestion(long chatId, Integer messageId, CallbackQuery callback, long docId) {
        SectionSuggestionService.Suggestion suggestion = pendingSuggestion.get(docId);
        if (suggestion == null) { // lost (e.g. restart) — fall back to the picker
            sender.answerCallback(callback.getId(), null);
            promptTopLevel(chatId, messageId, docId);
            return;
        }
        Optional<CatalogSection> leaf = sectionService.resolveSuggestion(suggestion);
        if (leaf.isEmpty()) {
            sender.answerCallback(callback.getId(), "Не удалось создать секцию.");
            return;
        }
        finishAssign(chatId, messageId, callback, docId, leaf.get().getId());
    }

    private void promptTopLevel(long chatId, Integer editMessageId, long docId) {
        if (sectionService.topLevel().isEmpty()) {
            render(chatId, editMessageId, "Секции ещё не заведены.", null);
            return;
        }
        render(chatId, editMessageId, "Выберите раздел (документ #" + docId + "):", topLevelKeyboard(docId));
    }

    private void promptSubsections(long chatId, Integer editMessageId, long docId, Long topId) {
        Optional<CatalogSection> top = sectionService.byId(topId);
        if (top.isEmpty()) {
            render(chatId, editMessageId, "Раздел не найден.", null);
            return;
        }
        render(chatId, editMessageId, "«" + top.get().getLabel() + "» — выберите подсекцию:", subKeyboard(docId, top.get()));
    }

    /** A tapped leaf (or the resolved suggestion): file the document and confirm in place. */
    private void finishAssign(long chatId, Integer editMessageId, CallbackQuery callback, long docId, Long sectionId) {
        if (sectionId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        Optional<CatalogSectionService.Assignment> assignment = sectionService.assign(docId, sectionId);
        if (assignment.isEmpty()) {
            sender.answerCallback(callback.getId(), "Документ или секция не найдены.");
            return;
        }
        pendingSuggestion.remove(docId);
        pendingSectionInput.remove(chatId);
        sender.answerCallback(callback.getId(), "Готово");
        render(chatId, editMessageId, "📁 Секция: " + assignment.get().sectionPath() + " ✅ (документ #" + docId + ")", null);
    }

    private void awaitNewSubsection(long chatId, Integer editMessageId, CallbackQuery callback, long docId, Long topId) {
        Optional<CatalogSection> top = sectionService.byId(topId);
        if (top.isEmpty()) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        pendingSectionInput.put(chatId, new PendingSectionInput(docId, topId, editMessageId));
        sender.answerCallback(callback.getId(), null);
        render(chatId, editMessageId, "Введите название новой подсекции в разделе «" + top.get().getLabel()
                + "» (или /cancel):", cancelKeyboard(docId));
    }

    private void awaitNewTopLevel(long chatId, Integer editMessageId, CallbackQuery callback, long docId) {
        pendingSectionInput.put(chatId, new PendingSectionInput(docId, null, editMessageId));
        sender.answerCallback(callback.getId(), null);
        render(chatId, editMessageId, "Введите название нового раздела (или /cancel):", cancelKeyboard(docId));
    }

    /**
     * Handle a typed-in section name: create a top-level (then ask for its first subsection, since
     * documents are filed into subsections) or a subsection (with near-duplicate merge) and file the doc.
     * Edits the prompt message in place so the dialog stays a single message.
     */
    private void createSectionFromInput(long chatId, PendingSectionInput pending, String name) {
        Integer editMessageId = pending.promptMessageId();
        if (name.isBlank()) {
            render(chatId, editMessageId, "Пустое название — отменил.", null);
            return;
        }
        if (pending.parentId() == null) {
            CatalogSection top = sectionService.getOrCreateTopLevel(name);
            pendingSectionInput.put(chatId, new PendingSectionInput(pending.docId(), top.getId(), editMessageId));
            render(chatId, editMessageId, "Раздел «" + top.getLabel()
                    + "» готов. Теперь название подсекции внутри него (или /cancel):", cancelKeyboard(pending.docId()));
            return;
        }
        Optional<CatalogSection> parent = sectionService.byId(pending.parentId());
        if (parent.isEmpty()) {
            render(chatId, editMessageId, "Раздел не найден. Повторите: /section " + pending.docId(), null);
            return;
        }
        CatalogSection sub = sectionService.getOrCreateSubsection(parent.get(), name);
        Optional<CatalogSectionService.Assignment> assignment = sectionService.assign(pending.docId(), sub.getId());
        if (assignment.isEmpty()) {
            render(chatId, editMessageId, "Документ #" + pending.docId() + " не найден.", null);
            return;
        }
        render(chatId, editMessageId, "📁 Секция: " + assignment.get().sectionPath() + " ✅ (документ #" + pending.docId() + ")", null);
    }

    /** /sections — show the whole catalog tree. */
    private void handleSectionsList(long chatId) {
        List<CatalogSection> tops = sectionService.topLevel();
        if (tops.isEmpty()) {
            sender.send(chatId, "Секции ещё не заведены.");
            return;
        }
        StringBuilder text = new StringBuilder("Секции картотеки:");
        for (CatalogSection top : tops) {
            text.append("\n\n📂 ").append(top.getLabel());
            for (CatalogSection sub : sectionService.subsections(top)) {
                text.append("\n   • ").append(sub.getLabel());
            }
        }
        sender.send(chatId, text.toString());
    }

    /** /section &lt;id&gt; — (re)assign the section of an existing document via the picker. */
    private void handleSectionReassign(long chatId, String text) {
        Long id = parseDocId(text);
        if (id == null) {
            sender.send(chatId, "Укажите номер документа, например: /section 42");
            return;
        }
        if (retrievalService.byId(id).isEmpty()) {
            sender.send(chatId, "Документ id=" + id + " не найден.");
            return;
        }
        promptTopLevel(chatId, null, id);
    }

    // ----- browse the catalog: open a section and see the documents filed in it (/browse) -----

    /** /browse — top level: pick a section (owner) or the «✱ Без секции» bucket. */
    private void browseTop(long chatId, Integer editMessageId) {
        List<CatalogSection> tops = sectionService.topLevel();
        long unfiled = sectionService.countUnfiled();
        if (tops.isEmpty() && unfiled == 0) {
            render(chatId, editMessageId, "Пока нет документов.", null);
            return;
        }
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSection top : tops) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📂 " + top.getLabel()).callbackData("brw:top:" + top.getId()).build()));
        }
        if (unfiled > 0) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("✱ Без секции (" + unfiled + ")").callbackData("brw:none:0").build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("✖ Закрыть").callbackData("brw:close").build()));
        render(chatId, editMessageId, "📁 Картотека — выберите раздел:", keyboard.build());
    }

    /** Subsections of a top-level section, each with its document count. */
    private void browseSubsections(long chatId, Integer editMessageId, Long topId) {
        Optional<CatalogSection> top = sectionService.byId(topId);
        if (top.isEmpty()) {
            render(chatId, editMessageId, "Раздел не найден.", null);
            return;
        }
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSectionService.SectionCount sub : sectionService.subsectionCounts(topId)) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📄 " + sub.label() + " (" + sub.count() + ")")
                    .callbackData("brw:sub:" + topId + ":" + sub.id() + ":0").build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("⬅ Назад").callbackData("brw:home").build()));
        render(chatId, editMessageId, "📂 " + top.get().getLabel() + " — выберите подсекцию:", keyboard.build());
    }

    /** Documents filed in a leaf subsection, paged; each button opens the file (get:&lt;id&gt;). */
    private void browseDocuments(long chatId, Integer editMessageId, Long topId, Long subId, int page) {
        if (subId == null) {
            render(chatId, editMessageId, "Секция не найдена.", null);
            return;
        }
        String path = sectionService.sectionPath(subId).orElse("секция");
        CatalogSectionService.DocPage docPage = sectionService.documentsInSection(subId, page, DOC_PAGE_SIZE);
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSectionService.DocDigest doc : docPage.items()) {
            // One full-width row per doc → its card (download + rename), so the name shows in full.
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📄 #" + doc.id() + " · " + truncate(doc.title(), 40))
                    .callbackData("brw:doc:" + topId + ":" + subId + ":" + page + ":" + doc.id()).build()));
        }
        if (docPage.hasNext()) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("⬇ Ещё").callbackData("brw:sub:" + topId + ":" + subId + ":" + (page + 1)).build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("⬅ Назад").callbackData("brw:top:" + topId).build()));
        String text = docPage.items().isEmpty()
                ? "📄 " + path + " — пусто."
                : "📄 " + path + " — выберите документ:";
        render(chatId, editMessageId, text, keyboard.build());
    }

    /** The «✱ Без секции» bucket: documents not filed anywhere yet, paged. */
    private void browseUnfiled(long chatId, Integer editMessageId, int page) {
        CatalogSectionService.DocPage docPage = sectionService.unfiledDocuments(page, DOC_PAGE_SIZE);
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSectionService.DocDigest doc : docPage.items()) {
            // One full-width row per doc → its card; topId/subId = 0 marks the «без секции» origin.
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📄 #" + doc.id() + " · " + truncate(doc.title(), 40))
                    .callbackData("brw:doc:0:0:" + page + ":" + doc.id()).build()));
        }
        if (docPage.hasNext()) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("⬇ Ещё").callbackData("brw:none:" + (page + 1)).build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("⬅ Назад").callbackData("brw:home").build()));
        String text = docPage.items().isEmpty() ? "Без секции — пусто." : "✱ Без секции — выберите документ:";
        render(chatId, editMessageId, text, keyboard.build());
    }

    /** The document «card»: download the file or rename it; «Назад» returns to the list it came from. */
    private void browseDocDetail(long chatId, Integer editMessageId, Long topId, Long subId, int page, Long docId) {
        if (docId == null) {
            render(chatId, editMessageId, "Документ не найден.", null);
            return;
        }
        Optional<Document> document = retrievalService.byId(docId);
        if (document.isEmpty()) {
            render(chatId, editMessageId, "Документ #" + docId + " не найден.", null);
            return;
        }
        String title = document.get().getTitle();
        String shown = title == null || title.isBlank() ? "без названия" : title;
        // subId == 0 marks the «✱ Без секции» origin; otherwise return to that subsection's page.
        String back = subId != null && subId > 0
                ? "brw:sub:" + topId + ":" + subId + ":" + page
                : "brw:none:" + page;
        var keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(openFileButton(docId, "📎 Скачать файл")))
                .keyboardRow(new InlineKeyboardRow(renameButton(docId)))
                .keyboardRow(new InlineKeyboardRow(deleteButton(docId)))
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("⬅ Назад").callbackData(back).build()));
        render(chatId, editMessageId, "📄 Документ #" + docId + "\nНазвание: " + shown, keyboard.build());
    }

    /** Route a brw:* callback: home | top | sub | doc:&lt;topId&gt;:&lt;subId&gt;:&lt;page&gt;:&lt;docId&gt; | none | close. */
    private void handleBrowseCallback(CallbackQuery callback) {
        String[] parts = callback.getData().split(":");
        var message = callback.getMessage();
        Long chatId = message == null ? null : message.getChatId();
        Integer messageId = message == null ? null : message.getMessageId();
        if (chatId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        sender.answerCallback(callback.getId(), null);
        switch (parts[1]) {
            case "home" -> browseTop(chatId, messageId);
            case "top" -> browseSubsections(chatId, messageId, parts.length > 2 ? parseLong(parts[2]) : null);
            case "sub" -> browseDocuments(chatId, messageId,
                    parts.length > 2 ? parseLong(parts[2]) : null,
                    parts.length > 3 ? parseLong(parts[3]) : null,
                    parsePage(parts, 4));
            case "doc" -> browseDocDetail(chatId, messageId,
                    parts.length > 2 ? parseLong(parts[2]) : null,
                    parts.length > 3 ? parseLong(parts[3]) : null,
                    parsePage(parts, 4),
                    parts.length > 5 ? parseLong(parts[5]) : null);
            case "none" -> browseUnfiled(chatId, messageId, parsePage(parts, 2));
            case "close" -> render(chatId, messageId, "Закрыто.", null);
            default -> { }
        }
    }

    /** Parse a page number from callback parts at {@code idx}; 0 if absent/unparseable. */
    private static int parsePage(String[] parts, int idx) {
        if (parts.length <= idx) {
            return 0;
        }
        Long value = parseLong(parts[idx]);
        return value == null ? 0 : value.intValue();
    }

    // ----- edit a document's title (✏️ button after save / in /browse, or /rename <id>) -----

    /** A tapped ✏️: begin a title edit (title:&lt;docId&gt;) or cancel it (title:cancel:&lt;docId&gt;). */
    private void handleTitleCallback(CallbackQuery callback) {
        String[] parts = callback.getData().split(":");
        var message = callback.getMessage();
        Long chatId = message == null ? null : message.getChatId();
        Integer messageId = message == null ? null : message.getMessageId();
        if (chatId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        if (parts.length > 2 && parts[1].equals("cancel")) { // title:cancel:<docId>
            pendingTitleInput.remove(chatId);
            sender.answerCallback(callback.getId(), "Отменено");
            render(chatId, messageId, "Отменено.", null);
            return;
        }
        Long docId = parts.length > 1 ? parseLong(parts[1]) : null;
        if (docId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        startTitleEdit(chatId, callback, docId);
    }

    /** /rename &lt;id&gt; (alias /title): begin a title edit for a document by id. */
    private void handleRenameCommand(long chatId, String text) {
        Long id = parseDocId(text);
        if (id == null) {
            sender.send(chatId, "Укажите номер документа, например: /rename 42");
            return;
        }
        if (retrievalService.byId(id).isEmpty()) {
            sender.send(chatId, "Документ id=" + id + " не найден.");
            return;
        }
        startTitleEdit(chatId, null, id);
    }

    /**
     * Prompt for a new title as a FRESH message (so the save summary / browse list stays), and remember
     * its id so the typed title edits that message in place. Clears any pending section-name input.
     */
    private void startTitleEdit(long chatId, CallbackQuery callback, long docId) {
        Optional<Document> document = retrievalService.byId(docId);
        if (document.isEmpty()) {
            if (callback != null) {
                sender.answerCallback(callback.getId(), "Документ не найден.");
            } else {
                sender.send(chatId, "Документ #" + docId + " не найден.");
            }
            return;
        }
        if (callback != null) {
            sender.answerCallback(callback.getId(), null);
        }
        pendingSectionInput.remove(chatId);
        Integer promptId = sender.send(chatId, titlePrompt(document.get()), cancelTitleKeyboard(docId));
        pendingTitleInput.put(chatId, new PendingTitleInput(docId, promptId));
    }

    /** Apply a typed-in title and confirm in place (editing the prompt message). */
    private void applyTitle(long chatId, PendingTitleInput pending, String title) {
        Integer editMessageId = pending.promptMessageId();
        if (title.isBlank()) {
            render(chatId, editMessageId, "Пустое название — отменил.", null);
            return;
        }
        Optional<Document> updated = editService.renameTitle(pending.docId(), title);
        if (updated.isEmpty()) {
            render(chatId, editMessageId, "Документ #" + pending.docId() + " не найден.", null);
            return;
        }
        render(chatId, editMessageId, "✏️ Название документа #" + pending.docId() + " → " + updated.get().getTitle(), null);
    }

    private String titlePrompt(Document document) {
        String current = document.getTitle();
        String now = current == null || current.isBlank() ? "—" : current;
        return "Название документа #" + document.getId() + ": " + now + "\nВведите новое название (или /cancel):";
    }

    private InlineKeyboardMarkup cancelTitleKeyboard(long docId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("✖ Отмена").callbackData("title:cancel:" + docId).build()))
                .build();
    }

    private InlineKeyboardButton renameButton(long docId) {
        return InlineKeyboardButton.builder().text("✏️ Название").callbackData("title:" + docId).build();
    }

    // ----- delete a document (🗑 in the card / /delete <id>); irreversible, so confirm first -----

    /** /delete &lt;id&gt;: show the delete confirmation for a document. */
    private void handleDeleteCommand(long chatId, String text) {
        Long id = parseDocId(text);
        if (id == null) {
            sender.send(chatId, "Укажите номер документа, например: /delete 42");
            return;
        }
        Optional<DocumentDeletionService.DeletionInfo> info = deletionService.info(id, Instant.now());
        if (info.isEmpty()) {
            sender.send(chatId, "Документ id=" + id + " не найден.");
            return;
        }
        sender.send(chatId, deleteConfirmText(info.get()), deleteConfirmKeyboard(id));
    }

    /** Route a del:* callback: ask:&lt;id&gt; (confirm) | yes:&lt;id&gt; (delete) | no:&lt;id&gt; (cancel). */
    private void handleDeleteCallback(CallbackQuery callback) {
        String[] parts = callback.getData().split(":");
        var message = callback.getMessage();
        Long chatId = message == null ? null : message.getChatId();
        Integer messageId = message == null ? null : message.getMessageId();
        Long docId = parts.length > 2 ? parseLong(parts[2]) : null;
        if (chatId == null || docId == null) {
            sender.answerCallback(callback.getId(), null);
            return;
        }
        switch (parts[1]) {
            case "ask" -> {
                sender.answerCallback(callback.getId(), null);
                Optional<DocumentDeletionService.DeletionInfo> info = deletionService.info(docId, Instant.now());
                if (info.isEmpty()) {
                    render(chatId, messageId, "Документ #" + docId + " не найден.", null);
                    return;
                }
                render(chatId, messageId, deleteConfirmText(info.get()), deleteConfirmKeyboard(docId));
            }
            case "yes" -> performDelete(chatId, messageId, callback, docId);
            case "no" -> {
                sender.answerCallback(callback.getId(), null);
                render(chatId, messageId, "Удаление отменено.", catalogHomeKeyboard());
            }
            default -> sender.answerCallback(callback.getId(), null);
        }
    }

    /** Delete the archived channel file(s) if still deletable (&lt;48h), then the registry row. */
    private void performDelete(long chatId, Integer messageId, CallbackQuery callback, long docId) {
        DocumentDeletionService.DeletionInfo info = deletionService.info(docId, Instant.now()).orElse(null);
        if (info == null) {
            sender.answerCallback(callback.getId(), "Документ не найден.");
            render(chatId, messageId, "Документ #" + docId + " не найден.", null);
            return;
        }
        String channel = telegram.archiveChannelId();
        boolean channelCleaned = false;
        if (info.withinDeleteWindow() && channel != null && !channel.isBlank()) {
            for (Integer msgId : info.channelMessageIds()) {
                sender.deleteMessage(channel, msgId);
            }
            channelCleaned = !info.channelMessageIds().isEmpty();
        }
        deletionService.delete(docId);
        sender.answerCallback(callback.getId(), "Удалено");
        String note;
        if (info.channelMessageIds().isEmpty()) {
            note = "";
        } else if (channelCleaned) {
            note = "\nФайл удалён и из архивного канала.";
        } else {
            note = "\nФайл в архивном канале оставлен (старше 48ч).";
        }
        render(chatId, messageId, "🗑 Документ #" + docId + " удалён." + note, catalogHomeKeyboard());
    }

    private String deleteConfirmText(DocumentDeletionService.DeletionInfo info) {
        String title = info.title() == null || info.title().isBlank() ? "без названия" : info.title();
        StringBuilder text = new StringBuilder("🗑 Удалить документ #" + info.id() + " «" + title + "»?\n")
                .append("Запись будет удалена из картотеки");
        if (info.channelMessageIds().isEmpty()) {
            text.append('.');
        } else if (info.withinDeleteWindow()) {
            text.append(", файл — из архивного канала.");
        } else {
            text.append(" (файл в архивном канале останется — он старше 48ч).");
        }
        return text.append("\nДействие необратимо.").toString();
    }

    private InlineKeyboardMarkup deleteConfirmKeyboard(long docId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ Удалить").callbackData("del:yes:" + docId).build(),
                        InlineKeyboardButton.builder().text("✖ Отмена").callbackData("del:no:" + docId).build()))
                .build();
    }

    private InlineKeyboardButton deleteButton(long docId) {
        return InlineKeyboardButton.builder().text("🗑 Удалить").callbackData("del:ask:" + docId).build();
    }

    private InlineKeyboardMarkup catalogHomeKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("⬅ Картотека").callbackData("brw:home").build()))
                .build();
    }

    /** /manage_sections — open the document browser at the first page (a fresh message). */
    private void handleManageSections(long chatId) {
        promptDocumentList(chatId, null, 0);
    }

    /** One page of documents (newest first) as tap-to-file buttons, plus a «⬇ Ещё» pager. */
    private void promptDocumentList(long chatId, Integer editMessageId, int page) {
        CatalogSectionService.DocPage docPage = sectionService.recentDocuments(page, DOC_PAGE_SIZE);
        if (docPage.items().isEmpty()) {
            render(chatId, editMessageId, page == 0 ? "Пока нет сохранённых документов." : "Больше документов нет.", null);
            return;
        }
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSectionService.DocDigest doc : docPage.items()) {
            String mark = doc.sectionPath() == null ? "✱ без секции" : "📁 " + doc.sectionPath();
            String label = "#" + doc.id() + " · " + truncate(doc.title(), 22) + " — " + mark;
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    // Edit this list message into the picker (list disappears) — no Claude call.
                    .text(truncate(label, 60)).callbackData("sec:pick:" + doc.id()).build()));
        }
        if (docPage.hasNext()) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("⬇ Ещё").callbackData("sec:list:" + (page + 1)).build()));
        }
        keyboard.keyboardRow(cancelRow(0)); // close the browser
        render(chatId, editMessageId, "Документы (стр. " + (page + 1) + ") — выберите, чтобы задать секцию:", keyboard.build());
    }

    /** ✖ Отмена: end the whole section scenario — clear its state and close the dialog message. */
    private void cancelSectionDialog(long chatId, Integer editMessageId, CallbackQuery callback, long docId) {
        pendingSuggestion.remove(docId);
        pendingSectionInput.remove(chatId);
        sender.answerCallback(callback.getId(), "Отменено");
        render(chatId, editMessageId, "Отменено.", null);
    }

    private InlineKeyboardButton cancelButton(long docId) {
        return InlineKeyboardButton.builder().text("✖ Отмена").callbackData("sec:cancel:" + docId).build();
    }

    private InlineKeyboardRow cancelRow(long docId) {
        return new InlineKeyboardRow(cancelButton(docId));
    }

    /** A one-button «✖ Отмена» keyboard for steps that otherwise wait on typed input (new section name). */
    private InlineKeyboardMarkup cancelKeyboard(long docId) {
        return InlineKeyboardMarkup.builder().keyboardRow(cancelRow(docId)).build();
    }

    private InlineKeyboardMarkup topLevelKeyboard(long docId) {
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSection top : sectionService.topLevel()) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📂 " + top.getLabel()).callbackData("sec:top:" + docId + ":" + top.getId()).build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("➕ Новый раздел").callbackData("sec:newtop:" + docId).build()));
        keyboard.keyboardRow(cancelRow(docId));
        return keyboard.build();
    }

    private InlineKeyboardMarkup subKeyboard(long docId, CatalogSection top) {
        var keyboard = InlineKeyboardMarkup.builder();
        for (CatalogSection sub : sectionService.subsections(top)) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📄 " + sub.getLabel()).callbackData("sec:sub:" + docId + ":" + sub.getId()).build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("➕ Новая подсекция").callbackData("sec:new:" + docId + ":" + top.getId()).build()));
        keyboard.keyboardRow(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("⬅ Назад").callbackData("sec:pick:" + docId).build(),
                cancelButton(docId)));
        return keyboard.build();
    }

    /** Compact document summary fed to the section suggester (normalized fields + a slice of the text). */
    private String documentSummaryForSection(Document document) {
        StringBuilder text = new StringBuilder();
        append(text, "Тип", document.getDocType());
        append(text, "Название", document.getTitle());
        append(text, "Контрагент", document.getCounterparty());
        append(text, "№", document.getDocumentNumber());
        String body = document.getOcrText();
        if (body != null && !body.isBlank()) {
            text.append("\nТекст: ").append(truncate(body.strip(), 800));
        }
        return text.toString().strip();
    }

    /** Full-text search by content; lists ranked matches, each with a one-tap button to open the file. */
    private void handleSearch(long chatId, String query) {
        if (query == null || query.isBlank()) {
            sender.send(chatId, "Введите слова для поиска, например: /search гарантия холодильник");
            return;
        }
        sender.sendChatAction(chatId, "typing"); // «печатает…» while expansion + search run
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
        return "Пришлите документ как файл (вложение) — я распознаю, сохраню и предложу секцию.\n"
                + "Поиск: просто напишите слова.\n"
                + "• /browse — открыть секцию и посмотреть документы в ней\n"
                + "• /manage_sections — разложить документы по секциям\n"
                + "• /rename <id> — изменить название документа (или кнопкой ✏️)\n"
                + "• /delete <id> — удалить документ (или 🗑 в карточке)\n"
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

    /** Send every page of document {@code id} to the chat; returns an error message, or null on success. */
    private String sendDocumentFile(long chatId, long id) {
        Document document = retrievalService.byId(id).orElse(null);
        if (document == null) {
            return "Документ id=" + id + " не найден.";
        }
        List<DocumentPage> pages = document.getPages();
        sender.sendChatAction(chatId, "upload_document"); // «отправляет файл…» while re-sending pages
        boolean anySent = false;
        for (DocumentPage page : pages) {
            String fileId = page.getTelegramFileId();
            if (fileId == null || fileId.isBlank()) {
                continue;
            }
            Integer sent = sender.sendDocumentByFileId(
                    String.valueOf(chatId), fileId, pageCaption(document, page));
            anySent |= sent != null;
        }
        return anySent ? null : "Для документа id=" + id + " не удалось отправить файлы. Попробуйте позже.";
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

    /** Caption for a re-sent page: id + type + title, plus the page number when the document is multi-page. */
    private String pageCaption(Document document, DocumentPage page) {
        StringBuilder caption = new StringBuilder("id=").append(document.getId());
        if (document.getDocType() != null && !document.getDocType().isBlank()) {
            caption.append(" · ").append(document.getDocType());
        }
        if (document.getTitle() != null && !document.getTitle().isBlank()) {
            caption.append(" · ").append(document.getTitle());
        }
        if (document.getPageCount() > 1) {
            caption.append(" · стр. ").append(page.getPageNumber()).append('/').append(document.getPageCount());
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
