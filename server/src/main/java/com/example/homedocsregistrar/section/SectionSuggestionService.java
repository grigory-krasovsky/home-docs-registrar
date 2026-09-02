package com.example.homedocsregistrar.section;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.example.homedocsregistrar.ai.AnthropicClients;
import com.example.homedocsregistrar.domain.CatalogSection;
import com.example.homedocsregistrar.extraction.ApiUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Suggests which card-catalog subsection a freshly recognized document belongs to, with a small
 * Claude text call (no image — cheaper and keeps the recognition prompt stable). The catalog is a
 * two-level tree «Владелец / Подсекция» (people + «Общая»); the model must return one existing leaf
 * path, or propose a NEW subsection under an existing owner when none fit — so the section count
 * doesn't inflate. Best-effort: disabled (no key / {@code sections.suggest=false}) or any error ->
 * empty, and the bot just shows the picker without a pre-selection.
 */
@Service
public class SectionSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SectionSuggestionService.class);

    private static final String SYSTEM = """
            Ты раскладываешь бумажные документы семьи по картотеке. Картотека двухуровневая:
            верхний уровень — ВЛАДЕЛЕЦ (люди по именам или «Общая» для общих/бытовых документов),
            внутри — ПОДСЕКЦИЯ (категория). Тебе дают список допустимых путей «Владелец / Подсекция».
            Определи, куда положить документ, и верни РОВНО ОДИН путь из списка — строкой
            «Владелец / Подсекция», без пояснений.
            Как выбирать владельца: если в тексте есть ФИО, сопоставь с человеком из списка
            (учитывай уменьшительные: Григорий→Гриша, Мария→Маша, Константин→Костя). Личные и
            медицинские документы относятся к конкретному человеку. Общие/бытовые (ЖКХ, гарантии,
            чеки на технику, финансы, автомобиль, инструкции) — к владельцу «Общая».
            Если ни одна существующая подсекция не подходит, предложи НОВУЮ подсекцию под подходящим
            СУЩЕСТВУЮЩИМ владельцем в том же формате «Владелец / Новое-название». Новых владельцев не
            придумывай. Не раздувай картотеку — предпочитай существующую подсекцию.""";

    private final AnthropicClient client;
    private final String model;
    private final boolean enabled;
    private final ApiUsageTracker usageTracker;

    public SectionSuggestionService(@Value("${anthropic.api-key:}") String apiKey,
                                    @Value("${anthropic.workspace-id:}") String workspaceId,
                                    @Value("${anthropic.model:claude-haiku-4-5}") String model,
                                    @Value("${sections.suggest:true}") boolean enabled,
                                    ApiUsageTracker usageTracker) {
        this.client = AnthropicClients.build(apiKey, workspaceId);
        this.model = model;
        this.enabled = enabled;
        this.usageTracker = usageTracker;
    }

    public boolean isEnabled() {
        return enabled && client != null;
    }

    /**
     * Best subsection for a document, given its short summary and the current leaf sections. Empty when
     * disabled, when there are no sections to choose from, or on any error/unparseable reply.
     */
    public Optional<Suggestion> suggest(String documentSummary, List<CatalogSection> leaves) {
        if (!isEnabled() || documentSummary == null || documentSummary.isBlank() || leaves == null || leaves.isEmpty()) {
            return Optional.empty();
        }
        Set<String> owners = leaves.stream()
                .map(leaf -> leaf.getParent() == null ? leaf.getLabel() : leaf.getParent().getLabel())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String paths = leaves.stream().map(CatalogSection::path).distinct().collect(Collectors.joining("\n"));
        String userMessage = "Допустимые пути:\n" + paths + "\n\nДокумент:\n" + documentSummary;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(64L)
                    .system(SYSTEM)
                    .addUserMessage(userMessage)
                    .build();
            Message response = client.messages().create(params);
            recordUsage(response);
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .collect(Collectors.joining("\n"));
            return parseSuggestion(text, owners);
        } catch (RuntimeException e) {
            log.warn("Section suggestion failed", e);
            return Optional.empty();
        }
    }

    private void recordUsage(Message response) {
        try {
            usageTracker.record(response.usage().inputTokens(), response.usage().outputTokens(), model);
        } catch (RuntimeException e) {
            log.warn("Failed to record API usage", e);
        }
    }

    /**
     * Parse a «Владелец / Подсекция» line into a suggestion, accepting only owners that already exist
     * (case-insensitive) so the model can't invent new top-level sections. Empty if unparseable.
     */
    static Optional<Suggestion> parseSuggestion(String modelText, Set<String> validOwners) {
        if (modelText == null) {
            return Optional.empty();
        }
        for (String line : modelText.split("[\\r\\n]+")) {
            int slash = line.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String owner = clean(line.substring(0, slash));
            String sub = clean(line.substring(slash + 1));
            if (owner.isBlank() || sub.isBlank()) {
                continue;
            }
            String canonicalOwner = validOwners.stream()
                    .filter(o -> o.equalsIgnoreCase(owner))
                    .findFirst()
                    .orElse(null);
            if (canonicalOwner != null) {
                return Optional.of(new Suggestion(canonicalOwner, sub));
            }
        }
        return Optional.empty();
    }

    /** Strip leading bullets/numbering/quotes and surrounding whitespace from a term. */
    private static String clean(String value) {
        return value.replaceAll("^[\\d.)\\-•*\\s\"]+", "").replace("\"", "").trim();
    }

    /** A proposed filing: an existing owner label plus a subsection name (which may be new). */
    public record Suggestion(String owner, String sub) {

        public String path() {
            return owner + " / " + sub;
        }

        public boolean matchesOwner(String ownerLabel) {
            return owner.equalsIgnoreCase(ownerLabel);
        }

        public boolean matchesSub(String subLabel) {
            return sub.equalsIgnoreCase(subLabel);
        }
    }
}
