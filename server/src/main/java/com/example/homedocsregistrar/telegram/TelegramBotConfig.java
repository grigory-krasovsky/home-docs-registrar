package com.example.homedocsregistrar.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.util.List;

/**
 * Wires the Telegram long-polling bot using the plain telegrambots libraries (no Spring starter),
 * mirroring the proven setup in the sibling Notifier project. If no bot token is configured the app
 * still starts normally and the bot is simply disabled, so tests and token-less runs stay green.
 */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication botsApplication() {
        return new TelegramBotsLongPollingApplication(ObjectMapper::new, this::buildHttpClient);
    }

    @Bean
    public TelegramClient telegramClient(TelegramProperties properties) {
        // OkHttpTelegramClient requires a non-null token; coerce a missing token to "" so the bean
        // still builds when no token is configured (tests / token-less runs). It is never called in
        // that case because the registrar below skips registration on a blank token.
        String token = properties.botToken() == null ? "" : properties.botToken();
        return new OkHttpTelegramClient(buildHttpClient(), token);
    }

    @Bean
    public ApplicationRunner telegramBotRegistrar(TelegramBotsLongPollingApplication botsApplication,
                                                  TelegramProperties properties, DocumentIntakeBot bot,
                                                  TelegramClient telegramClient) {
        return args -> {
            if (properties.botToken() == null || properties.botToken().isBlank()) {
                log.warn("TELEGRAM_BOT_TOKEN is not set - the Telegram bot is disabled");
                return;
            }
            try {
                botsApplication.registerBot(properties.botToken(), bot);
                log.info("Telegram bot registered, long polling started");
                registerCommandMenu(telegramClient);
            } catch (Exception e) {
                log.error("Telegram bot registration failed - the app keeps running, but the bot is DOWN. "
                        + "Check network access to api.telegram.org from this environment.", e);
            }
        };
    }

    /** Publish the bot's command menu (the "/" button in Telegram); best-effort, never fatal. */
    private void registerCommandMenu(TelegramClient telegramClient) {
        try {
            // Only argument-free, tap-useful commands go in the "/" menu — plus /ask, which has no
            // plain-text equivalent (unlike /search, where plain text already searches), so surfacing it
            // aids discovery: tapping it inserts "/ask " and the user types the question. /get, /section,
            // /rename need an id/query, and /sections is superseded by /browse — they still work when
            // typed, they're just not menu buttons.
            telegramClient.execute(SetMyCommands.builder()
                    .commands(List.of(
                            BotCommand.builder()
                                    .command("ask")
                                    .description("Задать вопрос по вашим документам")
                                    .build(),
                            BotCommand.builder()
                                    .command("browse")
                                    .description("Открыть секцию и посмотреть документы в ней")
                                    .build(),
                            BotCommand.builder()
                                    .command("manage_sections")
                                    .description("Список документов: разложить по секциям")
                                    .build(),
                            BotCommand.builder()
                                    .command("tokens")
                                    .description("Израсходовано токенов на распознавание")
                                    .build(),
                            BotCommand.builder()
                                    .command("register")
                                    .description("Запросить доступ к боту")
                                    .build()))
                    .build());
            log.info("Telegram command menu set (/ask, /browse, /manage_sections, /tokens, /register)");
        } catch (TelegramApiException e) {
            log.warn("Failed to set the Telegram command menu", e);
        }
    }

    /** Read timeout must exceed the long-polling getUpdates timeout (~50 s). */
    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(75))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }
}
