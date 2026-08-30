package com.example.homedocsregistrar.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
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
        try {
            Message message = telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
            return message.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", chatId, e);
            return null;
        }
    }
}
