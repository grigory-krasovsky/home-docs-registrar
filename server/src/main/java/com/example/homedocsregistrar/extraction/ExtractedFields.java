package com.example.homedocsregistrar.extraction;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured result of reading a document photo with the vision model. Field names/descriptions
 * drive the JSON schema the model must fill. Dates are ISO {@code yyyy-MM-dd} strings (parsed later);
 * unknown values come back as null.
 */
public record ExtractedFields(

        @JsonPropertyDescription("Document type in Russian, e.g. чек, договор, гарантия, свидетельство, счёт, акт")
        String docType,

        @JsonPropertyDescription("Short human-readable title of the document")
        String title,

        @JsonPropertyDescription("Counterparty or the parties involved (e.g. seller/organization name)")
        String counterparty,

        @JsonPropertyDescription("Document date as ISO yyyy-MM-dd, or null if absent")
        String docDate,

        @JsonPropertyDescription("Document number (e.g. чек/договор №), or null")
        String number,

        @JsonPropertyDescription("Total amount as a plain number like 4559.00 (no currency), or null")
        String amount,

        @JsonPropertyDescription("Warranty end date as ISO yyyy-MM-dd, or null")
        String warrantyUntil,

        @JsonPropertyDescription("Full transcribed text of the document, for search")
        String fullText
) {
}
