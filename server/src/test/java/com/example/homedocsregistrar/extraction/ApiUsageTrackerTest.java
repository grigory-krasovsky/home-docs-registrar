package com.example.homedocsregistrar.extraction;

import com.example.homedocsregistrar.domain.ApiUsage;
import com.example.homedocsregistrar.repository.ApiUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ApiUsageTrackerTest {

    @Autowired
    private ApiUsageTracker tracker;

    @Autowired
    private ApiUsageRepository usage;

    @Test
    void seedsThenAccumulatesTokensInOneRow() {
        // First call: no counter row yet -> it is seeded with this call's counts.
        tracker.record(100, 20, "claude-haiku-4-5");
        // Subsequent calls: atomically added to the same row.
        tracker.record(50, 10, "claude-haiku-4-5");

        ApiUsage total = usage.findById(ApiUsage.SINGLETON_ID).orElseThrow();
        assertThat(total.getInputTokens()).isEqualTo(150);
        assertThat(total.getOutputTokens()).isEqualTo(30);
        assertThat(usage.count()).isEqualTo(1);
    }
}
