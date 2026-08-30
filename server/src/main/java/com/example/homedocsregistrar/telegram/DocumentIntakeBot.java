package com.example.homedocsregistrar.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Receives Telegram updates (long polling) and drives the document-intake dialog.
 *
 * <p>Skeleton: for now it only acknowledges what it received and nudges the user to send the
 * document as a file (not a compressed photo). OCR, field confirmation, section selection,
 * re-posting to the archive channel and saving the registry row are added next in step 3.
 */
@Component
public class DocumentIntakeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeBot.class);

    private final TelegramSender sender;

    public DocumentIntakeBot(TelegramSender sender) {
        this.sender = sender;
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
                // TODO (step 3): OCR -> confirm fields -> pick section -> re-post to the archive
                // channel -> save the registry row with file_id + channel_message_id.
                sender.send(chatId, "Файл получен ✅ Распознавание и сохранение появятся на следующем шаге.");
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
}
