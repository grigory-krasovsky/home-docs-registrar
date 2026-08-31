package com.example.homedocsregistrar.extraction;

import com.example.homedocsregistrar.domain.ApiUsage;
import com.example.homedocsregistrar.repository.ApiUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a persistent running total of Anthropic token usage and logs the per-call and cumulative
 * figures after each vision extraction. Anthropic exposes no API for the remaining prepaid balance,
 * so this cumulative token count is the spend gauge. Best-effort — the caller must not let a failure
 * here discard a successful extraction.
 */
@Component
public class ApiUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ApiUsageTracker.class);

    private final ApiUsageRepository usage;

    public ApiUsageTracker(ApiUsageRepository usage) {
        this.usage = usage;
    }

    /** Add one call's token counts to the persistent total and log per-call + cumulative usage. */
    @Transactional
    public void record(long inputTokens, long outputTokens, String model) {
        ApiUsage total;
        if (usage.addUsage(ApiUsage.SINGLETON_ID, inputTokens, outputTokens) == 0) {
            // First call ever: the counter row doesn't exist yet, so seed it with this call's counts.
            total = usage.save(new ApiUsage(ApiUsage.SINGLETON_ID, inputTokens, outputTokens));
        } else {
            total = usage.findById(ApiUsage.SINGLETON_ID).orElse(null);
        }
        long inTotal = total != null ? total.getInputTokens() : inputTokens;
        long outTotal = total != null ? total.getOutputTokens() : outputTokens;
        log.info("Vision usage: in={} out={} (model={}); cumulative in={} out={} total={} tokens",
                inputTokens, outputTokens, model, inTotal, outTotal, inTotal + outTotal);
    }
}
