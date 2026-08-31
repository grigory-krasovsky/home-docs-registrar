package com.example.homedocsregistrar.extraction;

/**
 * One vision extraction: the parsed {@link ExtractedFields} plus the token usage that call cost, so
 * callers can show a per-document token status alongside the cumulative total.
 */
public record Extraction(ExtractedFields fields, long inputTokens, long outputTokens) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
