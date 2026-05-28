# Sage IT Co — Backend

Spring Boot backend for the Sage IT Co participant lifecycle platform. Lifted from the Spire Info Tech codebase and rebranded via env-overridable `brand.*` defaults in `application.properties`.

## Quick start

```bash
cp .env.example .env   # fill in values
./mvnw spring-boot:run
```

Runs on http://localhost:8080.

## Notes

- **IMPORTANT: Replace `src/main/resources/sage_letterhead.pdf` with the actual Sage letterhead before going to production.** The current file is a placeholder copied from Spire's `templates/letterhead.pdf`.
- The original Spire letterhead lives at `src/main/resources/templates/letterhead.pdf` — keep until Sage letterhead is finalised, then delete.
- Java package remains `com.spire.backend` (renaming is a Day-N refactor; functionally inert).
- Brand strings in `src/main/resources/terms/v1.0.json` and seed user emails in `config/DataSeeder.java` and `resources/seed.sql` still say "Spire" — Day 2 cleanup.
