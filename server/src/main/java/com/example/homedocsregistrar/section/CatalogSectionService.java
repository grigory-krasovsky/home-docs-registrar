package com.example.homedocsregistrar.section;

import com.example.homedocsregistrar.domain.CatalogSection;
import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.CatalogSectionRepository;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /** Result of filing a document: which document, and the resolved «Раздел / Подсекция» path. */
    public record Assignment(long documentId, String sectionPath) {
    }
}
