package com.example.homedocsregistrar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single running total of Anthropic token usage (one row, id = {@link #SINGLETON_ID}). Persisted so
 * the counter survives redeploys/restarts. Anthropic exposes no API for the remaining prepaid balance,
 * so this cumulative token count is the spend gauge (see the log line in {@code ApiUsageTracker}).
 */
@Entity
@Table(name = "api_usage")
public class ApiUsage {

    /** Fixed id of the one and only counter row. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    public ApiUsage() { // for JPA
    }

    public ApiUsage(long id, long inputTokens, long outputTokens) {
        this.id = id;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public Long getId() {
        return id;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }
}
