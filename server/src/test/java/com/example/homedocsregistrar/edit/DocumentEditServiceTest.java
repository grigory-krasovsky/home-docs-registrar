package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DocumentEditServiceTest {

    @Autowired
    private DocumentEditService service;

    @Autowired
    private DocumentRepository documents;

    @Test
    void renamesTitleAndTrims() {
        Document doc = new Document();
        doc.setTitle("Договор");
        doc.setContentHash("rn-1");
        documents.save(doc);

        Optional<Document> updated = service.renameTitle(doc.getId(), "  Договор аренды 2026  ");

        assertThat(updated).isPresent();
        assertThat(updated.get().getTitle()).isEqualTo("Договор аренды 2026");
        assertThat(documents.findById(doc.getId()).orElseThrow().getTitle()).isEqualTo("Договор аренды 2026");
    }

    @Test
    void emptyWhenDocumentUnknown() {
        assertThat(service.renameTitle(999_999L, "неважно")).isEmpty();
    }
}
