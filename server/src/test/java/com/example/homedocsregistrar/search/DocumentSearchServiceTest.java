package com.example.homedocsregistrar.search;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import com.example.homedocsregistrar.search.DocumentSearchService.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentSearchServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final QueryExpansionService expansion = mock(QueryExpansionService.class);
    private final DocumentSearchService search = new DocumentSearchService(documents, expansion);

    @Test
    void literalHitReturnsWithoutExpanding() {
        // The literal query already matches -> stay precise, don't expand (no sibling pollution).
        when(documents.search(eq("паспорт"), anyInt())).thenReturn(List.of(new Document()));

        SearchResult result = search.search("паспорт");

        assertThat(result.hits()).hasSize(1);
        assertThat(result.relatedTerms()).isEmpty();
        verifyNoInteractions(expansion);
    }

    @Test
    void expandsOnlyWhenLiteralQueryFindsNothing() {
        Document match = new Document();
        when(documents.search(eq("свадьба"), anyInt())).thenReturn(List.of());          // literal: nothing
        when(expansion.relatedTerms("свадьба")).thenReturn(List.of("брак"));
        when(documents.search(eq("свадьба or брак"), anyInt())).thenReturn(List.of(match)); // expanded: hit

        SearchResult result = search.search("свадьба");

        assertThat(result.hits()).containsExactly(match);
        assertThat(result.relatedTerms()).containsExactly("брак");
    }
}
