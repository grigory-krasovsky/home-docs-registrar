package com.example.homedocsregistrar.qa;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.example.homedocsregistrar.ai.AnthropicClients;
import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.extraction.ApiUsageTracker;
import com.example.homedocsregistrar.search.DocumentSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Answers a free-text question about the archive by retrieval-augmented generation: it reuses the
 * full-text search ({@link DocumentSearchService}) to pull the few most relevant documents, then asks
 * Claude to answer <em>only</em> from their text/fields and cite the documents it relied on. Grounding
 * the model on the retrieved text (and forbidding outside knowledge) keeps answers factual and lets the
 * UI attach the source files. Best-effort: disabled (no key / {@code qa.enabled=false}) or any error
 * degrades gracefully — the bot then tells the user Q&A is unavailable and falls back to plain search.
 */
@Service
public class DocumentQaService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQaService.class);

    /** How many top hits to feed the model as context, and how much of each document's text to include. */
    private static final int CONTEXT_DOCS = 5;
    private static final int MAX_TEXT_PER_DOC = 2000;

    private static final String SYSTEM = """
            Ты — помощник по личному архиву бумажных документов (чеки, договоры, гарантии,
            свидетельства, счета и т.п.). Отвечай на вопрос пользователя, опираясь ТОЛЬКО на
            приведённые ниже документы. Не додумывай факты, которых в документах нет, и не используй
            общие знания.
            - Если в документах есть ответ: found=true, дай краткий и точный ответ по-русски (называй
              конкретику: даты, суммы, номера, стороны) и перечисли в sourceDocIds номера документов
              (#id), на которых основан ответ.
            - Если ответа в документах нет: found=false, в answer коротко напиши, что в документах это
              не найдено, а sourceDocIds оставь пустым.""";

    private final AnthropicClient client;
    private final String model;
    private final boolean enabled;
    private final DocumentSearchService searchService;
    private final ApiUsageTracker usageTracker;

    public DocumentQaService(@Value("${anthropic.api-key:}") String apiKey,
                             @Value("${anthropic.workspace-id:}") String workspaceId,
                             @Value("${anthropic.model:claude-haiku-4-5}") String model,
                             @Value("${qa.enabled:true}") boolean enabled,
                             DocumentSearchService searchService,
                             ApiUsageTracker usageTracker) {
        this.client = AnthropicClients.build(apiKey, workspaceId);
        this.model = model;
        this.enabled = enabled;
        this.searchService = searchService;
        this.usageTracker = usageTracker;
    }

    public boolean isEnabled() {
        return enabled && client != null;
    }

    /**
     * Answer a question over the stored documents. Retrieval reuses the ranked FTS search (including its
     * query expansion), then the top hits are handed to Claude as grounding. Never throws: an unavailable
     * service, no matches, or a model error all return a well-formed {@link QaResult} the bot can render.
     */
    public QaResult ask(String question) {
        if (!isEnabled()) {
            return QaResult.unavailable();
        }
        if (question == null || question.isBlank()) {
            return QaResult.notFound("Задайте вопрос по вашим документам.", List.of());
        }
        // Retrieve on the question's content words first — stripping interrogatives («сколько/стоит»)
        // that no document contains and would otherwise AND away the real match. Fall back to the full
        // ranked search (with its LLM query expansion) only when the keyword search finds nothing.
        List<String> keywords = QuestionKeywords.keywords(question);
        DocumentSearchService.SearchResult search = keywords.isEmpty()
                ? new DocumentSearchService.SearchResult(List.of(), List.of())
                : searchService.searchAny(keywords);
        if (search.hits().isEmpty()) {
            search = searchService.search(question);
        }
        List<Document> hits = search.hits();
        List<String> relatedTerms = search.relatedTerms();
        if (hits.isEmpty()) {
            return QaResult.notFound("По вашим документам ничего не нашёл для этого вопроса.", relatedTerms);
        }
        List<Document> context = hits.size() > CONTEXT_DOCS ? hits.subList(0, CONTEXT_DOCS) : hits;
        Optional<QaAnswer> answer = generate(question, context);
        if (answer.isEmpty()) {
            return QaResult.error(relatedTerms);
        }
        QaAnswer parsed = answer.get();
        boolean found = Boolean.TRUE.equals(parsed.found());
        String text = parsed.answer() == null || parsed.answer().isBlank()
                ? (found ? "Нашёл, но не смог сформулировать ответ." : "В документах ответа не нашёл.")
                : parsed.answer().trim();
        // Show source buttons only for a positive answer; if the model cited nothing, fall back to the
        // documents that were actually in context so the user can still open what the answer came from.
        List<Document> sources = List.of();
        if (found) {
            sources = resolveSources(context, parsed.sourceDocIds());
            if (sources.isEmpty()) {
                sources = context;
            }
        }
        return new QaResult(true, found, text, sources, relatedTerms);
    }

    /** One grounded answer call; empty on any error so the caller degrades gracefully. */
    private Optional<QaAnswer> generate(String question, List<Document> context) {
        String userMessage = "Вопрос: " + question.trim() + "\n\nДокументы:\n"
                + buildContext(context, MAX_TEXT_PER_DOC);
        try {
            StructuredMessageCreateParams<QaAnswer> params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .system(SYSTEM)
                    .outputConfig(QaAnswer.class)
                    .addUserMessage(userMessage)
                    .build();
            StructuredMessage<QaAnswer> response = client.messages().create(params);
            recordUsage(response.usage().inputTokens(), response.usage().outputTokens());
            return response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("Q&A generation failed for '{}'", question, e);
            return Optional.empty();
        }
    }

    private void recordUsage(long inputTokens, long outputTokens) {
        try {
            usageTracker.record(inputTokens, outputTokens, model);
        } catch (RuntimeException e) {
            log.warn("Failed to record API usage", e);
        }
    }

    /**
     * Render the retrieved documents as plain-text context: id (so the model can cite it) plus the fields
     * we hold and the transcribed text, truncated per document to keep the prompt small. Only scalar
     * fields are read (no lazy JPA associations), so this is safe to call outside a transaction.
     */
    static String buildContext(List<Document> docs, int maxTextChars) {
        StringBuilder context = new StringBuilder();
        for (Document doc : docs) {
            context.append("Документ #").append(doc.getId()).append('\n');
            appendField(context, "Тип", doc.getDocType());
            appendField(context, "Название", doc.getTitle());
            appendField(context, "Дата", doc.getDocDate() == null ? null : doc.getDocDate().toString());
            appendField(context, "Номер", doc.getDocumentNumber());
            appendField(context, "Контрагент", doc.getCounterparty());
            appendField(context, "Сумма", doc.getAmount() == null ? null : doc.getAmount().toPlainString());
            appendField(context, "Гарантия до",
                    doc.getWarrantyUntil() == null ? null : doc.getWarrantyUntil().toString());
            String text = doc.getOcrText();
            if (text != null && !text.isBlank()) {
                appendField(context, "Текст", truncate(text.strip(), maxTextChars));
            }
            context.append('\n');
        }
        return context.toString().strip();
    }

    private static void appendField(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * The documents the model cited, restricted to those actually in context (ids the model invented are
     * ignored) and de-duplicated, preserving the context (relevance) order. Empty when nothing was cited.
     */
    static List<Document> resolveSources(List<Document> context, List<Long> citedIds) {
        if (citedIds == null || citedIds.isEmpty()) {
            return List.of();
        }
        return context.stream()
                .filter(doc -> citedIds.contains(doc.getId()))
                .distinct()
                .toList();
    }

    /** Service answer for the bot to render: whether Q&A ran, whether it found an answer, text + sources. */
    public record QaResult(boolean available, boolean found, String answer,
                           List<Document> sources, List<String> relatedTerms) {

        static QaResult unavailable() {
            return new QaResult(false, false, null, List.of(), List.of());
        }

        static QaResult notFound(String message, List<String> relatedTerms) {
            return new QaResult(true, false, message, List.of(), relatedTerms);
        }

        static QaResult error(List<String> relatedTerms) {
            return new QaResult(true, false,
                    "Не удалось получить ответ. Попробуйте ещё раз или поищите по словам.", List.of(), relatedTerms);
        }
    }
}
