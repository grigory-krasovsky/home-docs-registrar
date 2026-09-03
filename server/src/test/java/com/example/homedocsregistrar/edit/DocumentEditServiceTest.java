package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.edit.DocumentEditService.Field;
import com.example.homedocsregistrar.edit.DocumentEditService.UpdateResult;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        Document doc = savedDoc("rn-1");
        doc.setTitle("Договор");
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

    @Test
    void parsesIsoDate() {
        Document doc = savedDoc("dt-iso");

        UpdateResult result = service.updateField(doc.getId(), Field.DATE, "2027-03-01");

        assertThat(result.isOk()).isTrue();
        assertThat(documents.findById(doc.getId()).orElseThrow().getDocDate()).isEqualTo(LocalDate.parse("2027-03-01"));
    }

    @Test
    void parsesRussianDate() {
        Document doc = savedDoc("dt-ru");

        UpdateResult result = service.updateField(doc.getId(), Field.WARRANTY, "01.03.2027");

        assertThat(result.isOk()).isTrue();
        assertThat(documents.findById(doc.getId()).orElseThrow().getWarrantyUntil())
                .isEqualTo(LocalDate.parse("2027-03-01"));
    }

    @Test
    void rejectsBadDate() {
        Document doc = savedDoc("dt-bad");

        UpdateResult result = service.updateField(doc.getId(), Field.DATE, "вчера");

        assertThat(result.status()).isEqualTo(UpdateResult.Status.INVALID);
        assertThat(result.error()).contains("дату");
        assertThat(documents.findById(doc.getId()).orElseThrow().getDocDate()).isNull();
    }

    @Test
    void parsesAmountWithSpacesCommaAndCurrency() {
        Document doc = savedDoc("amt");

        UpdateResult result = service.updateField(doc.getId(), Field.AMOUNT, "4 559,00 руб");

        assertThat(result.isOk()).isTrue();
        assertThat(documents.findById(doc.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo(new BigDecimal("4559.00"));
    }

    @Test
    void rejectsBadAmount() {
        Document doc = savedDoc("amt-bad");

        UpdateResult result = service.updateField(doc.getId(), Field.AMOUNT, "много");

        assertThat(result.status()).isEqualTo(UpdateResult.Status.INVALID);
        assertThat(documents.findById(doc.getId()).orElseThrow().getAmount()).isNull();
    }

    @Test
    void blankValueClearsField() {
        Document doc = savedDoc("clr");
        doc.setDocType("чек");
        documents.save(doc);

        UpdateResult result = service.updateField(doc.getId(), Field.DOC_TYPE, "   ");

        assertThat(result.isOk()).isTrue();
        assertThat(documents.findById(doc.getId()).orElseThrow().getDocType()).isNull();
    }

    @Test
    void clearFieldSetsNull() {
        Document doc = savedDoc("clr2");
        doc.setAmount(new BigDecimal("100.00"));
        documents.save(doc);

        service.clearField(doc.getId(), Field.AMOUNT);

        assertThat(documents.findById(doc.getId()).orElseThrow().getAmount()).isNull();
    }

    @Test
    void currentValueRendersAmountAndEmptyForNull() {
        Document doc = new Document();
        doc.setAmount(new BigDecimal("4559.00"));

        assertThat(service.currentValue(doc, Field.AMOUNT)).isEqualTo("4559.00");
        assertThat(service.currentValue(doc, Field.DATE)).isEmpty();
    }

    @Test
    void notFoundForUnknownId() {
        assertThat(service.updateField(999_999L, Field.DOC_TYPE, "чек").status())
                .isEqualTo(UpdateResult.Status.NOT_FOUND);
    }

    private Document savedDoc(String hash) {
        Document doc = new Document();
        doc.setContentHash(hash);
        return documents.save(doc);
    }
}
