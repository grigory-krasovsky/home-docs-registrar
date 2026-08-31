-- Russian full-text search index. A STORED generated column holds the tsvector of the transcribed
-- text plus the key fields; Postgres computes and keeps it up to date automatically (including for the
-- rows that already exist). A GIN index over it makes `@@` searches fast. to_tsvector(regconfig, text)
-- is IMMUTABLE, so it is allowed in a generated column.

alter table document
    add column search_vector tsvector
        generated always as (
            to_tsvector('russian',
                coalesce(ocr_text, '') || ' ' || coalesce(title, '') || ' ' ||
                coalesce(doc_type, '') || ' ' || coalesce(counterparty, '') || ' ' ||
                coalesce(document_number, ''))
        ) stored;

create index idx_document_search_vector on document using gin (search_vector);
