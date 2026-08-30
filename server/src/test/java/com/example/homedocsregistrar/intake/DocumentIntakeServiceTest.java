package com.example.homedocsregistrar.intake;

import com.example.homedocsregistrar.domain.BackupStatus;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IntakeResult;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DocumentIntakeServiceTest {

    @Autowired
    private DocumentIntakeService intake;

    @Autowired
    private DocumentRepository documents;

    @Test
    void savesNewDocumentThenDeduplicatesByContentHash() {
        byte[] bytes = "file-content".getBytes(StandardCharsets.UTF_8);

        IntakeResult first = intake.save("FILE1", "scan.jpg", bytes, "распознанный текст");

        assertThat(first.duplicate()).isFalse();
        assertThat(first.document().getId()).isNotNull();
        assertThat(first.document().getTelegramFileId()).isEqualTo("FILE1");
        assertThat(first.document().getBackupStatus()).isEqualTo(BackupStatus.PENDING_BACKUP);
        assertThat(first.document().getContentHash()).isNotBlank();
        // No archive channel is configured in tests, so nothing is re-posted.
        assertThat(first.document().getChannelMessageId()).isNull();

        // Same bytes -> same hash -> recognized as a duplicate, no second row.
        IntakeResult second = intake.save("FILE2", "scan-again.jpg", bytes, "другой текст");

        assertThat(second.duplicate()).isTrue();
        assertThat(second.document().getId()).isEqualTo(first.document().getId());
        assertThat(documents.count()).isEqualTo(1);
    }
}
