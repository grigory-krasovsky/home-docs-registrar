package com.example.homedocsregistrar.telegram;

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
 * <p>Current behaviour: a document sent as a file is downloaded and OCR'd, and the recognized text
 * is echoed back. Field confirmation, section selection, re-posting to the archive channel and
 * saving the registry row are the next parts of step 3.
 */
@Component
public class DocumentIntakeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeBot.class);
    private static final int MAX_SNIPPET = 1500;

    private final TelegramSender sender;
    private final TelegramFileService fileService;
    private final OcrService ocrService;

    public DocumentIntakeBot(TelegramSender sender, TelegramFileService fileService, OcrService ocrService) {
        this.sender = sender;
        this.fileService = fileService;
        this.ocrService = ocrService;
    }

    @Override
    public void consume(Update update) {
        try {
            if (!update.hasMessage()) {
                return;
            }
            Message message = update.getMessage();
            long chatId = message.getChatId();

            if (message.hasDocument()) {
                handleDocument(chatId, message.getDocument().getFileId());
            } else if (message.hasPhoto()) {
                sender.send(chatId, "Пришлите документ как ФАЙЛ (вложение), а не как фото — "
                        + "иначе Telegram сожмёт изображение и пострадает распознавание.");
            } else if (message.hasText()) {
                sender.send(chatId, "Привет! Пришлите документ как файл (вложение), и я его распознаю.");
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    private void handleDocument(long chatId, String fileId) {
        sender.send(chatId, "Файл получен, распознаю…");
        try {
            byte[] bytes = fileService.download(fileId);
            String text = ocrService.ocr(bytes);
            if (text.isBlank()) {
                sender.send(chatId, "Текст не распознан (возможно, это PDF или нечёткое фото). "
                        + "Пришлите чёткое фото документа файлом.");
                return;
            }
            String snippet = text.length() > MAX_SNIPPET ? text.substring(0, MAX_SNIPPET) + "…" : text;
            // TODO (step 3): confirm fields -> pick section -> re-post to the archive channel -> save.
            sender.send(chatId, "Распознанный текст:\n\n" + snippet);
        } catch (Exception e) {
            log.error("Intake failed for document {}", fileId, e);
            sender.send(chatId, "Не удалось обработать файл. Попробуйте ещё раз или пришлите фото получше.");
        }
    }
}
