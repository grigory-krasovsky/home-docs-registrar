# One-time setup (manual steps)

These are the manual, out-of-band steps the software itself cannot perform. Fill in the concrete
values (paths, DB URL, tokens, ids) as they are decided. Detailed instructions are written in
**Step 6** of `docs/PLAN.md`; the sections below are placeholders so nothing is forgotten.

## 1. Telegram bot

- Create the bot via @BotFather; keep the **bot token** in an env var (never commit it).
- Note the Bot API limits: download ≤ ~20 MB, send ≤ ~50 MB — normalize scans to a compact PDF at ingest.
- _TODO: record the bot username; store the token as an env var._

## 2. Telegram — private archive channel (primary store)

- Create a **private channel** from your own account (bots cannot create channels — so you are the owner).
- **Add the bot as an admin** with "Post Messages" permission (member-only is not enough for a bot to post).
- It can contain just you (owner) + the bot. You keep a browsable view of all archived documents.
- Capture the channel's numeric **chat_id** (looks like `-100…`) for the server config (`ARCHIVE_CHANNEL_ID`):
  after adding the bot as admin, post any message in the channel and the bot receives it as a channel post with the id.
- _TODO: record `ARCHIVE_CHANNEL_ID`._

## 3. VPS — PostgreSQL

- Provision a PostgreSQL database + user for the server (stores metadata + `file_id`/`channel_message_id`, not the files).
- _TODO: record datasource URL/user; keep the password in an env var._

## 4. VPS — Tesseract OCR

- Install native **Tesseract** and the **`rus`** language data (`rus.traineddata`).
- _TODO: record the `tessdata` path for `application.yaml`._

## 5. VPS — server configuration & secrets

- Provide via environment variables (never commit): Telegram bot token, `ARCHIVE_CHANNEL_ID`, DB password, agent API bearer token.
- _TODO: document the exact env var names once `application.yaml` is written (Step 3–4)._

## 6. Home PC (Windows) — backup folder + agent autostart

- Choose the local **backup folder** where the agent mirrors documents (`BACKUP_DIR`).
- Build the agent fat JAR (`agent/target/home-docs-agent.jar`).
- Register a **Task Scheduler** task "at logon" running: `javaw -jar <path>\home-docs-agent.jar` (no console window).
- Provide the agent's env vars: `VPS_URL`, `AGENT_TOKEN`, `BACKUP_DIR`.
- _TODO: capture the exact command and working directory._

## 7. Home PC (Windows) — cold backup to the removable SSD

- Periodically and manually copy the backup folder to the removable SSD (robocopy/File History/any tool).
- _TODO: note the SSD drive letter / target path and the routine (e.g. weekly)._
