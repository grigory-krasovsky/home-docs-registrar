package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.BackupStatus;
import com.example.homedocsregistrar.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** Documents the home PC has not backed up yet — the agent's work list. */
    List<Document> findByBackupStatus(BackupStatus backupStatus);

    /** Idempotency lookup: a document is identified by the SHA-256 of its whole page pack. */
    Optional<Document> findByContentHash(String contentHash);

    /** Load a document with its pages eagerly (open-in-view is off, so callers can send all files). */
    @EntityGraph(attributePaths = "pages")
    Optional<Document> findWithPagesById(Long id);

    /** Documents filed in a given section (leaf), newest first — for the /browse catalog view. */
    Page<Document> findBySection_IdOrderByCreatedAtDesc(Long sectionId, Pageable pageable);

    /** How many documents are filed in a section — for the subsection button counts. */
    long countBySection_Id(Long sectionId);

    /** Documents not filed into any section yet — the «✱ Без секции» bucket in /browse. */
    Page<Document> findBySectionIsNullOrderByCreatedAtDesc(Pageable pageable);

    long countBySectionIsNull();

    /**
     * Russian full-text search over the transcribed text and key fields, ranked by relevance. Uses the
     * GIN-indexed {@code search_vector} generated column (see Flyway V2). Postgres-specific
     * (`websearch_to_tsquery`, config {@code russian}) — not exercised by the H2 tests; verified against
     * real Postgres.
     */
    @Query(value = """
            SELECT * FROM document
            WHERE search_vector @@ websearch_to_tsquery('russian', :query)
            ORDER BY ts_rank(search_vector, websearch_to_tsquery('russian', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Document> search(@Param("query") String query, @Param("limit") int limit);

    /**
     * Like {@link #search}, but drops the weak tail: only documents whose relevance is at least
     * {@code floor} times the top hit's relevance are returned. Used for the query-expansion pass, where a
     * single broad synonym would otherwise pull in unrelated documents that merely share a common word.
     * Postgres-specific; verified against real Postgres (not exercised by the H2 tests).
     */
    @Query(value = """
            SELECT d.* FROM document d
            WHERE d.search_vector @@ websearch_to_tsquery('russian', :query)
              AND ts_rank(d.search_vector, websearch_to_tsquery('russian', :query)) >= :floor * (
                    SELECT MAX(ts_rank(dm.search_vector, websearch_to_tsquery('russian', :query)))
                    FROM document dm
                    WHERE dm.search_vector @@ websearch_to_tsquery('russian', :query))
            ORDER BY ts_rank(d.search_vector, websearch_to_tsquery('russian', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Document> searchWithRankFloor(@Param("query") String query, @Param("floor") double floor,
                                       @Param("limit") int limit);
}
