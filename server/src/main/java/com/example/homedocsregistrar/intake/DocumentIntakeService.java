package com.example.homedocsregistrar.intake;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import com.example.homedocsregistrar.telegram.TelegramProperties;
import com.example.homedocsregistrar.telegram.TelegramSender;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Persists a received document into the registry: dedupes by content hash, archives the file to the
 * private Telegram channel (if configured), and saves the {@link Document} row. Field confirmation
 * and section selection are layered on top later.
 */
@Service
public class DocumentIntakeService {

    private final DocumentRepository documents;
    private final TelegramSender sender;
    private final TelegramProperties telegram;

    public DocumentIntakeService(DocumentRepository documents, TelegramSender sender, TelegramProperties telegram) {
        this.documents = documents;
        this.sender = sender;
        this.telegram = telegram;
    }

    public IntakeResult save(String fileId, String fileName, byte[] fileBytes, String ocrText) {
        String hash = sha256(fileBytes);
        Optional<Document> existing = documents.findByContentHash(hash);
        if (existing.isPresent()) {
            return new IntakeResult(existing.get(), true);
        }

        Long channelMessageId = archiveToChannel(fileId, fileName);

        Document document = new Document();
        document.setOcrText(ocrText == null || ocrText.isBlank() ? null : ocrText);
        document.setTelegramFileId(fileId);
        document.setChannelMessageId(channelMessageId);
        document.setContentHash(hash);
        document.setFileSizeBytes((long) fileBytes.length);
        document.setOriginalFilename(fileName);
        documents.save(document);
        return new IntakeResult(document, false);
    }

    /** Re-post the file to the archive channel; returns its message id, or null if no channel is set. */
    private Long archiveToChannel(String fileId, String fileName) {
        String channelId = telegram.archiveChannelId();
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        Integer messageId = sender.sendDocumentByFileId(channelId, fileId, fileName);
        return messageId == null ? null : messageId.longValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Outcome of an intake attempt. {@code duplicate} means the document already existed (same hash). */
    public record IntakeResult(Document document, boolean duplicate) {
    }
}
