# One-time setup (manual steps)

These are the manual, out-of-band steps the software itself cannot perform. Fill in the concrete
values (paths, ids, tokens) as they are decided. Detailed instructions are written in **Step 6** of
`docs/PLAN.md`; the sections below are placeholders so nothing is forgotten.

## 1. Telegram bot

- Create the bot via @BotFather; keep the **bot token** in the VPS `.env` (never commit it).
- Bot API limits: download ≤ ~20 MB, send ≤ ~50 MB — normalize scans to a compact PDF at ingest.
- _TODO: record the bot username._

## 2. Telegram — private archive channel (primary store)

- Create a **private channel** from your own account (bots cannot create channels — you are the owner).
- **Add the bot as an admin** with "Post Messages" permission (member-only is not enough).
- Capture the channel's numeric **chat_id** (`-100…`) for `ARCHIVE_CHANNEL_ID`: after adding the bot as admin,
  post any message in the channel and the bot receives it as a channel post with the id.
- _TODO: record `ARCHIVE_CHANNEL_ID`._

## 3. VPS — Docker host & the deployed stack

- Install **Docker Engine + Compose plugin**.
- The stack is `docker-compose.yml` (services: `server` + `postgres:17`, data in the `pgdata` volume).
  CI copies the compose file to `~/home-docs-registrar/`. Postgres runs as a container — no manual DB provisioning.
- Create `~/home-docs-registrar/.env` (never commit), at minimum:
  ```
  POSTGRES_PASSWORD=<choose a strong password>
  # filled in from step 3/4 onwards:
  TELEGRAM_BOT_TOKEN=
  ARCHIVE_CHANNEL_ID=
  AGENT_TOKEN=
  # reminders via the notifier bot — see section 8 (blank NOTIFIER_URL = reminders off):
  NOTIFIER_URL=
  NOTIFIER_API_TOKEN=
  NOTIFIER_BOT_USERNAME=
  ```
- _TODO: create `.env`; confirm `docker compose up -d` brings up postgres + server._

## 4. GitHub Actions — deploy access

- Add repository **secrets** (deploy uses SSH **password** auth): `SSH_HOST` (VPS IP), `SSH_USER` (VPS user), `SSH_PASSWORD` (VPS password).
- The SSH user must be able to run Docker (in the `docker` group).
- On push to `master`: build + test → image pushed to **GHCR** (`ghcr.io/<owner>/home-docs-server`) → SSH deploy.
  Until `SSH_HOST` is set, the deploy steps are **skipped** (the image is still built and pushed, workflow stays green).
- Password auth is convenient but weaker than an SSH key; consider switching to a deploy key later.
- _TODO: add the three secrets; run the workflow once and confirm the deploy._

## 5. VPS — Tesseract OCR

- Installed **inside the Docker image** (runtime stage of `Dockerfile`) when Tess4J lands in step 3 — no host install.
- _TODO: (step 3) add Tesseract + `rus.traineddata` to the Dockerfile._

## 6. Home PC (Windows) — backup folder + agent autostart

- Choose the local **backup folder** where the agent mirrors documents (`BACKUP_DIR`).
- Build the agent fat JAR (`agent/target/home-docs-agent.jar`).
- Register a **Task Scheduler** task "at logon" running: `javaw -jar <path>\home-docs-agent.jar` (no console window).
- Provide the agent's env vars: `VPS_URL`, `AGENT_TOKEN`, `BACKUP_DIR`.
- _TODO: capture the exact command and working directory._

## 7. Home PC (Windows) — cold backup to the removable SSD

- Periodically and manually copy the backup folder to the removable SSD (robocopy/File History/any tool).
- _TODO: note the SSD drive letter / target path and the routine (e.g. weekly)._

## 8. Reminders — the notifier bot (sibling project `../notifier`)

Reminders for documents are created in the separate **notifier** bot through its HTTP API (see
`docs/PLAN.md` → "Reminders via notifier"). Both apps run on the same VPS, each in its own compose
stack, and talk over a shared external docker network.

- **Network `home-apps`.** Created by the deploy if missing
  (`docker network inspect home-apps >/dev/null 2>&1 || docker network create home-apps`); notifier's
  deploy does the same, so whoever deploys first creates it. The `server` service joins both `default`
  (for postgres) and `home-apps`; notifier's container is reachable there as `notifier-app`.
- **`.env` on the VPS** (`~/home-docs-registrar/.env`, never commit):
  ```
  NOTIFIER_URL=http://notifier-app:8080
  # MUST equal NOTIFIER_API_TOKEN in notifier's .env. Generate once: openssl rand -hex 32
  NOTIFIER_API_TOKEN=<64 hex chars>
  # notifier's @username (with or without @), for the «запусти @… и нажми /start» hint
  NOTIFIER_BOT_USERNAME=
  # optional: LLM reminder suggestions at recognition (default true); false saves a few tokens per document
  REMINDERS_SUGGEST=true
  ```
  Leave `NOTIFIER_URL` blank to keep reminders off — the bot then shows no reminder buttons/suggestions.
- **Each user** who wants reminders presses `/start` in the notifier bot once (the registrar shows a hint
  until they do). Reminders are personal: they go to whoever creates them.
- **Deep link back.** Reminders carry a «📄 Документ» link `https://t.me/<this bot>?start=doc_<id>`; the
  bot's username is read via `getMe` at runtime — nothing to configure.
- **Local run.** notifier listens on 8080 locally, so start the registrar on another port:
  `SERVER_PORT=8081`, with `NOTIFIER_URL=http://localhost:8080` and the same `NOTIFIER_API_TOKEN` as
  notifier.
