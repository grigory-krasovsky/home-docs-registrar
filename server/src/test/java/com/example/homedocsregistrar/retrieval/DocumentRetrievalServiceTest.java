package com.example.homedocsregistrar.retrieval;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DocumentRetrievalServiceTest {

    @Autowired
    private DocumentRetrievalService retrieval;

    @Autowired
    private DocumentRepository documents;

    @Test
    void findsStoredDocumentByIdAndResolvesItsFileId() {
        Document document = new Document();
        document.setTelegramFileId("FILE_ID_123");
        document.setDocType("чек");
        Document saved = documents.save(document);

        assertThat(retrieval.byId(saved.getId()))
                .get()
                .extracting(Document::getTelegramFileId)
                .isEqualTo("FILE_ID_123");
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertThat(retrieval.byId(999_999L)).isEmpty();
    }
}
