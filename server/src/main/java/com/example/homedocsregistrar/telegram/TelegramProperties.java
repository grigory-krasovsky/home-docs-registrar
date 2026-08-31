package com.example.homedocsregistrar.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram configuration, bound from {@code telegram.*} (env {@code TELEGRAM_BOT_TOKEN} /
 * {@code ARCHIVE_CHANNEL_ID}). Both are blank when unset so the app still boots (the bot just
 * stays disabled) — see {@link TelegramBotConfig}. The user allow-list lives in the database
 * (see {@code AccessService}), not here, so people can be added/removed without a restart.
 *
 * @param botToken         the @BotFather token
 * @param archiveChannelId chat id of the private archive channel ("-100…"); kept as a String so an
 *                         empty value binds cleanly and so it can be passed straight as a chat id
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String archiveChannelId) {
}
