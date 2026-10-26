# home-docs-registrar — implementation plan

> Durable copy of the agreed design and roadmap. Any Claude Code session (or human) should read
> this first. It is the source of truth for *why* the project is shaped this way.

## What this is

A personal registry for home paper documents (contracts, warranties, receipts, …). Intake pipeline:

1. User photographs a document and sends it to a **Telegram bot as a file** (not a compressed "photo" — compression hurts OCR and archival quality).
2. The bot runs **OCR**, shows the extracted text/fields, the user confirms/corrects them in the dialog.
3. The bot asks for the **card-catalog section** (scan the section's QR, or pick from a list).
4. The registry row (OCR text/fields, physical location, and the file's Telegram `file_id` + `channel_message_id`) is saved in Postgres. The digitized file itself stays on **Telegram** — the bot re-posts it into a **private archive channel** it administers (as a *document*, byte-exact). It is **backed up to the home PC** when the PC is next online.

## Architecture: Telegram-primary store, VPS registry, PC backup

Storage roles (this is the key idea):

- **Telegram = primary file store.** At ingest the bot re-posts the document into a **private channel** the user owns and the bot administers, and stores that message's `file_id` + `channel_message_id`. The bot re-sends by `file_id` on request — instantly, from anywhere, without the home PC. Using a channel (not the personal chat) decouples the archive from the user's chat history and gives a browsable view.
- **VPS (`server/`) = registry + orchestrator, holds no file archive.** It keeps only metadata + `file_id`/`channel_message_id` in Postgres (VPS disk is limited). It downloads a file from Telegram **transiently** to OCR it (and to relay it to the PC for backup), then discards the bytes.
- **Home PC (`agent/`) = backup, synced when online.** When the PC comes on, the agent dials OUT to the VPS, learns which documents it hasn't backed up, pulls them (the VPS relays the bytes from Telegram), and writes them to a local folder. A **removable SSD** is a further cold copy (manual). After the first backup a document has 3 copies: Telegram + PC + SSD.

Flow: document arrives → bot re-posts it to the archive channel, gets `file_id`/`channel_message_id`, downloads it once to OCR, writes metadata to Postgres, discards the bytes → retrieval = `file_id` re-send → backup happens opportunistically when the PC is online.

Consequence of the pull model: the VPS needs **no inbound access to the home network** (no VPN/port-forwarding). The agent only needs outbound HTTPS + write access to a local folder.

## Module layout

```
home-docs-registrar/            # root = Maven parent/aggregator (packaging=pom)
├── server/                     # Spring Boot app (VPS)  — pkg com.example.homedocsregistrar
├── agent/                      # plain Java app (Windows) — pkg com.example.homedocsagent (backup syncer)
├── docs/PLAN.md                # this file
├── docs/SETUP.md               # one-time manual setup steps
└── CLAUDE.md
```

## Key decisions (with rationale)

- **Storage = Telegram primary + PC backup (+ SSD).** The archive lives on Telegram in a **private channel** (owner = the user, admin = the bot; config `ARCHIVE_CHANNEL_ID`); files are stored as **documents** (byte-exact, never as recompressed photos) and retrieved by `file_id`. The VPS keeps only metadata + `file_id`/`channel_message_id` because **VPS disk is limited**; the PC keeps an owned backup synced when online; the SSD is a manual cold copy. Earlier designs (Time Capsule over SMB; then PC-primary) were dropped.
- **Retrieval by `file_id`** — the bot re-sends the stored file instantly, from anywhere, PC-independent.
- **OCR = local Tesseract (Tess4J, `rus`)** — budget (zero per-request cost) + privacy (documents stay on the user's own machine for OCR). Human-in-the-loop field confirmation compensates for phone-photo quality. Auto field-extraction is a later, pluggable module.
- **DB = PostgreSQL** — good Russian full-text search (morphology/stemming); the always-available source of truth for text/metadata.
- **Agent packaging = plain JAR + Windows Task Scheduler (at logon)** — minimal footprint; Docker rejected (too heavy, login-dependent). Optional later: jpackage `.exe`/`.msi`, or WinSW service.

## Known constraints

- **Store as a document, not a photo**: Telegram recompresses photos (quality/OCR loss); documents keep the original bytes. Always send/re-post as a document.
- **Telegram Bot API limits**: a bot can **download** files up to ~20 MB and **send** up to ~50 MB. Fine for compressed PDFs/photos; very large multi-page scans are the exception (a self-hosted local Bot API server lifts the limit but adds infra — out of scope). Normalize scans to a compact PDF at ingest.
- **Brief single-copy window**: between ingest and the first PC backup, the only copy is on Telegram (low risk; acceptable). After backup there are 3 copies.
- **`file_id` is bot-specific**: it is tied to the current bot token; changing bots invalidates stored `file_id`s. The PC/SSD backup is the ownership hedge.

## Sync invariants (backup is benign, but still correct)

Telegram holds the primary copy, so the PC backup sync is not on the critical path for data loss. Still:

1. **Mark-after-confirm** — a document is flagged `BACKED_UP` only after the agent confirms a full, verified local write (size/hash).
2. **Idempotency** — keyed by stable document id / content hash; a retried backup (e.g. lost ack) must not duplicate; the agent checks "already present?" first.
3. **Persistence across restart** — backup state lives in Postgres (`PENDING_BACKUP` / `BACKED_UP`), never only in memory.
4. **Atomic write** — temp name → verify → rename within the backup folder.
5. **Search + retrieval never wait on the PC** — metadata/text (Postgres) and file retrieval (`file_id`) are always available regardless of whether the PC is online.

## Reminders via notifier

Documents often carry deadlines (warranty end, next service, contract/insurance end, payment due). Reminders
for them live in the sibling **notifier** bot (`../notifier`, its own Spring Boot app + Postgres), which
pings the user at the chosen time and, with a repeat interval, keeps nagging until they press «Готово».
The registrar talks to notifier's HTTP API v1 (full contract: `../notifier/docs/API.md`).

- **Roles.** notifier is the **source of truth** for reminders — the registrar stores **no copy** (the user
  can pause/finish/delete one right in notifier). Only short-lived dialog state lives in memory
  (`telegram/ReminderDialog`), benign to lose on restart.
- **Personal, tied to a document.** A reminder goes to the user who creates it (`telegramUserId` = the
  private chat id); `source=home-docs`, `ref=document:<id>`, plus a deep link
  `https://t.me/<bot>?start=doc_<id>` that notifier shows as «📄 Документ» — `/start doc_<id>` opens the
  document card here (same allow-list check as every command). The bot's username comes from `getMe`.
- **Idempotency.** The registrar generates `externalId` (UUID) when a suggestion or form *appears* and
  keeps it in the pending state, so a repeated tap or a retry after a timeout gets `200` (existing event)
  from notifier instead of a duplicate.
- **LLM suggestions ride on the existing vision call** (no extra request; the model sees the document
  itself). With `reminders.suggest` on (and notifier configured) the structured output is
  `ExtractionWithReminders {document, reminders[]}`; otherwise the old `ExtractedFields` schema/prompt is
  used unchanged. The model returns the **deadline itself** as an absolute ISO date (it's told today's
  date; relative terms are counted from the document date). The registrar (`ReminderPlanner`) drops
  malformed/past/>10-years/pre-document dates, de-dupes, keeps ≤ 3, and applies the **lead time** per kind
  (warranty/service/insurance 14 days, contract end / expiry 30, payment 3, other 7; never before today) —
  notifier only gets the final fire date. Suggestions default to a daily nag.
- **UI (no extra messages).** Suggestions are a block + button rows (✅ Создать / ✏️ Изменить / ✖ Не надо)
  on the existing «Сохранено ✅» card, edited in place; the section offer stays as is. «⏰ Напоминания»
  (card button or `/remind <id>`) lists this user's reminders for the document (🗑 each, ➕ Новое); the
  form is name → date → optional time (default: the user's working-hours start in notifier) → repeat
  (none / hourly / daily) → create. A user not connected to notifier (`USER_NOT_REGISTERED`) sees «запусти
  @<notifier> и нажми /start» instead of the create button.
- **Failures.** Short timeouts (connect 2 s, read 5 s); unreachable/timeout/codeless 5xx →
  `NotifierUnavailableException`, API errors → `NotifierApiException` by `code`. The user gets a clear
  message and the pending suggestion/form survives for another tap.
- **Document deletion** drops the document's reminders for all users (`DELETE /events?source&ref`),
  best-effort and after the DB delete: if notifier is down the document is still deleted and the user is
  warned.
- **Off switch.** Blank `NOTIFIER_URL` → integration off: no reminder buttons/suggestions, the app boots as
  usual. Config: `NOTIFIER_URL`, `NOTIFIER_API_TOKEN` (shared secret, env only), `NOTIFIER_BOT_USERNAME`,
  `REMINDERS_SUGGEST`.
- **Network.** On the VPS both stacks join the external docker network `home-apps` (the deploy creates it
  idempotently); notifier is `http://notifier-app:8080` there. See `docs/SETUP.md` §8.

## Roadmap (status)

- [x] **Step 1 — monorepo skeleton + durable docs.** Parent/aggregator + `server` (web+test) & `agent` (Jackson, shade); existing code in `server/`; agent stub; `docs/*`; `CLAUDE.md`. Green build, `contextLoads` passes.
- [x] **Step 2 — domain & storage (server).** JPA `Document` (incl. `telegram_file_id`, `channel_message_id`, `backup_status`) + repositories done; `contextLoads` green on H2. **Russian FTS done** (query-time `to_tsvector('russian', …)` over `ocr_text` + fields, ranked by `ts_rank`; `DocumentRepository.search` native query, `DocumentSearchService`, bot `/search`). **Deferred:** GIN index / generated `tsvector` column (query-time is fine at this scale) and `CatalogSection`.
- [x] **Step 3 — Telegram intake + extraction (server).** Long-polling bot: file → extract fields + full text → re-post to the archive channel → dedupe by content hash → save row; "sent as file, not a compressed photo" check; download-then-discard; config `ARCHIVE_CHANNEL_ID`. **Extraction switched from Tesseract to Claude vision** (`DocumentExtractionService`, Haiku 4.5); persistent token-usage counter + `/tokens` + remaining-balance estimate. **Deferred:** human-in-the-loop field confirmation and catalog-section picking.
- [~] **Step 4 — retrieval + backup API (server).** **Done:** bot command `/get <id>` re-sends a document's file by its `file_id` (`DocumentRetrievalService`). **Deferred (postponed by user):** REST backup API for the agent (list `PENDING_BACKUP`, relay bytes from Telegram, mark `BACKED_UP`; bearer token).
- [ ] **Step 5 — agent (Windows).** Backup sync loop: pull pending → write to local folder (temp → verify → rename) → mark backed up; idempotent + backoff; env config (`VPS_URL`, `AGENT_TOKEN`, `BACKUP_DIR`); fat JAR; Task Scheduler. Optional tray icon.
- [x] **Reminders via notifier (server).** `reminder/` client (RestClient, typed errors) + `ReminderService`
  + `ReminderPlanner`; `telegram/ReminderDialog` (card suggestions, list, form); `/remind`, `/start doc_<id>`;
  reminders dropped on document deletion; `home-apps` network in compose + deploy. See "Reminders via notifier".
- [ ] **Step 6 — SETUP.md + verification.** Fill in manual steps; build/tests + end-to-end check.

## Open decisions (resolve at implementation time)

- **Telegram library under Spring Boot 4.1.1 / Spring 7** — check `telegrambots` long-polling starter; safe minimal fallback: call the Bot API directly via `RestClient` (no third-party dep). Decide in Step 3.
- **Tess4J + native Tesseract** — the VPS needs Tesseract + `rus.traineddata` (documented in `docs/SETUP.md`).
- **Normalization** — normalize the incoming scan to a compact PDF before re-posting (keeps within Telegram limits, uniform archive). Decide in Step 3.

## Verification

- Build: `.\mvnw.cmd clean package` — both modules green; `contextLoads` passes.
- Server (unit/slice): backup status transitions; REST contract (MockMvc: list-pending / relay / mark-backed-up, idempotency); retrieval by `file_id`; OCR smoke test on a sample `rus` image.
- Agent: backup loop behind a write interface (temp dir instead of the real folder) — idempotency, atomicity (temp → rename), backoff.
- End-to-end (manual): send a document → row + `file_id`/`channel_message_id` in Postgres, file in the archive channel and retrievable via the bot from anywhere → bring the PC online → file mirrored locally, row flips to `BACKED_UP`.
