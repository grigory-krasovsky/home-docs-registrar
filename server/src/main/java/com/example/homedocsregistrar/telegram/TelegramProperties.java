package com.example.homedocsregistrar.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Telegram configuration, bound from {@code telegram.*} (env {@code TELEGRAM_BOT_TOKEN} /
 * {@code ARCHIVE_CHANNEL_ID} / {@code TELEGRAM_ALLOWED_USER_IDS}). Token/channel are blank when unset so
 * the app still boots (the bot just stays disabled) — see {@link TelegramBotConfig}.
 *
 * @param botToken         the @BotFather token
 * @param archiveChannelId chat id of the private archive channel ("-100…"); kept as a String so an
 *                         empty value binds cleanly and so it can be passed straight as a chat id
 * @param allowedUserIds   numeric Telegram user ids allowed to use the bot (comma-separated in the env);
 *                         empty means no restriction — the bot is open to everyone (with a startup warning)
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String archiveChannelId, List<Long> allowedUserIds) {
}
