package com.example.homedocsregistrar.extraction;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A deadline the vision model spotted in the document that is worth a reminder. The model returns the
 * deadline itself; the registrar decides how early to remind (see {@code ReminderPlanner}). Plain strings,
 * validated later — a malformed value then drops one hint instead of failing the whole extraction.
 */
public record ReminderHint(

        @JsonPropertyDescription("Вид срока: WARRANTY (окончание гарантии), SERVICE (следующее ТО, поверка, "
                + "обслуживание), CONTRACT_END (окончание договора), INSURANCE_END (окончание страховки/полиса), "
                + "PAYMENT (срок оплаты), EXPIRY (истекает срок действия документа), OTHER (другое)")
        String kind,

        @JsonPropertyDescription("Короткое название напоминания по-русски, например «Истекает гарантия: "
                + "холодильник Bosch»")
        String title,

        @JsonPropertyDescription("Дата САМОГО срока (не дата напоминания) — только абсолютная, ISO yyyy-MM-dd")
        String date
) {
}
