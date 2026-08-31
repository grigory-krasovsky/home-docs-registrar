package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.BackupStatus;
import com.example.homedocsregistrar.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** Documents the home PC has not backed up yet — the agent's work list. */
    List<Document> findByBackupStatus(BackupStatus backupStatus);

    /** Idempotency lookup: a document is identified by the SHA-256 of its file bytes. */
    Optional<Document> findByContentHash(String contentHash);

    /**
     * Russian full-text search over the transcribed text and key fields, ranked by relevance.
     * Postgres-specific (`to_tsvector`/`websearch_to_tsquery`, config {@code russian}) — not exercised
     * by the H2 tests; verified against real Postgres. No GIN index yet (query-time tsvector); fine at
     * a personal registry's scale, add a generated column + GIN index if the corpus grows.
     */
    @Query(value = """
            SELECT * FROM document
            WHERE to_tsvector('russian',
                    coalesce(ocr_text, '') || ' ' || coalesce(title, '') || ' ' ||
                    coalesce(doc_type, '') || ' ' || coalesce(counterparty, '') || ' ' ||
                    coalesce(document_number, '')) @@ websearch_to_tsquery('russian', :query)
            ORDER BY ts_rank(to_tsvector('russian',
                    coalesce(ocr_text, '') || ' ' || coalesce(title, '') || ' ' ||
                    coalesce(doc_type, '') || ' ' || coalesce(counterparty, '') || ' ' ||
                    coalesce(document_number, '')),
                    websearch_to_tsquery('russian', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Document> search(@Param("query") String query, @Param("limit") int limit);
}
