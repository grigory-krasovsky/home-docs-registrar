-- The vision model sometimes stored a literal "absent" marker (e.g. "+null", "null", "—") in a text
-- field instead of leaving it empty. New intake now normalizes these to NULL; clean up existing rows.
-- (search_vector is a generated column and recomputes automatically after these updates.)

update document set doc_type = null
    where lower(btrim(doc_type)) in ('null', '+null', 'none', 'n/a', 'na', 'nan', '-', '—', 'нет', 'не указано', 'отсутствует');
update document set title = null
    where lower(btrim(title)) in ('null', '+null', 'none', 'n/a', 'na', 'nan', '-', '—', 'нет', 'не указано', 'отсутствует');
update document set counterparty = null
    where lower(btrim(counterparty)) in ('null', '+null', 'none', 'n/a', 'na', 'nan', '-', '—', 'нет', 'не указано', 'отсутствует');
update document set document_number = null
    where lower(btrim(document_number)) in ('null', '+null', 'none', 'n/a', 'na', 'nan', '-', '—', 'нет', 'не указано', 'отсутствует');
update document set ocr_text = null
    where lower(btrim(ocr_text)) in ('null', '+null', 'none', 'n/a', 'na', 'nan', '-', '—', 'нет', 'не указано', 'отсутствует');
