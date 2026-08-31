package com.example.homedocsregistrar.search;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Russian full-text search over stored documents (transcribed text + key fields), ranked by relevance.
 * The query is first expanded into related terms (see {@link QueryExpansionService}) so a search matches
 * semantically related documents, not only exact word forms; all terms are OR-combined into one Postgres
 * FTS query. Returns the top matches plus the extra terms used, so the UI can show what it also searched.
 */
@Service
public class DocumentSearchService {

    private static final int MAX_RESULTS = 10;

    private final DocumentRepository documents;
    private final QueryExpansionService expansion;

    public DocumentSearchService(DocumentRepository documents, QueryExpansionService expansion) {
        this.documents = documents;
        this.expansion = expansion;
    }

    /** Top matches for a free-text query (with query expansion); empty result for a blank query. */
    public SearchResult search(String query) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), List.of());
        }
        String original = query.trim();
        List<String> related = expansion.relatedTerms(original);
        List<Document> hits = documents.search(buildOrQuery(original, related), MAX_RESULTS);
        return new SearchResult(hits, related);
    }

    /**
     * Build one {@code websearch_to_tsquery} string that ORs the original and related terms. Each term is
     * quoted (so a multi-word term is a phrase) and joined with the {@code or} operator; embedded quotes
     * are stripped so the query stays well-formed.
     */
    private static String buildOrQuery(String original, List<String> related) {
        return Stream.concat(Stream.of(original), related.stream())
                .map(term -> term.replace('"', ' ').trim())
                .filter(term -> !term.isBlank())
                .map(term -> '"' + term + '"')
                .collect(Collectors.joining(" or "));
    }

    /** Search hits plus the related terms the query was expanded with (empty when not expanded). */
    public record SearchResult(List<Document> hits, List<String> relatedTerms) {
    }
}
