package com.example.homedocsregistrar.extraction;

import java.util.List;
import java.util.Objects;

/**
 * One vision extraction: the parsed {@link ExtractedFields}, the reminder deadlines the model suggested
 * (empty unless asked for), plus the token usage that call cost, so callers can show a per-document token
 * status alongside the cumulative total.
 */
public record Extraction(ExtractedFields fields, List<ReminderHint> reminderHints, long inputTokens, long outputTokens) {

    public Extraction {
        reminderHints = reminderHints == null ? List.of() : reminderHints.stream().filter(Objects::nonNull).toList();
    }

    public Extraction(ExtractedFields fields, long inputTokens, long outputTokens) {
        this(fields, List.of(), inputTokens, outputTokens);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
