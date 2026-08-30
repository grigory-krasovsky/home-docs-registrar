# One-time setup (manual steps)

These are the manual, out-of-band steps the software itself cannot perform. Fill in the concrete
values (paths, DB URL) as they are decided. Detailed instructions are written in **Step 6** of
`docs/PLAN.md`; the sections below are placeholders so nothing is forgotten.

## 1. Home PC (Windows) — documents folder

- Choose the folder where digitized documents are stored (the agent's `TARGET_DIR`).
- Ensure the account running the agent can write there.
- _TODO: record the absolute path._

## 2. Home PC (Windows) — backup to the removable SSD

- Back up the documents folder to the removable SSD **periodically and manually** (not driven by the app):
  plug the SSD in and copy/mirror the folder (robocopy, File History, or any backup tool).
- _TODO: note the SSD drive letter / target path and the routine (e.g. weekly)._

## 3. VPS — Tesseract OCR

- Install native **Tesseract** and the **`rus`** language data (`rus.traineddata`).
- _TODO: record the `tessdata` path for `application.yaml`._

## 4. VPS — PostgreSQL

- Provision a PostgreSQL database + user for the server.
- _TODO: record datasource URL/user; keep the password in an env var._

## 5. VPS — server configuration & secrets

- Provide via environment variables (never commit): Telegram bot token, DB password, agent API bearer token,
  storage/queue directory, disk-space thresholds.
- _TODO: document the exact env var names once `application.yaml` is written (Step 3–4)._

## 6. Home PC (Windows) — agent autostart

- Build the agent fat JAR (`agent/target/home-docs-agent.jar`).
- Register a **Task Scheduler** task "at logon" running: `javaw -jar <path>\home-docs-agent.jar` (no console window).
- Provide the agent's env vars: `VPS_URL`, `AGENT_TOKEN`, `TARGET_DIR`.
- _TODO: capture the exact command and working directory._
