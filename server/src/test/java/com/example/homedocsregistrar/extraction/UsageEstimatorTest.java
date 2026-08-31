package com.example.homedocsregistrar.extraction;

import com.example.homedocsregistrar.extraction.ApiUsageTracker.Totals;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class UsageEstimatorTest {

    @Test
    void estimatesSpendAndRemainingFromTokenTotals() {
        // $5 pool, Claude Haiku 4.5 prices ($1 in / $5 out per 1M tokens).
        UsageEstimator estimator = new UsageEstimator(5.00, 1.00, 5.00);
        // 1,000,000 in -> $1.00; 200,000 out -> $1.00; spent $2.00; remaining $3.00 -> 60%.
        Totals totals = new Totals(1_000_000, 200_000);

        assertThat(estimator.hasPool()).isTrue();
        assertThat(estimator.spentUsd(totals)).isEqualTo(2.00, within(1e-9));
        assertThat(estimator.remainingUsd(totals)).isEqualTo(3.00, within(1e-9));
        assertThat(estimator.remainingPercent(totals)).isEqualTo(60);
    }

    @Test
    void remainingIsClampedAndPoolCanBeDisabled() {
        // Overspent -> remaining floored at 0 / 0%.
        UsageEstimator overspent = new UsageEstimator(1.00, 1.00, 5.00);
        Totals lots = new Totals(2_000_000, 0); // $2 spent against a $1 pool
        assertThat(overspent.remainingUsd(lots)).isEqualTo(0.0, within(1e-9));
        assertThat(overspent.remainingPercent(lots)).isZero();

        // No pool configured -> estimate disabled.
        UsageEstimator disabled = new UsageEstimator(0, 1.00, 5.00);
        assertThat(disabled.hasPool()).isFalse();
    }
}
