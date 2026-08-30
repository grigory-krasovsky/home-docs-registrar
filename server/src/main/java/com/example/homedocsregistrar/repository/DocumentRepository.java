package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.BackupStatus;
import com.example.homedocsregistrar.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** Documents the home PC has not backed up yet — the agent's work list. */
    List<Document> findByBackupStatus(BackupStatus backupStatus);

    /** Idempotency lookup: a document is identified by the SHA-256 of its file bytes. */
    Optional<Document> findByContentHash(String contentHash);
}
