package com.example.homedocsregistrar.section;

import com.example.homedocsregistrar.domain.CatalogSection;
import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.CatalogSectionRepository;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the card-catalog tree and files documents into it. The tree is two levels: top-level
 * owners («Гриша», «Общая», …) and their subsections. Creating a subsection merges into an existing
 * sibling with the same name (case-insensitive) so near-duplicates don't inflate the catalog.
 */
@Service
public class CatalogSectionService {

    private final CatalogSectionRepository sections;
    private final DocumentRepository documents;

    public CatalogSectionService(CatalogSectionRepository sections, DocumentRepository documents) {
        this.sections = sections;
        this.documents = documents;
    }

    /** Top-level sections (owners), oldest first. */
    public List<CatalogSection> topLevel() {
        return sections.findByParentIsNullOrderByIdAsc();
    }

    /** Subsections of a top-level section, oldest first. */
    public List<CatalogSection> subsections(CatalogSection parent) {
        return sections.findByParentOrderByIdAsc(parent);
    }

    /**
     * All leaf subsection paths «Владелец / Подсекция» — the buckets shown to the suggester. Built
     * inside a transaction so {@link CatalogSection#path()} can load each leaf's (lazy) parent; callers
     * get plain strings and never touch a JPA proxy.
     */
    @Transactional(readOnly = true)
    public List<String> leafPaths() {
        return sections.findByParentIsNotNullOrderByIdAsc().stream().map(CatalogSection::path).toList();
    }

    public Optional<CatalogSection> byId(Long id) {
        return id == null ? Optional.empty() : sections.findById(id);
    }

    public Optional<CatalogSection> findTopLevel(String label) {
        return sections.findByParentIsNullAndLabelIgnoreCase(label);
    }

    /**
     * Turn a model suggestion «Владелец / Подсекция» into an assignable leaf, creating the subsection if
     * it's new. Empty when the owner doesn't exist (the model shouldn't invent owners, but guard anyway).
     */
    @Transactional
    public Optional<CatalogSection> resolveSuggestion(SectionSuggestionService.Suggestion suggestion) {
        return findTopLevel(suggestion.owner()).map(owner -> getOrCreateSubsection(owner, suggestion.sub()));
    }

    /** Find an existing subsection by name under {@code parent} (case-insensitive), or create it. */
    @Transactional
    public CatalogSection getOrCreateSubsection(CatalogSection parent, String label) {
        String trimmed = label.trim();
        return sections.findByParentAndLabelIgnoreCase(parent, trimmed)
                .orElseGet(() -> sections.save(new CatalogSection(trimmed, parent)));
    }

    /** Find an existing top-level section by name (case-insensitive), or create it. */
    @Transactional
    public CatalogSection getOrCreateTopLevel(String label) {
        String trimmed = label.trim();
        return findTopLevel(trimmed).orElseGet(() -> sections.save(new CatalogSection(trimmed, (CatalogSection) null)));
    }

    /** File a document into a section; returns the resulting placement, or empty if either id is unknown. */
    @Transactional
    public Optional<Assignment> assign(long documentId, long sectionId) {
        Optional<CatalogSection> section = sections.findById(sectionId);
        Optional<Document> document = documents.findById(documentId);
        if (section.isEmpty() || document.isEmpty()) {
            return Optional.empty();
        }
        document.get().setSection(section.get());
        documents.save(document.get());
        // path() is computed here, inside the transaction, so the lazy parent is loaded before returning.
        return Optional.of(new Assignment(documentId, section.get().path()));
    }

    /** The «Раздел / Подсекция» path a document is currently filed in, or empty if it isn't filed yet. */
    @Transactional(readOnly = true)
    public Optional<String> currentSectionPath(long documentId) {
        return documents.findById(documentId).map(Document::getSection).map(CatalogSection::path);
    }

    /**
     * Owner (top-level section label) per document id — for the per-person emoji on result buttons.
     * Documents without a section are simply absent from the map. One query, safe outside the caller's tx.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> ownersOf(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> owners = new HashMap<>();
        for (Object[] row : documents.findOwnerLabels(documentIds)) {
            owners.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return owners;
    }

    /**
     * A page of documents (newest first) for the /manage_sections browser: id, a short title, and the
     * current section path (null if unfiled). Rendered inside a transaction so the lazy section/parent
     * load safely; callers get plain data.
     */
    @Transactional(readOnly = true)
    public DocPage recentDocuments(int page, int size) {
        Page<Document> slice = documents.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<DocDigest> items = slice.getContent().stream()
                .map(doc -> new DocDigest(doc.getId(), shortTitle(doc),
                        doc.getSection() == null ? null : doc.getSection().path()))
                .toList();
        return new DocPage(items, slice.hasNext());
    }

    /** Subsections of a top-level section with their document counts — for the /browse view. */
    @Transactional(readOnly = true)
    public List<SectionCount> subsectionCounts(long parentId) {
        return sections.findById(parentId)
                .map(parent -> sections.findByParentOrderByIdAsc(parent).stream()
                        .map(sub -> new SectionCount(sub.getId(), sub.getLabel(), documents.countBySection_Id(sub.getId())))
                        .toList())
                .orElseGet(List::of);
    }

    /** A page of documents filed in a section (newest first) for /browse. */
    @Transactional(readOnly = true)
    public DocPage documentsInSection(long sectionId, int page, int size) {
        return toDocPage(documents.findBySection_IdOrderByCreatedAtDesc(sectionId, PageRequest.of(page, size)));
    }

    /** A page of documents not filed into any section — the «✱ Без секции» bucket. */
    @Transactional(readOnly = true)
    public DocPage unfiledDocuments(int page, int size) {
        return toDocPage(documents.findBySectionIsNullOrderByCreatedAtDesc(PageRequest.of(page, size)));
    }

    public long countInSection(long sectionId) {
        return documents.countBySection_Id(sectionId);
    }

    public long countUnfiled() {
        return documents.countBySectionIsNull();
    }

    /** The «Раздел / Подсекция» path of a section (loaded in-tx so the lazy parent resolves). */
    @Transactional(readOnly = true)
    public Optional<String> sectionPath(long sectionId) {
        return sections.findById(sectionId).map(CatalogSection::path);
    }

    private static DocPage toDocPage(Page<Document> slice) {
        List<DocDigest> items = slice.getContent().stream()
                .map(doc -> new DocDigest(doc.getId(), shortTitle(doc), null))
                .toList();
        return new DocPage(items, slice.hasNext());
    }

    /** Best short name for a document: title, else type, else a generic label. */
    private static String shortTitle(Document doc) {
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) {
            return doc.getTitle();
        }
        if (doc.getDocType() != null && !doc.getDocType().isBlank()) {
            return doc.getDocType();
        }
        return "документ";
    }

    /** Result of filing a document: which document, and the resolved «Раздел / Подсекция» path. */
    public record Assignment(long documentId, String sectionPath) {
    }

    /** One row in the document browser: id, short title, and current section path (null = unfiled). */
    public record DocDigest(long id, String title, String sectionPath) {
    }

    /** A page of the document browser plus whether a next page exists. */
    public record DocPage(List<DocDigest> items, boolean hasNext) {
    }

    /** A subsection with how many documents are filed in it — for the /browse buttons. */
    public record SectionCount(long id, String label, long count) {
    }
}
