package com.example.homedocsregistrar.retrieval;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Reads stored documents for retrieval. The file itself lives on Telegram (primary store); this only
 * resolves a document's metadata — including its {@code telegram_file_id} — so a caller can re-send
 * the file by that id. (The backup relay for the home agent will reuse this in a later step.)
 */
@Service
public class DocumentRetrievalService {

    private final DocumentRepository documents;

    public DocumentRetrievalService(DocumentRepository documents) {
        this.documents = documents;
    }

    /** Look up a document (with its pages) by its registry id, so a caller can send every page's file. */
    @Transactional(readOnly = true)
    public Optional<Document> byId(long id) {
        return documents.findWithPagesById(id);
    }
}
