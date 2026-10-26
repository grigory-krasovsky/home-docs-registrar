package com.example.homedocsregistrar.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.example.homedocsregistrar.ai.AnthropicClients;
import com.example.homedocsregistrar.ocr.HeicConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Reads a document photo with a Claude vision model and returns structured fields (type, date,
 * parties, number, amount, warranty) plus the full transcribed text — replacing the local OCR path.
 * If no API key is configured the service is disabled and returns empty (the caller degrades gracefully).
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private static final String PROMPT = """
            Ты извлекаешь данные из фотографий ОДНОГО документа (это может быть несколько страниц одного
            документа: чек, договор, гарантия, свидетельство и т.п.). Рассматривай все изображения как один
            документ и заполни поля по схеме на основе всех страниц. Если поля нет — верни null. Даты — в
            формате ГГГГ-ММ-ДД. Сумму верни числом без валюты (например 4559.00). В fullText помести весь
            распознанный текст со всех страниц по порядку.
            Если на фото НЕ документ (человек, пейзаж, предмет, животное, случайный скриншот) — верни
            isDocument=false, НЕ описывай сцену и оставь fullText пустым (null). В fullText — только
            реальный текст, присутствующий на документе, а не твоё описание изображения.""";

    /** Appended only when reminder suggestions are asked for (the schema then has a «reminders» list). */
    private static final String REMINDERS_PROMPT = """

            Поля документа верни в document. Дополнительно заполни reminders — будущие сроки из документа,
            о которых стоит напомнить: окончание гарантии, следующее ТО или поверка, окончание договора
            или страховки, срок оплаты, окончание срока действия документа. Сегодня %s. В date — дата
            САМОГО срока, только абсолютная ГГГГ-ММ-ДД; если срок задан относительно («гарантия 12
            месяцев», «оплатить в течение 10 дней»), отсчитай его от даты документа (docDate). Бери только
            сроки, явно следующие из документа и ещё не прошедшие, не больше трёх. Если таких сроков нет
            — пустой список.""";

    private final AnthropicClient client;
    private final String model;
    private final HeicConverter heicConverter;
    private final ApiUsageTracker usageTracker;

    public DocumentExtractionService(@Value("${anthropic.api-key:}") String apiKey,
                                     @Value("${anthropic.workspace-id:}") String workspaceId,
                                     @Value("${anthropic.model:claude-haiku-4-5}") String model,
                                     HeicConverter heicConverter,
                                     ApiUsageTracker usageTracker) {
        this.model = model;
        this.heicConverter = heicConverter;
        this.usageTracker = usageTracker;
        this.client = AnthropicClients.build(apiKey, workspaceId);
        if (this.client == null) {
            log.warn("ANTHROPIC_API_KEY is not set - vision extraction is disabled");
        } else {
            log.info("Vision extraction enabled (model={})", model);
        }
    }

    public boolean isEnabled() {
        return client != null;
    }

    /** Extract fields from a single document image; empty if disabled or the bytes aren't a readable image. */
    public Optional<Extraction> extract(byte[] fileBytes) {
        return extract(List.of(fileBytes));
    }

    /**
     * Extract fields from the pages of one document (one or more photos) in a single request. Unreadable
     * images are skipped; empty if disabled or none of the images are readable.
     */
    public Optional<Extraction> extract(List<byte[]> images) {
        return extract(images, null);
    }

    /**
     * Like {@link #extract(List)}, and when {@code remindersAsOf} is set also asks the same call for the
     * document's upcoming deadlines (reminder suggestions), telling the model that this is today's date.
     * Null keeps the original schema and prompt — no extra tokens.
     */
    public Optional<Extraction> extract(List<byte[]> images, LocalDate remindersAsOf) {
        if (client == null) {
            return Optional.empty();
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (byte[] image : images) {
            byte[] jpeg = toJpeg(image);
            if (jpeg == null) {
                continue;
            }
            String base64 = Base64.getEncoder().encodeToString(jpeg);
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(base64)
                            .build())
                    .build()));
        }
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        String prompt = remindersAsOf == null ? PROMPT : PROMPT + REMINDERS_PROMPT.formatted(remindersAsOf);
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()));

        // Scale the output budget with the page count so a long multi-page transcript isn't truncated.
        long maxTokens = Math.max(4096L, Math.min(16384L, 2048L * blocks.size()));
        try {
            if (remindersAsOf == null) {
                return request(ExtractedFields.class, blocks, maxTokens)
                        .map(reply -> new Extraction(reply.value(), reply.inputTokens(), reply.outputTokens()));
            }
            return request(ExtractionWithReminders.class, blocks, maxTokens)
                    .filter(reply -> reply.value().document() != null)
                    .map(reply -> new Extraction(reply.value().document(), reply.value().reminders(),
                            reply.inputTokens(), reply.outputTokens()));
        } catch (RuntimeException e) {
            log.error("Vision extraction failed", e);
            return Optional.empty();
        }
    }

    /** One structured vision call into {@code type}; usage is recorded even when the reply has no content. */
    private <T> Optional<Reply<T>> request(Class<T> type, List<ContentBlockParam> blocks, long maxTokens) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .outputConfig(type)
                .addUserMessageOfBlockParams(blocks)
                .build();
        StructuredMessage<T> response = client.messages().create(params);
        long inputTokens = response.usage().inputTokens();
        long outputTokens = response.usage().outputTokens();
        recordUsage(inputTokens, outputTokens);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .map(value -> new Reply<>(value, inputTokens, outputTokens));
    }

    private record Reply<T>(T value, long inputTokens, long outputTokens) {
    }

    /** Persist/log token usage; best-effort so a tracking failure never discards a good extraction. */
    private void recordUsage(long inputTokens, long outputTokens) {
        try {
            usageTracker.record(inputTokens, outputTokens, model);
        } catch (RuntimeException e) {
            log.warn("Failed to record API usage", e);
        }
    }

    /** Normalize any supported input (JPEG/PNG, or iPhone HEIC via heif-convert) to JPEG bytes. */
    private byte[] toJpeg(byte[] input) {
        BufferedImage image = readImage(input);
        if (image == null) {
            image = heicConverter.toJpeg(input).map(this::readImage).orElse(null);
        }
        if (image == null) {
            return null;
        }
        try {
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.createGraphics().drawImage(image, 0, 0, Color.WHITE, null);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }
}
