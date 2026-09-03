package com.example.homedocsregistrar.qa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Stripping a question down to the content words the FTS should look for. */
class QuestionKeywordsTest {

    @Test
    void dropsInterrogativesLeavingTheSubject() {
        // «сколько» and «стоит» are the survivors that would otherwise AND away the real match.
        assertThat(QuestionKeywords.keywords("сколько стоит пила?")).containsExactly("пила");
    }

    @Test
    void keepsContentWordsInOrderAndLowercased() {
        assertThat(QuestionKeywords.keywords("Когда истекает гарантия на холодильник?"))
                .containsExactly("истекает", "гарантия", "холодильник");
    }

    @Test
    void deduplicates() {
        assertThat(QuestionKeywords.keywords("пила и пила")).containsExactly("пила");
    }

    @Test
    void emptyWhenOnlyStopWords() {
        assertThat(QuestionKeywords.keywords("сколько это стоит?")).isEmpty();
        assertThat(QuestionKeywords.keywords("  ")).isEmpty();
        assertThat(QuestionKeywords.keywords(null)).isEmpty();
    }

    @Test
    void dropsSingleCharacterTokens() {
        assertThat(QuestionKeywords.keywords("чек на 5 пил")).containsExactly("чек", "пил");
    }
}
