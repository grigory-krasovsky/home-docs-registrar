package com.example.homedocsregistrar.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Thin wrapper over {@link TelegramClient} for outbound messages. Grows with steps 3–4. */
@Component
public class TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);

    private final TelegramClient telegramClient;

    public TelegramSender(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /** Send a plain-text message; returns the sent message id, or null when sending failed. */
    public Integer send(long chatId, String text) {
        return send(chatId, text, null);
    }

    /** Send a text message, optionally with an inline keyboard; returns the sent id, or null on failure. */
    public Integer send(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Message message = telegramClient.execute(SendMessage.builder()
                    .chatId(chatId).text(text).replyMarkup(keyboard).build());
            return message.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", chatId, e);
            return null;
        }
    }

    /**
     * Show a transient status indicator in the chat ("typing…" / "sending file…") while the bot works.
     * Telegram clears it after a few seconds or when the next message is sent. Best-effort — cosmetic,
     * so failures are ignored.
     */
    public void sendChatAction(long chatId, String action) {
        try {
            telegramClient.execute(SendChatAction.builder().chatId(chatId).action(action).build());
        } catch (TelegramApiException e) {
            log.debug("Failed to send chat action to chat {}", chatId, e);
        }
    }

    /** Acknowledge a callback query so Telegram clears the tapped button's spinner; text is an optional toast. */
    public void answerCallback(String callbackQueryId, String text) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback query {}", callbackQueryId, e);
        }
    }

    /**
     * Re-send an existing Telegram file (referenced by its {@code file_id}) into a chat/channel
     * without re-uploading. Returns the new message id, or null when sending failed.
     */
    public Integer sendDocumentByFileId(String chatId, String fileId, String caption) {
        try {
            Message message = telegramClient.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(fileId))
                    .caption(caption)
                    .build());
            return message.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send document to chat {}", chatId, e);
            return null;
        }
    }
}
