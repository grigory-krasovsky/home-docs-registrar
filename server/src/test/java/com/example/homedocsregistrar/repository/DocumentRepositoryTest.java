package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.BackupStatus;
import com.example.homedocsregistrar.domain.CatalogSection;
import com.example.homedocsregistrar.domain.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DocumentRepositoryTest {

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private CatalogSectionRepository sections;

    @Test
    void persistsAndQueriesByStatusHashAndSection() {
        CatalogSection section = sections.save(new CatalogSection("QR-A1", "Shelf A / Drawer 1"));

        Document doc = new Document();
        doc.setTitle("Договор аренды");
        doc.setOcrText("образец текста договора");
        doc.setTelegramFileId("FILEID123");
        doc.setContentHash("abc123");
        doc.setSection(section);
        documents.save(doc);

        // default status is PENDING_BACKUP -> the document shows up in the agent's work list
        List<Document> pending = documents.findByBackupStatus(BackupStatus.PENDING_BACKUP);
        assertThat(pending).extracting(Document::getTitle).containsExactly("Договор аренды");
        assertThat(documents.findByBackupStatus(BackupStatus.BACKED_UP)).isEmpty();

        // idempotency lookup by content hash resolves the document (and its section)
        Optional<Document> byHash = documents.findByContentHash("abc123");
        assertThat(byHash).isPresent();
        assertThat(byHash.get().getSection().getCode()).isEqualTo("QR-A1");

        // section lookup by QR code
        assertThat(sections.findByCode("QR-A1")).isPresent();
        assertThat(sections.findByCode("QR-NONE")).isEmpty();
    }
}
