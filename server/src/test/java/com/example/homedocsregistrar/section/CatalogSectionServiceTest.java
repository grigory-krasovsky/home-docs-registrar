package com.example.homedocsregistrar.section;

import com.example.homedocsregistrar.domain.CatalogSection;
import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.CatalogSectionRepository;
import com.example.homedocsregistrar.repository.DocumentRepository;
import com.example.homedocsregistrar.section.CatalogSectionService.Assignment;
import com.example.homedocsregistrar.section.SectionSuggestionService.Suggestion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CatalogSectionServiceTest {

    @Autowired
    private CatalogSectionService service;

    @Autowired
    private CatalogSectionRepository sections;

    @Autowired
    private DocumentRepository documents;

    @Test
    void getOrCreateSubsectionMergesCaseInsensitiveNearDuplicate() {
        CatalogSection grisha = sections.save(new CatalogSection(null, "Гриша"));
        CatalogSection med = sections.save(new CatalogSection("Медицина и здоровье", grisha));

        // Same name, different case -> reuse the existing subsection instead of inflating the catalog.
        CatalogSection resolved = service.getOrCreateSubsection(grisha, "медицина и здоровье");

        assertThat(resolved.getId()).isEqualTo(med.getId());
        assertThat(service.subsections(grisha)).hasSize(1);
    }

    @Test
    void resolveSuggestionCreatesNewSubsectionUnderExistingOwner() {
        CatalogSection obshaya = sections.save(new CatalogSection(null, "Общая"));
        sections.save(new CatalogSection("Авто", obshaya));

        Optional<CatalogSection> leaf = service.resolveSuggestion(new Suggestion("Общая", "Гарантии"));

        assertThat(leaf).isPresent();
        assertThat(leaf.get().getLabel()).isEqualTo("Гарантии");
        assertThat(leaf.get().getParent().getId()).isEqualTo(obshaya.getId());
        assertThat(service.subsections(obshaya)).extracting(CatalogSection::getLabel)
                .containsExactlyInAnyOrder("Авто", "Гарантии");
    }

    @Test
    void resolveSuggestionEmptyWhenOwnerUnknown() {
        assertThat(service.resolveSuggestion(new Suggestion("Гриша", "Личное"))).isEmpty();
    }

    @Test
    void leafPathsRenderOwnerSlashSub() {
        CatalogSection grisha = sections.save(new CatalogSection(null, "Гриша"));
        sections.save(new CatalogSection("Личное", grisha));
        sections.save(new CatalogSection("Медицина и здоровье", grisha));

        // Leaf paths are rendered strings (parent loaded in-tx) — what the suggester consumes.
        assertThat(service.leafPaths()).containsExactly("Гриша / Личное", "Гриша / Медицина и здоровье");
    }

    @Test
    void recentDocumentsListsNewestFirstWithSectionMarkAndPaging() {
        CatalogSection obshaya = sections.save(new CatalogSection(null, "Общая"));
        CatalogSection avto = sections.save(new CatalogSection("Авто", obshaya));

        Document older = new Document();
        older.setTitle("Старый");
        older.setContentHash("h-old");
        older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        documents.save(older);

        Document newer = new Document();
        newer.setTitle("Новый");
        newer.setContentHash("h-new");
        newer.setCreatedAt(Instant.parse("2026-02-01T00:00:00Z"));
        documents.save(newer);
        service.assign(newer.getId(), avto.getId());

        CatalogSectionService.DocPage page0 = service.recentDocuments(0, 8);
        assertThat(page0.items()).extracting(CatalogSectionService.DocDigest::title)
                .containsExactly("Новый", "Старый"); // newest first
        assertThat(page0.items().get(0).sectionPath()).isEqualTo("Общая / Авто");
        assertThat(page0.items().get(1).sectionPath()).isNull(); // unfiled
        assertThat(page0.hasNext()).isFalse();

        // Paging: one per page -> the first page has a next, the second doesn't.
        assertThat(service.recentDocuments(0, 1).hasNext()).isTrue();
        assertThat(service.recentDocuments(1, 1).items()).extracting(CatalogSectionService.DocDigest::title)
                .containsExactly("Старый");
    }

    @Test
    void ownersOfResolvesTopLevelLabelPerDocument() {
        CatalogSection masha = sections.save(new CatalogSection(null, "Маша"));
        CatalogSection lichnoe = sections.save(new CatalogSection("Личное", masha));

        Document filed = new Document();
        filed.setContentHash("own-1");
        filed.setSection(lichnoe); // filed in a leaf -> owner is the parent «Маша»
        documents.save(filed);

        Document unfiled = new Document();
        unfiled.setContentHash("own-2");
        documents.save(unfiled); // no section -> absent from the map

        var owners = service.ownersOf(List.of(filed.getId(), unfiled.getId()));

        assertThat(owners).containsEntry(filed.getId(), "Маша");
        assertThat(owners).doesNotContainKey(unfiled.getId());
    }

    @Test
    void assignFilesDocumentAndReportsPath() {
        CatalogSection obshaya = sections.save(new CatalogSection(null, "Общая"));
        CatalogSection avto = sections.save(new CatalogSection("Авто", obshaya));
        Document doc = new Document();
        doc.setContentHash("hash-1");
        documents.save(doc);

        assertThat(service.currentSectionPath(doc.getId())).isEmpty(); // not filed yet

        Optional<Assignment> assignment = service.assign(doc.getId(), avto.getId());

        assertThat(assignment).isPresent();
        assertThat(assignment.get().sectionPath()).isEqualTo("Общая / Авто");
        assertThat(service.currentSectionPath(doc.getId())).contains("Общая / Авто");
    }

    @Test
    void browseListsDocumentsBySectionUnfiledAndCounts() {
        CatalogSection obshaya = sections.save(new CatalogSection(null, "Общая"));
        CatalogSection avto = sections.save(new CatalogSection("Авто", obshaya));

        Document filed = new Document();
        filed.setTitle("Страховка");
        filed.setContentHash("br-1");
        filed.setSection(avto);
        documents.save(filed);

        Document unfiled = new Document();
        unfiled.setTitle("Ничей чек");
        unfiled.setContentHash("br-2");
        documents.save(unfiled);

        assertThat(service.countInSection(avto.getId())).isEqualTo(1);
        assertThat(service.countUnfiled()).isEqualTo(1);
        assertThat(service.documentsInSection(avto.getId(), 0, 8).items())
                .extracting(CatalogSectionService.DocDigest::title).containsExactly("Страховка");
        assertThat(service.unfiledDocuments(0, 8).items())
                .extracting(CatalogSectionService.DocDigest::title).containsExactly("Ничей чек");
        assertThat(service.sectionPath(avto.getId())).contains("Общая / Авто");

        var counts = service.subsectionCounts(obshaya.getId());
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).label()).isEqualTo("Авто");
        assertThat(counts.get(0).count()).isEqualTo(1L);
    }
}
