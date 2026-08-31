package com.example.homedocsregistrar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One source photo of a {@link Document}. The file itself lives on Telegram (retrieved by
 * {@link #telegramFileId}); a single-page document simply has one page. Pages are ordered by
 * {@link #pageNumber} (the order they arrived in the album).
 */
@Entity
@Table(
        name = "document_page",
        indexes = @Index(name = "idx_document_page_document", columnList = "document_id"),
        uniqueConstraints = @UniqueConstraint(name = "uq_document_page", columnNames = {"document_id", "page_number"})
)
public class DocumentPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "telegram_file_id")
    private String telegramFileId;

    @Column(name = "channel_message_id")
    private Long channelMessageId;

    /** SHA-256 of this file's bytes; per-file key for idempotent backup. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "original_filename")
    private String originalFilename;

    protected DocumentPage() { // for JPA
    }

    public DocumentPage(int pageNumber, String telegramFileId, Long channelMessageId,
                        String contentHash, Long fileSizeBytes, String originalFilename) {
        this.pageNumber = pageNumber;
        this.telegramFileId = telegramFileId;
        this.channelMessageId = channelMessageId;
        this.contentHash = contentHash;
        this.fileSizeBytes = fileSizeBytes;
        this.originalFilename = originalFilename;
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    void setDocument(Document document) {
        this.document = document;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getTelegramFileId() {
        return telegramFileId;
    }

    public Long getChannelMessageId() {
        return channelMessageId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }
}
