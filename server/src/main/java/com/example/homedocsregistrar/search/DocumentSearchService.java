package com.example.homedocsregistrar.search;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Russian full-text search over stored documents (transcribed text + key fields), ranked by relevance.
 * Backed by Postgres FTS in {@link DocumentRepository#search}; returns the top matches so the caller
 * can list them and retrieve a file by id.
 */
@Service
public class DocumentSearchService {

    private static final int MAX_RESULTS = 10;

    private final DocumentRepository documents;

    public DocumentSearchService(DocumentRepository documents) {
        this.documents = documents;
    }

    /** Top matches for a free-text query; empty for a blank query. */
    public List<Document> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return documents.search(query.trim(), MAX_RESULTS);
    }
}
