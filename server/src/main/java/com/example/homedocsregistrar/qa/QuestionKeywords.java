package com.example.homedocsregistrar.qa;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a natural-language question into the content words to search for, dropping interrogatives and
 * pronouns/prepositions. This matters because the FTS treats a query as an AND of its words: a question
 * like «сколько стоит пила?» would require «сколько» AND «стоит» — words no document contains — and so
 * miss the very receipt it asks about. Stripping them leaves «пила», which matches directly. Free (no
 * LLM); the Q&A retrieval falls back to the full ranked search (with its query expansion) if this finds
 * nothing.
 */
public final class QuestionKeywords {

    private QuestionKeywords() {
    }

    /**
     * Question words / pronouns / prepositions / auxiliaries to drop. Many are already Postgres russian
     * stopwords (ignored by the FTS anyway); the ones that matter here are the survivors — «сколько»,
     * «стоит» and its forms — which otherwise AND away the real match.
     */
    private static final Set<String> STOP = Set.of(
            "сколько", "стоит", "стоил", "стоила", "стоило", "стоят",
            "обошлось", "обошлась", "обошёлся", "обошелся",
            "какой", "какая", "какое", "какие", "каком", "каких", "какую", "каким",
            "когда", "где", "куда", "откуда", "кто", "кого", "кому", "кем",
            "что", "чего", "чем", "чём", "чей", "чья", "чьё", "чьи",
            "почему", "зачем", "отчего", "как", "ли", "разве", "неужели",
            "я", "мне", "меня", "мной", "мой", "моя", "моё", "мои", "мы", "нам", "нас",
            "ты", "тебя", "вы", "вам", "ваш",
            "он", "она", "оно", "они", "его", "её", "их",
            "это", "этот", "эта", "эти", "тот", "та", "те",
            "у", "в", "во", "на", "по", "за", "о", "об", "обо", "до", "для", "из", "с", "со",
            "от", "к", "ко", "над", "под", "при", "про", "без", "через", "между",
            "и", "а", "но", "или", "же", "не", "ни", "да",
            "есть", "был", "была", "было", "были", "быть",
            "надо", "нужно", "хочу");

    /**
     * The distinct content words of a question, lowercased, in order, with stop-words and 1-character
     * tokens removed. Empty when the question is only stop-words (the caller then falls back).
     */
    public static List<String> keywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String token : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (token.length() > 1 && !STOP.contains(token)) {
                words.add(token);
            }
        }
        return List.copyOf(words);
    }
}
