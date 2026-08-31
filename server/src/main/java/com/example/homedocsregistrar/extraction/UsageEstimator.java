package com.example.homedocsregistrar.extraction;

import com.example.homedocsregistrar.extraction.ApiUsageTracker.Totals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Estimates USD spend and the remaining prepaid pool from token totals, using configured per-MTok
 * prices and the pool size. Anthropic has no balance API, so this is an estimate: the token counter
 * only covers spend since it was deployed, and the prices must match the model in use. When no pool is
 * configured ({@code anthropic.pool-usd} <= 0) the remaining estimate is disabled.
 */
@Component
public class UsageEstimator {

    private final double poolUsd;
    private final double inputPricePerMTok;
    private final double outputPricePerMTok;

    public UsageEstimator(@Value("${anthropic.pool-usd:0}") double poolUsd,
                          @Value("${anthropic.price.input-per-mtok:1.0}") double inputPricePerMTok,
                          @Value("${anthropic.price.output-per-mtok:5.0}") double outputPricePerMTok) {
        this.poolUsd = poolUsd;
        this.inputPricePerMTok = inputPricePerMTok;
        this.outputPricePerMTok = outputPricePerMTok;
    }

    /** True when a pool size is configured, so a remaining-balance estimate is meaningful. */
    public boolean hasPool() {
        return poolUsd > 0;
    }

    /** Estimated USD spent for the given cumulative token totals. */
    public double spentUsd(Totals totals) {
        return totals.inputTokens() / 1_000_000.0 * inputPricePerMTok
                + totals.outputTokens() / 1_000_000.0 * outputPricePerMTok;
    }

    /** Estimated USD left in the pool (never negative). */
    public double remainingUsd(Totals totals) {
        return Math.max(0.0, poolUsd - spentUsd(totals));
    }

    /** Remaining pool as a whole-number percent, clamped to 0..100. */
    public int remainingPercent(Totals totals) {
        if (poolUsd <= 0) {
            return 100;
        }
        double pct = remainingUsd(totals) / poolUsd * 100.0;
        return (int) Math.round(Math.max(0.0, Math.min(100.0, pct)));
    }
}
