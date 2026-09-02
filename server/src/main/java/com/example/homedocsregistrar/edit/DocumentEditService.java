package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Edits to an already-saved document. For now only the title can be changed (a human correction of the
 * vision model's guess). The Postgres {@code search_vector} is a STORED generated column over title +
 * fields, so it re-computes automatically on update — no manual reindex.
 */
@Service
public class DocumentEditService {

    /** {@code document.title} is {@code varchar(255)} — cap the input so a long paste can't overflow it. */
    private static final int MAX_TITLE = 255;

    private final DocumentRepository documents;

    public DocumentEditService(DocumentRepository documents) {
        this.documents = documents;
    }

    /** Set a document's title; returns the updated document, or empty if the id is unknown. */
    @Transactional
    public Optional<Document> renameTitle(long documentId, String title) {
        String trimmed = title.strip();
        String capped = trimmed.length() > MAX_TITLE ? trimmed.substring(0, MAX_TITLE) : trimmed;
        return documents.findById(documentId).map(document -> {
            document.setTitle(capped);
            return documents.save(document);
        });
    }
}
