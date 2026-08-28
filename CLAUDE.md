# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read first

`docs/PLAN.md` is the source of truth for what this project is and why it's shaped this way (the
architecture, the key decisions, and the step-by-step roadmap). Read it before making changes.
`docs/SETUP.md` lists the one-time manual/out-of-band setup steps.

## Project state

`home-docs-registrar` is a personal registry for home paper documents (contracts, warranties,
receipts). A Telegram bot receives a photographed document, OCRs it, stores the text + fields in a
database, and files the digitized copy on a home Apple Time Capsule; the DB also records the physical
location (a card catalog whose sections carry QR codes).

It is a **two-module monorepo** (see `docs/PLAN.md` for the full picture):

- **`server/`** — runs on the VPS: Telegram bot, OCR, PostgreSQL registry, an on-disk store-and-forward
  queue, an HTTPS API for the agent, and a disk-space monitor.
- **`agent/`** — runs on the home Windows PC (not always on): dials OUT to the VPS over HTTPS (pull
  model), downloads queued files, and writes them to the Time Capsule over SMB3.

Because the home PC isn't always on and the capsule can't be a tunnel endpoint, files are buffered on
the VPS and flushed to the capsule when the PC is online. The pull model means the VPS needs **no
inbound access to the home network** (no VPN/port-forwarding). Implementation is in progress — most
domain code is still to be written; follow the roadmap in `docs/PLAN.md`.

## Stack

- **Java 21** (`<java.version>21</java.version>` in the root `pom.xml`)
- **Spring Boot 4.1.1** — the `server` module is a Spring Boot app; the root `pom.xml` is the Maven
  parent/aggregator and children inherit dependency/plugin management from the Spring Boot parent.
- **`server`**: `spring-boot-starter-web` now; `data-jpa` + `postgresql` (Step 2) and Tess4J OCR
  (Step 3) are added with their roadmap steps to keep each build green.
- **`agent`**: deliberately **not** a Spring app (minimal footprint on the home PC) — plain Java with
  `jcifs-ng` (SMB3) + Jackson, built into a runnable fat JAR via the shade plugin. HTTP uses the
  built-in `java.net.http` client.
- **Maven** via the bundled wrapper (`mvnw` / `mvnw.cmd`) — no system Maven required.
- Server configuration lives in `server/src/main/resources/application.yaml`.

## Commands

Targets Windows (PowerShell); the Bash tool is also available — use `mvnw.cmd` (PowerShell/cmd) or
`./mvnw` (Bash/POSIX). Run from the repo root.

```powershell
.\mvnw.cmd clean package                       # build & test both modules
.\mvnw.cmd -pl server -am spring-boot:run       # run the server (VPS app)
.\mvnw.cmd -pl server -am clean package         # build only the server (+ what it needs)
.\mvnw.cmd -pl agent  -am clean package         # build only the agent -> agent/target/home-docs-agent.jar
java -jar agent/target/home-docs-agent.jar      # run the agent (after packaging)

# Run a single server test class or method:
.\mvnw.cmd -pl server test "-Dtest=HomeDocsRegistrarApplicationTests"
.\mvnw.cmd -pl server test "-Dtest=HomeDocsRegistrarApplicationTests#contextLoads"
```

(Drop the `.cmd` and use `./mvnw` when running through the Bash tool.) There is no separate lint step;
compilation via `package`/`compile` is the only static check.

## Conventions

- Packages: server code under `com.example.homedocsregistrar`, agent code under
  `com.example.homedocsagent` — so each module's component scan / build picks it up.
- The root `pom.xml` keeps empty `<license>`, `<developers>`, and `<scm>` overrides to block unwanted
  inheritance from the Spring Boot parent (see `HELP.md`) — leave these unless intentionally
  populating that metadata.
- **Secrets never go in git** — Telegram token, DB password, and the agent API token are provided via
  environment variables (see `docs/SETUP.md`).
- Respect the queue invariants in `docs/PLAN.md` (delete-after-confirm, idempotency, persistence
  across restart, atomic writes) — they exist to prevent silent document loss.
