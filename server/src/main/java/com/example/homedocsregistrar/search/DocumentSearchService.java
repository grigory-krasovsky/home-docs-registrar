package com.example.homedocsregistrar.search;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
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
    private final double expandRankFloor;

    public DocumentSearchService(DocumentRepository documents, QueryExpansionService expansion,
                                 @Value("${search.expand-rank-floor:0.3}") double expandRankFloor) {
        this.documents = documents;
        this.expansion = expansion;
        this.expandRankFloor = expandRankFloor;
    }

    /**
     * Top matches for a free-text query. Precision first, in three tiers, each tried only if the previous
     * found nothing:
     * <ol>
     *   <li><b>Literal AND</b> — an exact multi-word match («свидетельство о браке» → the marriage cert).</li>
     *   <li><b>OR of the query's own words</b> — a distinctive word still matches even if not every word
     *       does («водительские права» → the licence, which contains «водительск» but not «права»). No LLM,
     *       no synonym pollution.</li>
     *   <li><b>LLM query expansion</b> — the words appear in no document («свадьба» → «брак»). Broadening by
     *       synonyms risks pulling in unrelated docs that share a common word, so the weak tail is dropped
     *       (only hits within {@code expandRankFloor} of the top hit survive).</li>
     * </ol>
     * Empty result for a blank query.
     */
    public SearchResult search(String query) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), List.of());
        }
        String original = query.trim();

        List<Document> literalHits = documents.search(buildOrQuery(original, List.of()), MAX_RESULTS);
        if (!literalHits.isEmpty()) {
            return new SearchResult(literalHits, List.of());
        }

        List<String> words = queryWords(original);
        if (words.size() > 1) {
            List<Document> wordHits = documents.search(
                    buildOrQuery(words.get(0), words.subList(1, words.size())), MAX_RESULTS);
            if (!wordHits.isEmpty()) {
                return new SearchResult(wordHits, List.of());
            }
        }

        List<String> related = expansion.relatedTerms(original);
        if (related.isEmpty()) {
            return new SearchResult(List.of(), List.of());
        }
        List<Document> expandedHits = documents.searchWithRankFloor(
                buildOrQuery(original, related), expandRankFloor, MAX_RESULTS);
        return new SearchResult(expandedHits, related);
    }

    /** The query split into its individual words — for the OR-of-own-words tier. */
    private static List<String> queryWords(String query) {
        return Arrays.stream(query.trim().split("\\s+"))
                .filter(word -> !word.isBlank())
                .toList();
    }

    /**
     * OR-search over caller-supplied terms (no query expansion) — for a caller that already extracted the
     * words to look for (e.g. Q&A stripping a question down to its nouns). Any document matching at least
     * one term is returned, ranked by relevance. Empty result for no usable terms.
     */
    public SearchResult searchAny(List<String> terms) {
        List<String> clean = terms == null ? List.of()
                : terms.stream().filter(term -> term != null && !term.isBlank()).toList();
        if (clean.isEmpty()) {
            return new SearchResult(List.of(), List.of());
        }
        String query = buildOrQuery(clean.get(0), clean.subList(1, clean.size()));
        return new SearchResult(documents.search(query, MAX_RESULTS), List.of());
    }

    /**
     * Build one {@code websearch_to_tsquery} string that ORs the original and related terms. Terms are NOT
     * quoted: a multi-word term becomes an AND of its words (matches when all words appear anywhere), not a
     * strict phrase — otherwise «свидетельство о браке» wouldn't match «свидетельство о заключении брака».
     * Quotes and hyphens are stripped so the term isn't parsed as a phrase or a NOT operator.
     */
    private static String buildOrQuery(String original, List<String> related) {
        return Stream.concat(Stream.of(original), related.stream())
                .map(term -> term.replaceAll("[\"-]", " ").trim().replaceAll("\\s+", " "))
                .filter(term -> !term.isBlank())
                .collect(Collectors.joining(" or "));
    }

    /** Search hits plus the related terms the query was expanded with (empty when not expanded). */
    public record SearchResult(List<Document> hits, List<String> relatedTerms) {
    }
}
