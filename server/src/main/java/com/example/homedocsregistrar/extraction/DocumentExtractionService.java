package com.example.homedocsregistrar.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
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
            Ты извлекаешь данные из фотографии документа (чек, договор, гарантия, свидетельство и т.п.).
            Заполни поля по схеме. Если поля нет в документе — верни null. Даты — в формате ГГГГ-ММ-ДД.
            Сумму верни числом без валюты (например 4559.00). В fullText помести весь распознанный текст.""";

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
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
        } else {
            var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
            // Identity-linked API keys aren't scoped to a workspace and require this header on every
            // request; workspace-scoped keys don't need it, so only send it when configured.
            if (workspaceId != null && !workspaceId.isBlank()) {
                builder.putHeader("anthropic-workspace-id", workspaceId);
            }
            this.client = builder.build();
        }
    }

    public boolean isEnabled() {
        return client != null;
    }

    /** Extract fields from a document image; empty if disabled or the bytes aren't a readable image. */
    public Optional<Extraction> extract(byte[] fileBytes) {
        if (client == null) {
            return Optional.empty();
        }
        byte[] jpeg = toJpeg(fileBytes);
        if (jpeg == null) {
            return Optional.empty();
        }
        String base64 = Base64.getEncoder().encodeToString(jpeg);

        ImageBlockParam image = ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(base64)
                        .build())
                .build();
        TextBlockParam prompt = TextBlockParam.builder().text(PROMPT).build();

        StructuredMessageCreateParams<ExtractedFields> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4096L)
                .outputConfig(ExtractedFields.class)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(prompt)))
                .build();

        try {
            StructuredMessage<ExtractedFields> response = client.messages().create(params);
            long inputTokens = response.usage().inputTokens();
            long outputTokens = response.usage().outputTokens();
            recordUsage(inputTokens, outputTokens);
            return response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .map(fields -> new Extraction(fields, inputTokens, outputTokens));
        } catch (RuntimeException e) {
            log.error("Vision extraction failed", e);
            return Optional.empty();
        }
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
