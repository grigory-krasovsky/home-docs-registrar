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
