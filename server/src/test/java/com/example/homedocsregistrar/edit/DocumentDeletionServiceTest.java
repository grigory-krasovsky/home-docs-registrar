package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.domain.DocumentPage;
import com.example.homedocsregistrar.edit.DocumentDeletionService.DeletionInfo;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DocumentDeletionServiceTest {

    @Autowired
    private DocumentDeletionService service;

    @Autowired
    private DocumentRepository documents;

    private Document saveWithPage(Instant createdAt) {
        Document doc = new Document();
        doc.setTitle("Чек");
        doc.setContentHash("del-" + createdAt.toEpochMilli());
        doc.setCreatedAt(createdAt);
        doc.addPage(new DocumentPage(1, "file-id", 555L, "page-hash", 10L, "f.jpg"));
        return documents.save(doc);
    }

    @Test
    void infoReportsChannelIdsAndDeleteWindow() {
        Instant created = Instant.parse("2026-02-01T00:00:00Z");
        Document doc = saveWithPage(created);

        DeletionInfo fresh = service.info(doc.getId(), created.plus(Duration.ofHours(1))).orElseThrow();
        assertThat(fresh.title()).isEqualTo("Чек");
        assertThat(fresh.channelMessageIds()).containsExactly(555);
        assertThat(fresh.withinDeleteWindow()).isTrue();

        DeletionInfo old = service.info(doc.getId(), created.plus(Duration.ofHours(72))).orElseThrow();
        assertThat(old.withinDeleteWindow()).isFalse(); // posted >48h before "now" — leave the channel file
    }

    @Test
    void deleteRemovesDocumentAndPages() {
        Document doc = saveWithPage(Instant.parse("2026-02-01T00:00:00Z"));

        assertThat(service.delete(doc.getId())).isTrue();
        assertThat(documents.findById(doc.getId())).isEmpty();
        assertThat(documents.findWithPagesById(doc.getId())).isEmpty();
    }

    @Test
    void deleteUnknownIsFalse() {
        assertThat(service.delete(999_999L)).isFalse();
        assertThat(service.info(999_999L, Instant.parse("2026-02-01T00:00:00Z"))).isEmpty();
    }
}
