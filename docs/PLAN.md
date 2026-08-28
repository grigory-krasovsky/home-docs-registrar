# home-docs-registrar — implementation plan

> Durable copy of the agreed design and roadmap. Any Claude Code session (or human) should read
> this first. It is the source of truth for *why* the project is shaped the way it is.

## What this is

A personal registry for home paper documents (contracts, warranties, receipts, …). Intake pipeline:

1. User photographs a document and sends it to a **Telegram bot as a file** (not a compressed "photo" — compression hurts OCR).
2. The bot runs **OCR**, shows the extracted text/fields, the user confirms/corrects them in the dialog.
3. The bot asks for the **card-catalog section** (scan the section's QR, or pick from a list).
4. The digitized file is stored on the home **Apple Time Capsule**; the DB row keeps the OCR text/fields, the capsule file path, and the physical location.

## Architecture: two apps + store-and-forward queue

There is **no always-on device at home** (the Time Capsule cannot be a tunnel endpoint), so the system is split:

- **`server/`** (runs on the VPS): Telegram bot, OCR, PostgreSQL registry, on-disk file queue, HTTPS API, disk-space monitor.
- **`agent/`** (runs on the home Windows PC, on only sometimes): pulls the queue and writes files to the capsule.

Flow: a document arrives → the server OCRs it and writes metadata to Postgres **immediately**, and puts the file in a queue on the VPS. When the home PC comes online, the **agent dials OUT to the VPS over HTTPS (pull model)**, downloads queued files, writes them to the Time Capsule over the LAN, verifies, acks; the VPS then deletes its copy.

Consequence of the pull model: the VPS never speaks SMB and never needs inbound access to the home network — **no WireGuard/Tailscale, no port-forwarding**. The agent only needs outbound HTTPS + LAN access to the capsule.

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
- **Storage = Apple Time Capsule over SMB3** — a home **Mac** installs [TimeCapsuleSMB](https://github.com/jamesyc/TimeCapsuleSMB) once so the capsule speaks modern SMB3 (its installer is macOS-only; it is strictly LAN-only). The agent then uses maintained `jcifs-ng` (pure Java over TCP, no OS SMB stack). Address the capsule by a **fixed IP** (DHCP reservation). File access is **home-only** (accepted).
- **Agent packaging = plain JAR + Windows Task Scheduler (at logon)** — chosen for minimal footprint; Docker was considered and rejected (Docker Desktop + WSL2 too heavy, and also login-dependent). Optional later polish: jpackage `.exe`/`.msi`, or WinSW to run as a true service.
- **Queue on the VPS = plaintext files on disk** (no at-rest encryption — user's choice; Tesseract sees plaintext at intake anyway).

## Queue invariants (must never lose a document)

1. **Delete-after-confirm** — the VPS deletes its copy ONLY after the agent confirms a full, durable write on the capsule (size/hash verified).
2. **Idempotency** — items keyed by stable id / content hash; a retried hand-off (e.g. lost ack) must not duplicate.
3. **Persistence across restart** — queue lives on disk + a Postgres status column (`PENDING_UPLOAD` / `ON_CAPSULE`), never only in memory.
4. **Atomic write on the capsule** — write to a temp name → verify → rename.
5. **Search-before-sync** — OCR text/metadata go to Postgres immediately, so search works even while the binary is still queued and the PC is offline; only file retrieval waits.

## Roadmap (status)

- [x] **Step 1 — monorepo skeleton + durable docs.** Root→parent/aggregator; `server` (web+test) & `agent` (jcifs-ng, jackson, shade) modules; existing code moved to `server/`; agent stub; `docs/PLAN.md` + `docs/SETUP.md`; `CLAUDE.md` updated. Green: `mvnw clean package` builds both, `contextLoads` passes.
- [ ] **Step 2 — domain & storage (server).** JPA `Document` + `CatalogSection`; queue status enum; Postgres FTS (`tsvector` russian + GIN); Spring Data repositories. Add `data-jpa` + `postgresql` and pick a test-datasource strategy that keeps `contextLoads` green.
- [ ] **Step 3 — Telegram intake + OCR (server).** Long-polling bot with a dialog state machine (file → OCR → confirm fields → pick section → enqueue); "sent as file, not a compressed photo" check; `OcrService` on Tess4J (`rus`). Decide the Telegram library (starter vs. raw Bot API via `RestClient`).
- [ ] **Step 4 — queue, disk monitor, agent API (server).** On-disk queue + DB status; `DiskSpaceMonitor` with hysteresis + Telegram alert; REST `/api/queue` (`pending` / `file` / `ack`, bearer token); enforce the invariants above.
- [ ] **Step 5 — agent (Windows).** Pull loop → write via jcifs-ng (temp → verify → rename) → ack; idempotent + backoff; env config; fat JAR; Task Scheduler autostart. Optional tray icon.
- [ ] **Step 6 — SETUP.md + verification.** Fill in the one-time manual steps; run build/tests + an end-to-end check.

## Open decisions (resolve at implementation time)

- **Telegram library under Spring Boot 4.1.1 / Spring 7** — check `telegrambots` long-polling starter compatibility; safe minimal-footprint fallback: call the Telegram Bot API directly via `RestClient` (no third-party dependency). Decide in Step 3.
- **Tess4J + native Tesseract** — the VPS needs Tesseract installed plus `rus.traineddata` (documented in `docs/SETUP.md`).
- **Queue payload format** — store the original image, or a normalized compressed PDF (recommended: compressed PDF, which is also the archival copy on the capsule; smaller queue). Decide in Step 4.

## Verification

- Build: `.\mvnw.cmd clean package` — both modules green; `contextLoads` passes.
- Server (unit/slice): `DiskSpaceMonitor` hysteresis; queue status transitions; REST contract (MockMvc: pending/file/ack, ack idempotency); OCR smoke test on a sample `rus` image.
- Agent: pull → verify → ack loop behind a write interface (fake writer instead of a real capsule) — idempotency, atomicity (temp → rename), backoff.
- End-to-end (manual): send a document to the bot → DB row + queued file → run the agent on the LAN → file lands on the capsule, DB flips to `ON_CAPSULE`, VPS copy deleted; search works before sync.
