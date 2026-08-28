# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This is a freshly scaffolded Spring Boot application (from Spring Initializr). Apart from the
generated entry point and a context-load smoke test, there is no domain code yet — the name
("home-docs-registrar") suggests it is intended to register/manage home documents, but no
controllers, services, entities, or persistence layer exist. Expect to build these from scratch.

## Stack

- **Java 21** (`<java.version>21</java.version>` in `pom.xml`)
- **Spring Boot 4.1.1** — currently only `spring-boot-starter` (core, no web/data/security starters yet;
  add the relevant starter to `pom.xml` when introducing REST, persistence, etc.)
- **Maven** via the bundled wrapper (`mvnw` / `mvnw.cmd`) — no system Maven required
- Configuration lives in `src/main/resources/application.yaml`

## Commands

The project targets Windows (PowerShell) but the Bash tool is also available; use the wrapper for
your shell — `mvnw.cmd` (PowerShell/cmd) or `./mvnw` (Bash/POSIX).

```powershell
.\mvnw.cmd clean package            # compile, run tests, build the jar into target/
.\mvnw.cmd spring-boot:run          # run the application locally
.\mvnw.cmd test                     # run all tests
# Run a single test class or method:
.\mvnw.cmd test "-Dtest=HomeDocsRegistrarApplicationTests"
.\mvnw.cmd test "-Dtest=HomeDocsRegistrarApplicationTests#contextLoads"
```

(Drop the `.cmd` and use `./mvnw` when running through the Bash tool.)

There is no separate lint step configured; compilation via `mvnw.cmd package`/`compile` is the
only static check.

## Conventions

- Base package: `com.example.homedocsregistrar`. New code (controllers, services, config) should live
  under this package or subpackages so Spring Boot's component scan (rooted at
  `HomeDocsRegistrarApplication`) picks it up automatically.
- `pom.xml` deliberately keeps empty `<license>`, `<developers>`, and `<scm>` overrides to block
  unwanted inheritance from the Spring Boot parent POM (see `HELP.md`) — leave these unless
  intentionally populating that metadata.
