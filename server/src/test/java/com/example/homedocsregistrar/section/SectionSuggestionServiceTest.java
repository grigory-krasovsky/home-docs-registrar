package com.example.homedocsregistrar.section;

import com.example.homedocsregistrar.section.SectionSuggestionService.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of the model's «Владелец / Подсекция» reply — the pure, network-free core of the suggester. */
class SectionSuggestionServiceTest {

    private static final Set<String> OWNERS = Set.of("Гриша", "Маша", "Костя", "Общая");

    @Test
    void parsesExistingLeafPath() {
        Optional<Suggestion> parsed = SectionSuggestionService.parseSuggestion("Гриша / Медицина и здоровье", OWNERS);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().owner()).isEqualTo("Гриша");
        assertThat(parsed.get().sub()).isEqualTo("Медицина и здоровье");
        assertThat(parsed.get().path()).isEqualTo("Гриша / Медицина и здоровье");
    }

    @Test
    void parsesProposedNewSubsectionUnderExistingOwner() {
        Optional<Suggestion> parsed = SectionSuggestionService.parseSuggestion("Общая / Питомцы", OWNERS);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().owner()).isEqualTo("Общая");
        assertThat(parsed.get().sub()).isEqualTo("Питомцы");
    }

    @Test
    void canonicalizesOwnerCaseAndStripsBullets() {
        Optional<Suggestion> parsed = SectionSuggestionService.parseSuggestion("1. гриша / Личное", OWNERS);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().owner()).isEqualTo("Гриша"); // canonical casing from the owner set
        assertThat(parsed.get().sub()).isEqualTo("Личное");
    }

    @Test
    void rejectsInventedOwner() {
        // The model must not invent a new top-level owner; such a reply yields no suggestion.
        assertThat(SectionSuggestionService.parseSuggestion("Вася / Личное", OWNERS)).isEmpty();
    }

    @Test
    void emptyWhenNoPath() {
        assertThat(SectionSuggestionService.parseSuggestion("не знаю", OWNERS)).isEmpty();
        assertThat(SectionSuggestionService.parseSuggestion(null, OWNERS)).isEmpty();
    }
}
