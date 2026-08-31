package com.example.homedocsregistrar.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    @Column(name = "document_number")
    private String documentNumber;

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

    // --- files on Telegram (primary store): one page per source photo ---

    /** SHA-256 of the whole pack (all pages in order); stable key for dedupe. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "page_count", nullable = false)
    private int pageCount = 1;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pageNumber")
    private List<DocumentPage> pages = new ArrayList<>();

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

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
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

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public List<DocumentPage> getPages() {
        return pages;
    }

    /** Attach a page and keep both sides of the relationship consistent. */
    public void addPage(DocumentPage page) {
        page.setDocument(this);
        pages.add(page);
    }

    public BackupStatus getBackupStatus() {
        return backupStatus;
    }

    public void setBackupStatus(BackupStatus backupStatus) {
        this.backupStatus = backupStatus;
    }
}
