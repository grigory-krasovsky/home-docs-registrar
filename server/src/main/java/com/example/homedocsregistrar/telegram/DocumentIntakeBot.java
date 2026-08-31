package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.extraction.ApiUsageTracker;
import com.example.homedocsregistrar.extraction.DocumentExtractionService;
import com.example.homedocsregistrar.extraction.ExtractedFields;
import com.example.homedocsregistrar.extraction.Extraction;
import com.example.homedocsregistrar.extraction.UsageEstimator;
import com.example.homedocsregistrar.intake.DocumentIntakeService;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IntakeResult;
import com.example.homedocsregistrar.retrieval.DocumentRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final ApiUsageTracker usageTracker;
    private final UsageEstimator usageEstimator;
    private final TelegramProperties telegram;

    public DocumentIntakeBot(TelegramSender sender, TelegramFileService fileService,
                             DocumentExtractionService extractionService, DocumentIntakeService intakeService,
                             DocumentRetrievalService retrievalService, ApiUsageTracker usageTracker,
                             UsageEstimator usageEstimator, TelegramProperties telegram) {
        this.sender = sender;
        this.fileService = fileService;
        this.extractionService = extractionService;
        this.intakeService = intakeService;
        this.retrievalService = retrievalService;
        this.usageTracker = usageTracker;
        this.usageEstimator = usageEstimator;
        this.telegram = telegram;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasChannelPost()) {
                logChannelIdForSetup(update.getChannelPost());
                return;
            }
            if (!update.hasMessage()) {
                return;
            }
            Message message = update.getMessage();
            long chatId = message.getChatId();

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
            text += "\n\nФайл прислать: /get " + result.document().getId();
            if (extraction != null) {
                text += "\n\n" + tokenStatus(extraction);
            }
            sender.send(chatId, text);
        } catch (Exception e) {
            log.error("Intake failed for document {}", fileId, e);
            sender.send(chatId, "Не удалось обработать файл. Попробуйте ещё раз.");
        }
    }

    private void handleText(long chatId, String text) {
        String trimmed = text.strip();
        String command = trimmed.toLowerCase(Locale.ROOT);
        if (command.startsWith("/tokens") || command.startsWith("/usage")) {
            sender.send(chatId, tokensSummary());
        } else if (command.startsWith("/get") || command.startsWith("/doc")) {
            handleGet(chatId, trimmed);
        } else {
            sender.send(chatId, "Привет! Пришлите документ как файл (вложение), и я его распознаю.\n"
                    + "• /get <id> — прислать сохранённый файл документа\n"
                    + "• /tokens — сколько токенов израсходовано на распознавание");
        }
    }

    /** Re-send a stored document's file from Telegram by its registry id (resolved to its file_id). */
    private void handleGet(long chatId, String text) {
        Long id = parseDocId(text);
        if (id == null) {
            sender.send(chatId, "Укажите номер документа, например: /get 42");
            return;
        }
        Document document = retrievalService.byId(id).orElse(null);
        if (document == null) {
            sender.send(chatId, "Документ id=" + id + " не найден.");
            return;
        }
        String fileId = document.getTelegramFileId();
        if (fileId == null || fileId.isBlank()) {
            sender.send(chatId, "Для документа id=" + id + " не сохранён файл.");
            return;
        }
        Integer sent = sender.sendDocumentByFileId(String.valueOf(chatId), fileId, retrievalCaption(document));
        if (sent == null) {
            sender.send(chatId, "Не удалось отправить файл документа id=" + id + ". Попробуйте позже.");
        }
    }

    /** Parse the document id from a "/get 42" (or "/doc 42") command; null if absent or not a number. */
    private static Long parseDocId(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            return Long.parseLong(parts[1]);
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
