package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Human corrections to an already-saved document — the fields the vision model fills at intake (title,
 * type, number, date, amount, warranty). Values are parsed/validated per field ({@link Field#kind}), and
 * a blank input clears the field. The Postgres {@code search_vector} is a STORED generated column over the
 * text fields, so it re-computes automatically on update — no manual reindex.
 */
@Service
public class DocumentEditService {

    /** Text columns (title/doc_type/document_number) are {@code varchar(255)} — cap so a paste can't overflow. */
    private static final int MAX_TEXT = 255;

    /** Accept a Russian-style {@code ДД.ММ.ГГГГ} date in addition to ISO {@code ГГГГ-ММ-ДД}. */
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d.M.uuuu");

    private final DocumentRepository documents;

    public DocumentEditService(DocumentRepository documents) {
        this.documents = documents;
    }

    /** How a field's input is parsed and rendered. */
    enum Kind { TEXT, DATE, AMOUNT }

    /**
     * A user-correctable document field. {@code key} is the stable token used in Telegram callback data;
     * {@code label} is the Russian caption shown to the user; {@code kind} drives parsing/validation.
     */
    public enum Field {
        TITLE("title", "Название", Kind.TEXT),
        DOC_TYPE("type", "Тип", Kind.TEXT),
        NUMBER("number", "Номер", Kind.TEXT),
        DATE("date", "Дата", Kind.DATE),
        AMOUNT("amount", "Сумма", Kind.AMOUNT),
        WARRANTY("warranty", "Гарантия до", Kind.DATE);

        private final String key;
        private final String label;
        private final Kind kind;

        Field(String key, String label, Kind kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        Kind kind() {
            return kind;
        }

        /** A short input-format hint appended to the edit prompt (empty for free text). */
        public String hint() {
            return switch (kind) {
                case DATE -> " (ГГГГ-ММ-ДД или ДД.ММ.ГГГГ)";
                case AMOUNT -> " (число, например 4559.00)";
                case TEXT -> "";
            };
        }

        public static Optional<Field> fromKey(String key) {
            return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
        }
    }

    /** A field's current value on a document, rendered for display; empty string when unset. */
    public String currentValue(Document document, Field field) {
        return switch (field) {
            case TITLE -> nz(document.getTitle());
            case DOC_TYPE -> nz(document.getDocType());
            case NUMBER -> nz(document.getDocumentNumber());
            case DATE -> document.getDocDate() == null ? "" : document.getDocDate().toString();
            case AMOUNT -> document.getAmount() == null ? "" : document.getAmount().toPlainString();
            case WARRANTY -> document.getWarrantyUntil() == null ? "" : document.getWarrantyUntil().toString();
        };
    }

    /**
     * Set a field from raw user input, parsing/validating per the field's kind. A blank value clears the
     * field. Returns an {@link UpdateResult}: OK with the saved document, INVALID with a message the caller
     * shows (input kept for a retry), or NOT_FOUND for an unknown id.
     */
    @Transactional
    public UpdateResult updateField(long documentId, Field field, String rawValue) {
        String raw = rawValue == null ? "" : rawValue.strip();
        if (raw.isBlank()) {
            return clearField(documentId, field);
        }
        Object parsed;
        switch (field.kind()) {
            case TEXT -> parsed = cap(raw);
            case DATE -> {
                LocalDate date = parseDate(raw);
                if (date == null) {
                    return UpdateResult.invalid("Не понял дату. Формат: ГГГГ-ММ-ДД или ДД.ММ.ГГГГ.");
                }
                parsed = date;
            }
            case AMOUNT -> {
                BigDecimal amount = parseAmount(raw);
                if (amount == null) {
                    return UpdateResult.invalid("Не понял сумму. Введите число, например 4559.00.");
                }
                parsed = amount;
            }
            default -> parsed = null;
        }
        Object value = parsed;
        return documents.findById(documentId)
                .map(document -> {
                    applyValue(document, field, value);
                    Document saved = documents.save(document);
                    return UpdateResult.ok(saved, currentValue(saved, field));
                })
                .orElseGet(UpdateResult::notFound);
    }

    /** Clear a field (set it to null/empty). Returns OK with the saved document, or NOT_FOUND. */
    @Transactional
    public UpdateResult clearField(long documentId, Field field) {
        return documents.findById(documentId)
                .map(document -> {
                    applyValue(document, field, null);
                    return UpdateResult.ok(documents.save(document), "");
                })
                .orElseGet(UpdateResult::notFound);
    }

    /** Set a document's title; kept for {@code /rename} and existing callers. Empty if the id is unknown. */
    @Transactional
    public Optional<Document> renameTitle(long documentId, String title) {
        return Optional.ofNullable(updateField(documentId, Field.TITLE, title).document());
    }

    private static void applyValue(Document document, Field field, Object value) {
        switch (field) {
            case TITLE -> document.setTitle((String) value);
            case DOC_TYPE -> document.setDocType((String) value);
            case NUMBER -> document.setDocumentNumber((String) value);
            case DATE -> document.setDocDate((LocalDate) value);
            case AMOUNT -> document.setAmount((BigDecimal) value);
            case WARRANTY -> document.setWarrantyUntil((LocalDate) value);
        }
    }

    /** Parse ISO {@code yyyy-MM-dd} first, then {@code d.M.yyyy}; null if neither parses. */
    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ignored) {
            // fall through to the Russian format
        }
        try {
            return LocalDate.parse(raw, DMY);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Parse a money amount, tolerating spaces, a currency word and a comma decimal; null if not a number. */
    private static BigDecimal parseAmount(String raw) {
        String cleaned = raw.replaceAll("[^0-9,.-]", "").replace(',', '.');
        if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cap(String value) {
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** Outcome of a field edit: OK (with the saved document + rendered new value), INVALID, or NOT_FOUND. */
    public record UpdateResult(Status status, Document document, String newValue, String error) {

        public enum Status { OK, INVALID, NOT_FOUND }

        static UpdateResult ok(Document document, String newValue) {
            return new UpdateResult(Status.OK, document, newValue, null);
        }

        static UpdateResult invalid(String error) {
            return new UpdateResult(Status.INVALID, null, null, error);
        }

        static UpdateResult notFound() {
            return new UpdateResult(Status.NOT_FOUND, null, null, null);
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }
}
