# home-docs-registrar — implementation plan

> Durable copy of the agreed design and roadmap. Any Claude Code session (or human) should read
> this first. It is the source of truth for *why* the project is shaped the way it is.

## What this is

A personal registry for home paper documents (contracts, warranties, receipts, …). Intake pipeline:

1. User photographs a document and sends it to a **Telegram bot as a file** (not a compressed "photo" — compression hurts OCR).
2. The bot runs **OCR**, shows the extracted text/fields, the user confirms/corrects them in the dialog.
3. The bot asks for the **card-catalog section** (scan the section's QR, or pick from a list).
4. The digitized file is stored in a **folder on the home PC**; the DB row keeps the OCR text/fields, the file path, and the physical location.

## Architecture: two apps + store-and-forward queue

The home PC is **not always on**, so the system is split so intake never depends on the PC being up:

- **`server/`** (runs on the VPS): Telegram bot, OCR, PostgreSQL registry, on-disk file queue, HTTPS API, disk-space monitor.
- **`agent/`** (runs on the home Windows PC, on only sometimes): pulls the queue and writes files to a local folder.

Flow: a document arrives → the server OCRs it and writes metadata to Postgres **immediately**, and puts the file in a queue on the VPS. When the home PC comes online, the **agent dials OUT to the VPS over HTTPS (pull model)**, downloads queued files, writes them to the local documents folder, verifies, acks; the VPS then deletes its copy.

Consequence of the pull model: the VPS needs **no inbound access to the home network** — no VPN/port-forwarding. The agent only needs outbound HTTPS + write access to a local folder.

## Module layout

```
home-docs-registrar/            # root = Maven parent/aggregator (packaging=pom)
├── server/                     # Spring Boot app (VPS)  — pkg com.example.homedocsregistrar
├── agent/                      # plain Java app (Windows) — pkg com.example.homedocsagent
├── docs/PLAN.md                # this file
├── docs/SETUP.md               # one-time manual setup steps
└── CLAUDE.md
```

## Key decisions (with rationale)

- **OCR = local Tesseract (Tess4J, `rus`)** — chosen over paid Yandex Vision / cloud vision-LLMs for budget (zero per-request cost) and privacy (documents stay on the user's own machine). Compensate for phone-photo quality with preprocessing + human-in-the-loop field confirmation. Auto field-extraction is a later, pluggable module.
- **DB = PostgreSQL** — for good Russian full-text search (morphology/stemming). It is the always-available source of truth.
- **Storage = a folder on the home PC.** The digitized files live on the PC's local filesystem (the agent writes there directly). An Apple Time Capsule / SMB was considered as network storage and **dropped** — it added the most complexity (SMB3 via TimeCapsuleSMB, `jcifs-ng`, a fixed IP, a Mac to set it up) for little gain now that the PC holds the files. The "digital file link" is a local path on the PC; retrieval requires the PC to be on (accepted).
- **Backup = a removable SSD (manual, out-of-band).** Guards the single-disk risk for irreplaceable papers. The user already owns the SSD; backup is a periodic manual copy of the documents folder — **not** driven by the software.
- **Agent packaging = plain JAR + Windows Task Scheduler (at logon)** — chosen for minimal footprint; Docker was considered and rejected (Docker Desktop + WSL2 too heavy, and also login-dependent). Optional later polish: jpackage `.exe`/`.msi`, or WinSW to run as a true service.
- **Queue on the VPS = plaintext files on disk** (no at-rest encryption — user's choice; Tesseract sees plaintext at intake anyway).

## Queue invariants (must never lose a document)

1. **Delete-after-confirm** — the VPS deletes its copy ONLY after the agent confirms a full, durable write to the documents folder (size/hash verified).
2. **Idempotency** — items keyed by stable id / content hash; a retried hand-off (e.g. lost ack) must not duplicate.
3. **Persistence across restart** — queue lives on disk + a Postgres status column (`PENDING_UPLOAD` / `STORED`), never only in memory.
4. **Atomic write** — write to a temp name → verify → rename within the same folder.
5. **Search-before-sync** — OCR text/metadata go to Postgres immediately, so search works even while the binary is still queued and the PC is offline; only file retrieval waits.

## Roadmap (status)

- [x] **Step 1 — monorepo skeleton + durable docs.** Root→parent/aggregator; `server` (web+test) & `agent` (Jackson, shade) modules; existing code moved to `server/`; agent stub; `docs/PLAN.md` + `docs/SETUP.md`; `CLAUDE.md`. Green: `mvnw clean package` builds both, `contextLoads` passes.
- [ ] **Step 2 — domain & storage (server).** JPA `Document` + `CatalogSection`; queue status enum; Postgres FTS (`tsvector` russian + GIN); Spring Data repositories. Add `data-jpa` + `postgresql` and pick a test-datasource strategy that keeps `contextLoads` green.
- [ ] **Step 3 — Telegram intake + OCR (server).** Long-polling bot with a dialog state machine (file → OCR → confirm fields → pick section → enqueue); "sent as file, not a compressed photo" check; `OcrService` on Tess4J (`rus`). Decide the Telegram library (starter vs. raw Bot API via `RestClient`).
- [ ] **Step 4 — queue, disk monitor, agent API (server).** On-disk queue + DB status; `DiskSpaceMonitor` with hysteresis + Telegram alert; REST `/api/queue` (`pending` / `file` / `ack`, bearer token); enforce the invariants above.
- [ ] **Step 5 — agent (Windows).** Pull loop → write to the local documents folder (temp → verify → rename) → ack; idempotent + backoff; env config (`VPS_URL`, `AGENT_TOKEN`, `TARGET_DIR`); fat JAR; Task Scheduler autostart. Optional tray icon.
- [ ] **Step 6 — SETUP.md + verification.** Fill in the one-time manual steps; run build/tests + an end-to-end check.

## Open decisions (resolve at implementation time)

- **Telegram library under Spring Boot 4.1.1 / Spring 7** — check `telegrambots` long-polling starter compatibility; safe minimal-footprint fallback: call the Telegram Bot API directly via `RestClient` (no third-party dependency). Decide in Step 3.
- **Tess4J + native Tesseract** — the VPS needs Tesseract installed plus `rus.traineddata` (documented in `docs/SETUP.md`).
- **Queue payload format** — store the original image, or a normalized compressed PDF (recommended: compressed PDF, which is also the archival copy; smaller queue). Decide in Step 4.

## Verification

- Build: `.\mvnw.cmd clean package` — both modules green; `contextLoads` passes.
- Server (unit/slice): `DiskSpaceMonitor` hysteresis; queue status transitions; REST contract (MockMvc: pending/file/ack, ack idempotency); OCR smoke test on a sample `rus` image.
- Agent: pull → verify → ack loop behind a write interface (fake writer / temp dir instead of the real folder) — idempotency, atomicity (temp → rename), backoff.
- End-to-end (manual): send a document to the bot → DB row + queued file → run the agent → file lands in the documents folder, DB flips to `STORED`, VPS copy deleted; search works before sync.
