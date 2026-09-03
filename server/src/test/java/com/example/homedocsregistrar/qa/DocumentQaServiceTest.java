package com.example.homedocsregistrar.qa;

import com.example.homedocsregistrar.domain.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure, network-free core of the Q&A service: context rendering and citation resolution. */
class DocumentQaServiceTest {

    @Test
    void buildContextRendersIdAndPresentFieldsOnly() {
        Document doc = new Document();
        ReflectionTestUtils.setField(doc, "id", 12L);
        doc.setDocType("гарантия");
        doc.setTitle("Холодильник Bosch");
        doc.setWarrantyUntil(LocalDate.parse("2027-03-01"));
        doc.setOcrText("Гарантийный талон на холодильник");
        // counterparty / amount / number left null -> their lines must be omitted

        String context = DocumentQaService.buildContext(List.of(doc), 2000);

        assertThat(context).contains("Документ #12");
        assertThat(context).contains("Тип: гарантия");
        assertThat(context).contains("Название: Холодильник Bosch");
        assertThat(context).contains("Гарантия до: 2027-03-01");
        assertThat(context).contains("Текст: Гарантийный талон на холодильник");
        assertThat(context).doesNotContain("Контрагент");
        assertThat(context).doesNotContain("Сумма");
        assertThat(context).doesNotContain("Номер");
    }

    @Test
    void buildContextTruncatesLongText() {
        Document doc = new Document();
        ReflectionTestUtils.setField(doc, "id", 1L);
        doc.setOcrText("абвгдеёжзи"); // 10 chars

        String context = DocumentQaService.buildContext(List.of(doc), 4);

        assertThat(context).contains("Текст: абвг…");
        assertThat(context).doesNotContain("абвгд");
    }

    @Test
    void resolveSourcesKeepsCitedDocsInContextOrder() {
        Document a = docWithId(1L);
        Document b = docWithId(2L);
        Document c = docWithId(3L);
        List<Document> context = List.of(a, b, c);

        // Cited out of order and with an id that isn't in context (invented by the model) -> ignored.
        List<Document> sources = DocumentQaService.resolveSources(context, List.of(3L, 1L, 99L));

        assertThat(sources).containsExactly(a, c); // context (relevance) order, not the cited order
    }

    @Test
    void resolveSourcesEmptyWhenNothingCited() {
        List<Document> context = List.of(docWithId(1L));

        assertThat(DocumentQaService.resolveSources(context, List.of())).isEmpty();
        assertThat(DocumentQaService.resolveSources(context, null)).isEmpty();
    }

    private static Document docWithId(long id) {
        Document doc = new Document();
        ReflectionTestUtils.setField(doc, "id", id);
        return doc;
    }
}
