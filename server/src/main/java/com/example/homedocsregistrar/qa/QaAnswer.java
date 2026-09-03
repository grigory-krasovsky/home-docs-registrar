package com.example.homedocsregistrar.qa;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured answer the model returns for a Q&A over the archive. Field names/descriptions drive the
 * JSON schema the model must fill: whether the documents contain the answer, the answer text (Russian),
 * and the ids of the documents it relied on (a subset of the ones provided as context).
 */
public record QaAnswer(

        @JsonPropertyDescription("true если в приведённых документах есть ответ на вопрос; "
                + "false если ответа в документах нет")
        Boolean found,

        @JsonPropertyDescription("Краткий ответ по-русски строго на основе документов; если ответа нет — "
                + "короткое пояснение, что в документах это не найдено")
        String answer,

        @JsonPropertyDescription("Номера документов (числа #id), на которых основан ответ; "
                + "пустой список, если ответа нет")
        List<Long> sourceDocIds
) {
}
