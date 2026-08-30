package com.example.homedocsregistrar.telegram;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Downloads the raw bytes of a file the bot received. Resolves the file path via {@code getFile},
 * then fetches it from the Telegram file endpoint. Subject to the Bot API ~20 MB download limit.
 */
@Service
public class TelegramFileService {

    private final TelegramClient telegramClient;
    private final String botToken;
    private final HttpClient http = HttpClient.newHttpClient();

    public TelegramFileService(TelegramClient telegramClient, TelegramProperties properties) {
        this.telegramClient = telegramClient;
        this.botToken = properties.botToken();
    }

    public byte[] download(String fileId) throws TelegramApiException, IOException, InterruptedException {
        org.telegram.telegrambots.meta.api.objects.File file =
                telegramClient.execute(GetFile.builder().fileId(fileId).build());
        URI uri = URI.create("https://api.telegram.org/file/bot" + botToken + "/" + file.getFilePath());
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Telegram file download failed with HTTP " + response.statusCode());
        }
        return response.body();
    }
}
