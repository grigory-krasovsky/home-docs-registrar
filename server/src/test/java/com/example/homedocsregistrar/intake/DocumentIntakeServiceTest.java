package com.example.homedocsregistrar.intake;

import com.example.homedocsregistrar.domain.BackupStatus;
import com.example.homedocsregistrar.extraction.ExtractedFields;
import com.example.homedocsregistrar.intake.DocumentIntakeService.IntakeResult;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DocumentIntakeServiceTest {

    @Autowired
    private DocumentIntakeService intake;

    @Autowired
    private DocumentRepository documents;

    @Test
    void savesExtractedFieldsThenDeduplicatesByContentHash() {
        byte[] bytes = "file-content".getBytes(StandardCharsets.UTF_8);
        ExtractedFields fields = new ExtractedFields(
                "чек", "Товарный чек", "ООО \"Ромашка\"",
                "2026-08-10", "75485", "4559.00", null, "распознанный текст");

        IntakeResult first = intake.save("FILE1", "scan.jpg", bytes, fields);

        assertThat(first.duplicate()).isFalse();
        assertThat(first.document().getId()).isNotNull();
        assertThat(first.document().getDocType()).isEqualTo("чек");
        assertThat(first.document().getDocumentNumber()).isEqualTo("75485");
        assertThat(first.document().getDocDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(first.document().getAmount()).isEqualByComparingTo(new BigDecimal("4559.00"));
        assertThat(first.document().getWarrantyUntil()).isNull();
        assertThat(first.document().getOcrText()).isEqualTo("распознанный текст");
        assertThat(first.document().getBackupStatus()).isEqualTo(BackupStatus.PENDING_BACKUP);
        // No archive channel is configured in tests, so nothing is re-posted.
        assertThat(first.document().getChannelMessageId()).isNull();

        // Same bytes -> same hash -> recognized as a duplicate, no second row.
        IntakeResult second = intake.save("FILE2", "scan-again.jpg", bytes, fields);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.document().getId()).isEqualTo(first.document().getId());
        assertThat(documents.count()).isEqualTo(1);
    }
}
