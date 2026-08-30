# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read first

`docs/PLAN.md` is the source of truth for what this project is and why it's shaped this way (the
architecture, the key decisions, and the step-by-step roadmap). Read it before making changes.
`docs/SETUP.md` lists the one-time manual/out-of-band setup steps.

## Project state

`home-docs-registrar` is a personal registry for home paper documents (contracts, warranties,
receipts). A Telegram bot receives a photographed document, OCRs it, and stores the text + fields +
the file's Telegram `file_id` in a database; it also records the physical location (a card catalog
whose sections carry QR codes). The digitized file itself stays on **Telegram** (primary store,
retrieved by `file_id`) and is backed up to the home PC when it is online.

It is a **two-module monorepo** (see `docs/PLAN.md` for the full picture):

- **`server/`** — runs on the VPS: Telegram bot, OCR, PostgreSQL registry (metadata + `file_id`),
  file retrieval by `file_id`, and a backup-orchestration API for the agent. It holds **no file
  archive** (VPS disk is limited); it downloads a file from Telegram only transiently for OCR/relay.
- **`agent/`** — runs on the home Windows PC (not always on): dials OUT to the VPS over HTTPS (pull
  model) and mirrors documents (relayed from Telegram via the VPS) into a local backup folder.

Storage roles: **Telegram = primary**, VPS = registry/orchestrator, PC = owned backup (synced when
online), removable SSD = manual cold copy. The pull model means the VPS needs **no inbound access to
the home network** (no VPN/port-forwarding). Implementation is in progress — most domain code is
still to be written; follow the roadmap in `docs/PLAN.md`.

## Stack

- **Java 21** (`<java.version>21</java.version>` in the root `pom.xml`)
- **Spring Boot 4.1.1** — the `server` module is a Spring Boot app; the root `pom.xml` is the Maven
  parent/aggregator and children inherit dependency/plugin management from the Spring Boot parent.
- **`server`**: `spring-boot-starter-web` now; `data-jpa` + `postgresql` (Step 2) and Tess4J OCR
  (Step 3) are added with their roadmap steps to keep each build green.
- **`agent`**: deliberately **not** a Spring app (minimal footprint on the home PC) — plain Java with
  Jackson, built into a runnable fat JAR via the shade plugin. HTTP uses the built-in `java.net.http`
  client; it writes backups to the local filesystem (no SMB/network storage).
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

**Note:** the shell build needs `JAVA_HOME` pointing at a **JDK 21** (this machine's `JAVA_HOME`
defaults to a JDK 17, which fails with "release version 21 not supported"). IntelliJ builds are
unaffected (they use the IDE's configured SDK).

## Conventions

- Packages: server code under `com.example.homedocsregistrar`, agent code under
  `com.example.homedocsagent` — so each module's component scan / build picks it up.
- The root `pom.xml` keeps empty `<license>`, `<developers>`, and `<scm>` overrides to block unwanted
  inheritance from the Spring Boot parent (see `HELP.md`) — leave these unless intentionally
  populating that metadata.
- **Secrets never go in git** — Telegram token, DB password, and the agent API token are provided via
  environment variables (see `docs/SETUP.md`).
- Respect the sync invariants in `docs/PLAN.md` (mark-after-confirm, idempotency, persistence across
  restart, atomic writes) so the PC backup stays consistent.
