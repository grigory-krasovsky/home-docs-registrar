package com.example.homedocsregistrar.extraction;

import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The SDK derives the structured-output JSON schema from the record and validates it locally against what
 * the API supports; this catches an unsupported shape (e.g. the nested reminders list) without a paid call.
 */
class ExtractionSchemaTest {

    @Test
    void bothExtractionSchemasAreAcceptedBySdk() {
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-haiku-4-5").maxTokens(16L).addUserMessage("x")
                .outputConfig(ExtractedFields.class).build()).doesNotThrowAnyException();
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-haiku-4-5").maxTokens(16L).addUserMessage("x")
                .outputConfig(ExtractionWithReminders.class).build()).doesNotThrowAnyException();
    }
}
