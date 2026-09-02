-- Card-catalog sections become a shallow two-level tree (owner -> subsection), and the QR code turns
-- optional: sections are now created by name from the bot; a physical QR sticker is bound to a drawer
-- later, if ever. A document is filed into a leaf subsection (document.section_id -> a subsection row).

alter table catalog_section add column parent_id bigint references catalog_section;
alter table catalog_section alter column code drop not null;

-- Seed the agreed starter tree so the model files documents into sensible buckets from day one and
-- doesn't invent new sections. Idempotent: each insert is guarded so a manual re-run can't duplicate.

-- Top level: three people + a shared section.
insert into catalog_section (label)
select v.label
from (values ('Гриша'), ('Маша'), ('Костя'), ('Общая')) as v(label)
where not exists (
    select 1 from catalog_section c where c.parent_id is null and c.label = v.label);

-- Personal subsections, the same set under each person.
insert into catalog_section (label, parent_id)
select s.label, p.id
from (values ('Личное'), ('Медицина и здоровье')) as s(label)
cross join catalog_section p
where p.parent_id is null and p.label in ('Гриша', 'Маша', 'Костя')
  and not exists (
    select 1 from catalog_section c where c.parent_id = p.id and c.label = s.label);

-- Shared subsections under «Общая».
insert into catalog_section (label, parent_id)
select s.label, p.id
from (values ('ЖКХ'), ('Гарантии'), ('Финансы'), ('Инструкции к бытовой технике'), ('Авто')) as s(label)
cross join catalog_section p
where p.parent_id is null and p.label = 'Общая'
  and not exists (
    select 1 from catalog_section c where c.parent_id = p.id and c.label = s.label);
