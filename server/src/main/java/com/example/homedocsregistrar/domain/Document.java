package com.example.homedocsregistrar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A registered document. Text/metadata are the source of truth on the VPS; the file itself lives on
 * Telegram (see {@link #telegramFileId} / {@link #channelMessageId}) and is mirrored to the home PC
 * when it is online (see {@link #backupStatus}).
 */
@Entity
@Table(
        name = "document",
        indexes = {
                @Index(name = "idx_document_backup_status", columnList = "backup_status"),
                @Index(name = "idx_document_content_hash", columnList = "content_hash")
        }
)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // --- extracted / confirmed fields (nullable: filled by OCR + human confirmation) ---

    private String title;

    @Column(name = "doc_type")
    private String docType;

    private String counterparty;

    @Column(name = "doc_date")
    private LocalDate docDate;

    private BigDecimal amount;

    @Column(name = "warranty_until")
    private LocalDate warrantyUntil;

    /** Full OCR text; the basis for full-text search (Postgres FTS added in step 3). */
    @Column(name = "ocr_text", columnDefinition = "text")
    private String ocrText;

    // --- physical location ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private CatalogSection section;

    // --- file on Telegram (primary store) ---

    /** Telegram {@code file_id} of the archived document; the handle used to re-send / download it. */
    @Column(name = "telegram_file_id")
    private String telegramFileId;

    /** Message id of the document inside the private archive channel. */
    @Column(name = "channel_message_id")
    private Long channelMessageId;

    /** SHA-256 of the file bytes; stable key for idempotent backup. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "original_filename")
    private String originalFilename;

    // --- backup state ---

    @Enumerated(EnumType.STRING)
    @Column(name = "backup_status", nullable = false, length = 32)
    private BackupStatus backupStatus = BackupStatus.PENDING_BACKUP;

    public Document() { // for JPA and for application code building a document up via setters
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public LocalDate getDocDate() {
        return docDate;
    }

    public void setDocDate(LocalDate docDate) {
        this.docDate = docDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getWarrantyUntil() {
        return warrantyUntil;
    }

    public void setWarrantyUntil(LocalDate warrantyUntil) {
        this.warrantyUntil = warrantyUntil;
    }

    public String getOcrText() {
        return ocrText;
    }

    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }

    public CatalogSection getSection() {
        return section;
    }

    public void setSection(CatalogSection section) {
        this.section = section;
    }

    public String getTelegramFileId() {
        return telegramFileId;
    }

    public void setTelegramFileId(String telegramFileId) {
        this.telegramFileId = telegramFileId;
    }

    public Long getChannelMessageId() {
        return channelMessageId;
    }

    public void setChannelMessageId(Long channelMessageId) {
        this.channelMessageId = channelMessageId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public BackupStatus getBackupStatus() {
        return backupStatus;
    }

    public void setBackupStatus(BackupStatus backupStatus) {
        this.backupStatus = backupStatus;
    }
}
