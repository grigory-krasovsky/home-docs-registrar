package com.example.homedocsregistrar.search;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.example.homedocsregistrar.ai.AnthropicClients;
import com.example.homedocsregistrar.extraction.ApiUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Expands a search query into semantically related Russian terms with a small Claude call, so a search
 * for «свадьба» also matches a «свидетельство о браке» that never contains the word «свадьба». Lexical
 * FTS only matches word forms of the same word; this bridges synonyms/related concepts. Best-effort:
 * when disabled (no key / {@code search.expand-query=false}) or on any error it returns no extra terms
 * and the caller falls back to a plain search.
 */
@Service
public class QueryExpansionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExpansionService.class);

    private static final int MAX_TERMS = 10;

    private static final String SYSTEM = """
            Ты помогаешь искать бумажные документы (чеки, договоры, гарантии, свидетельства и т.п.).
            По запросу пользователя верни связанные по смыслу русские слова и короткие словосочетания —
            синонимы, близкие термины, слова, которые реально встречаются в тексте таких документов.
            Только термины, каждый с новой строки, без нумерации, кавычек и пояснений. Не более 10.""";

    private final AnthropicClient client;
    private final String model;
    private final boolean enabled;
    private final ApiUsageTracker usageTracker;

    public QueryExpansionService(@Value("${anthropic.api-key:}") String apiKey,
                                 @Value("${anthropic.workspace-id:}") String workspaceId,
                                 @Value("${anthropic.model:claude-haiku-4-5}") String model,
                                 @Value("${search.expand-query:true}") boolean enabled,
                                 ApiUsageTracker usageTracker) {
        this.client = AnthropicClients.build(apiKey, workspaceId);
        this.model = model;
        this.enabled = enabled;
        this.usageTracker = usageTracker;
    }

    public boolean isEnabled() {
        return enabled && client != null;
    }

    /** Related Russian terms for the query (never including the original); empty when disabled or on error. */
    public List<String> relatedTerms(String query) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(256L)
                    .system(SYSTEM)
                    .addUserMessage(query)
                    .build();
            Message response = client.messages().create(params);
            recordUsage(response);
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .collect(Collectors.joining("\n"));
            return parseTerms(text, query);
        } catch (RuntimeException e) {
            log.warn("Query expansion failed for '{}'", query, e);
            return List.of();
        }
    }

    private void recordUsage(Message response) {
        try {
            usageTracker.record(response.usage().inputTokens(), response.usage().outputTokens(), model);
        } catch (RuntimeException e) {
            log.warn("Failed to record API usage", e);
        }
    }

    /** Split the model's lines into clean terms, dropping bullets/numbering and the original query. */
    private static List<String> parseTerms(String text, String original) {
        String originalNormalized = original.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(text.split("[\\r\\n,;]+"))
                .map(line -> line.replaceAll("^[\\d.)\\-•*\\s\"]+", "").replace("\"", "").trim())
                .filter(term -> !term.isBlank())
                .filter(term -> !term.toLowerCase(Locale.ROOT).equals(originalNormalized))
                .distinct()
                .limit(MAX_TERMS)
                .toList();
    }
}
