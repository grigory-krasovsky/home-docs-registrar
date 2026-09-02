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
}
