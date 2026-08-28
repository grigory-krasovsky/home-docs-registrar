# One-time setup (manual steps)

These are the manual, out-of-band steps the software itself cannot perform. Fill in the concrete
values (IPs, paths, share names) as they are decided. Detailed instructions are written in **Step 6**
of `docs/PLAN.md`; the sections below are placeholders so nothing is forgotten.

## 1. Time Capsule → SMB3 (from the home Mac)

- Install [TimeCapsuleSMB](https://github.com/jamesyc/TimeCapsuleSMB) from the home **Mac** (its installer is macOS-only).
  After deploy the capsule runs Samba 4 and speaks SMB3 permanently (on Gen5, flash the boot-hook for auto-start).
- **LAN-only** — never expose the capsule's SMB to the internet.
- _TODO: record capsule model/generation and confirm SMB3 reachable._

## 2. Fixed IP for the capsule (DHCP reservation)

- Reserve a static IP for the capsule on the router (mDNS/Bonjour name discovery is unreliable across NAT).
- _TODO: `CAPSULE_HOST = <fixed IP>`._

## 3. SMB share + credentials

- Choose/create the target share and a user the agent will authenticate as.
- _TODO: `CAPSULE_SHARE = <share>`, store credentials in the agent config (env), not in git._

## 4. VPS — Tesseract OCR

- Install native **Tesseract** and the **`rus`** language data (`rus.traineddata`).
- _TODO: record the `tessdata` path for `application.yaml`._

## 5. VPS — PostgreSQL

- Provision a PostgreSQL database + user for the server.
- _TODO: record datasource URL/user; keep the password in an env var._

## 6. VPS — server configuration & secrets

- Provide via environment variables (never commit): Telegram bot token, DB password, agent API bearer token,
  storage/queue directory, disk-space thresholds.
- _TODO: document the exact env var names once `application.yaml` is written (Step 3–4)._

## 7. Home PC (Windows) — agent autostart

- Build the agent fat JAR (`agent/target/home-docs-agent.jar`).
- Register a **Task Scheduler** task "at logon" running: `javaw -jar <path>\home-docs-agent.jar` (no console window).
- _TODO: capture the exact command and working directory._
