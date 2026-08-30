package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.intake.DocumentIntakeService;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IntakeResult;
import com.example.homedocsregistrar.ocr.OcrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Receives Telegram updates (long polling) and drives the document-intake dialog.
 *
 * <p>Current behaviour: a document sent as a file is downloaded, OCR'd, archived to the private
 * channel and saved to the registry. Field confirmation and section selection come next. While the
 * archive channel id is unknown, channel posts are logged so the operator can capture
 * {@code ARCHIVE_CHANNEL_ID}.
 */
@Component
public class DocumentIntakeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeBot.class);
    private static final int MAX_SNIPPET = 1500;

    private final TelegramSender sender;
    private final TelegramFileService fileService;
    private final OcrService ocrService;
    private final DocumentIntakeService intakeService;
    private final TelegramProperties telegram;

    public DocumentIntakeBot(TelegramSender sender, TelegramFileService fileService, OcrService ocrService,
                             DocumentIntakeService intakeService, TelegramProperties telegram) {
        this.sender = sender;
        this.fileService = fileService;
        this.ocrService = ocrService;
        this.intakeService = intakeService;
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
                sender.send(chatId, "Привет! Пришлите документ как файл (вложение), и я его зарегистрирую.");
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    private void handleDocument(long chatId, String fileId, String fileName) {
        sender.send(chatId, "Файл получен, обрабатываю…");
        try {
            byte[] bytes = fileService.download(fileId);
            String text = ocrService.ocr(bytes);
            IntakeResult result = intakeService.save(fileId, fileName, bytes, text);
            if (result.duplicate()) {
                sender.send(chatId, "Этот документ уже сохранён (id=" + result.document().getId() + ").");
                return;
            }
            StringBuilder reply = new StringBuilder("Сохранено ✅ id=").append(result.document().getId());
            if (text.isBlank()) {
                reply.append("\n\n(текст не распознан — возможно PDF или нечёткое фото)");
            } else {
                String snippet = text.length() > MAX_SNIPPET ? text.substring(0, MAX_SNIPPET) + "…" : text;
                reply.append("\n\nРаспознано:\n").append(snippet);
            }
            sender.send(chatId, reply.toString());
        } catch (Exception e) {
            log.error("Intake failed for document {}", fileId, e);
            sender.send(chatId, "Не удалось обработать файл. Попробуйте ещё раз.");
        }
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
