package com.example.homedocsregistrar.extraction;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured output of the vision call when reminder suggestions are on: the usual document fields plus
 * the deadlines worth a reminder. A wrapper (not extra fields on {@link ExtractedFields}) so that with
 * suggestions off the schema and prompt stay exactly as before and cost no extra tokens.
 */
public record ExtractionWithReminders(

        @JsonPropertyDescription("Поля и полный текст документа")
        ExtractedFields document,

        @JsonPropertyDescription("Будущие сроки из документа, о которых стоит напомнить; пустой список, "
                + "если явных сроков нет")
        List<ReminderHint> reminders
) {
}
