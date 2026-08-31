package com.example.homedocsregistrar.intake;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.domain.DocumentPage;
import com.example.homedocsregistrar.extraction.ExtractedFields;
import com.example.homedocsregistrar.repository.DocumentRepository;
import com.example.homedocsregistrar.telegram.TelegramProperties;
import com.example.homedocsregistrar.telegram.TelegramSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Persists a received document into the registry: dedupes by content hash, archives the file to the
 * private Telegram channel (if configured), fills the {@link Document} from the vision-extracted
 * {@link ExtractedFields}, and saves the row.
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

    /**
     * Look up an already-stored document with the same content, so callers can skip re-processing
     * (e.g. the vision API call) for a duplicate. {@link #save} re-checks this to stay idempotent.
     */
    public Optional<Document> findExisting(List<byte[]> pageBytes) {
        return documents.findByContentHash(packHash(pageBytes));
    }

    /** Save one logical document from its ordered pages (one per source photo) and extracted fields. */
    public IntakeResult save(List<IncomingPage> pages, ExtractedFields fields) {
        String hash = packHash(pages.stream().map(IncomingPage::bytes).toList());
        Optional<Document> existing = documents.findByContentHash(hash);
        if (existing.isPresent()) {
            return new IntakeResult(existing.get(), true);
        }

        Document document = new Document();
        applyFields(document, fields);
        document.setContentHash(hash);
        document.setPageCount(pages.size());
        int pageNumber = 1;
        for (IncomingPage page : pages) {
            Long channelMessageId = archiveToChannel(page.fileId(), page.fileName());
            document.addPage(new DocumentPage(pageNumber++, page.fileId(), channelMessageId,
                    sha256(page.bytes()), (long) page.bytes().length, page.fileName()));
        }
        documents.save(document);
        return new IntakeResult(document, false);
    }

    private void applyFields(Document document, ExtractedFields fields) {
        if (fields == null) {
            return;
        }
        document.setDocType(blankToNull(fields.docType()));
        document.setTitle(blankToNull(fields.title()));
        document.setCounterparty(blankToNull(fields.counterparty()));
        document.setDocumentNumber(blankToNull(fields.number()));
        document.setDocDate(parseDate(fields.docDate()));
        document.setWarrantyUntil(parseDate(fields.warrantyUntil()));
        document.setAmount(parseAmount(fields.amount()));
        document.setOcrText(blankToNull(fields.fullText()));
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

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("[^0-9.,-]", "").replace(',', '.');
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** SHA-256 over all pages' bytes in order — the stable key identifying the whole pack. */
    private static String packHash(List<byte[]> pages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] page : pages) {
                digest.update(page);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** One incoming page (source photo): its Telegram file id, filename, and downloaded bytes. */
    public record IncomingPage(String fileId, String fileName, byte[] bytes) {
    }

    /** Outcome of an intake attempt. {@code duplicate} means the document already existed (same hash). */
    public record IntakeResult(Document document, boolean duplicate) {
    }
}
