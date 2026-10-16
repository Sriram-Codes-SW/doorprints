# Doorprints server (`backend/`)

The optional self-hosted server of Doorprints. The apps work fully without it (each keeps its data on the device); a person
who runs one gets a sync hub for the phones and the website, photo storage, geospatial queries, export and delete-all, and
optional AI. Java 25, Spring Boot 4.1, PostgreSQL with PostGIS (and pgvector for the AI index), Flyway. Where it sits in the
whole system: [docs/16](../docs/16-architecture-overview.md).

## Layout

- `src/main/java/app/doorprints/server/`: `DoorprintsApplication.java`; one package per area: `house` (the `/api/candidates`
  API), `visit`, `photo`, `record` (the typed record envelope), `backup` (export and import), `privacy` (delete-all, tombstone
  purge), `device` (pairing and the owner page), `secrets`, `sync` (clock guard, sync versions), `ai` (optional), `config`
  (API key, rate limit, size limit and security-header filters).
- `src/main/resources/db/migration/V1__init.sql`: the schema, **one migration** (the earlier V1..V14 were squashed on
  2026-10-12; a change from now on is a new `V2__...sql`). `application.yml` lists every setting and its environment variable.
- `src/main/resources/kinds/`: the kind files the server reads (`house.json`, `school.json`: byte-identical copies of
  `docs/schemas/kinds/`, copy after an edit there) and the prompt packs of those two (`<id>.prompts.json`). A kind needs its
  file and nothing in Java ([docs/03](../docs/03-design.md) §18.22); `src/test/resources/kinds/dojo.json` is the test kind.
- `db/Dockerfile`: the PostGIS image with pgvector that CI and the compose file use. `Dockerfile`: the API image.

## Run it

From the repository root, with Docker (the full steps are in the root [README](../README.md) under *Run the API locally*):

```bash
export APP_API_KEY=$(openssl rand -hex 32)   # refuses to start without a key of 32+ characters
docker compose up --build                    # PostGIS + pgvector and the API on http://127.0.0.1:8080
curl -H "X-API-Key: $APP_API_KEY" http://127.0.0.1:8080/api/stats
```

## Test it

`mvn -B -ntp verify` in this folder, with JDK 25 and a PostGIS database on port 5432. CI (`.github/workflows/backend.yml`)
builds `backend/db`, starts it as `doorprints-db:ci` with user, password and database `doorprints`, then runs
`mvn -B -ntp verify` with `DB_URL=jdbc:postgresql://127.0.0.1:5432/doorprints`, `DB_USER=doorprints`, `DB_PASSWORD=doorprints`,
`APP_AI_ENABLED=false` and `APP_MCP_ENABLED=false`. The workflow also reads `docs/schemas/**` and `docs/ai/evals/**`, so a change
there runs the backend tests. How a session runs this locally: [docs/14](../docs/14-lead-backlog-and-handoff.md) §7.

## Configuration worth knowing

All are environment variables read by `application.yml` (passed through by `docker-compose.yml`); the full list and the
deploy values are in [docs/07](../docs/07-secure-build-and-deploy.md) §7.

| Variable (property) | Meaning |
|---|---|
| `APP_API_KEY` (`app.api-key`) | The shared key clients send as `X-API-Key`; `APP_API_KEY_NEXT` is used to rotate it |
| `APP_CORS_ORIGINS` | Origins allowed to call the API from a browser (default `http://localhost:4200`) |
| `KINDS_AVAILABLE` (`app.kinds.available`) | Comma-separated kind ids this server reads in the AI; default `house` (the house is always in); a request for another kind is a 400 ([docs/03](../docs/03-design.md) §18.15). A listed kind is read from its kind file: `src/main/resources/kinds/<id>.json`, a byte-identical copy of `docs/schemas/kinds/<id>.json` (`KindFileResourcesTest`), validated at start by `KindFiles` (§18.22); a kind with no file there is read as a house |
| `APP_AI_ENABLED`, `AI_PROVIDER`, `AI_API_KEY` | AI is off unless `APP_AI_ENABLED=true`; `AI_PROVIDER` is `aistudio` (default) or `vertex` ([docs/ai/ai-design.md](../docs/ai/ai-design.md)) |
| `APP_MCP_ENABLED` | The MCP endpoint `/mcp`, off by default |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | The PostgreSQL connection |
| `TOMBSTONE_RETENTION_DAYS` | How long deleted rows are kept before the daily purge (default 90) |

## Read next

[docs/03](../docs/03-design.md) §4.1 (API components), §6.1 (server ERD), §9 (API reference), §10 (sync), §12.1 (device keys and
the owner page); [docs/ai/ai-design.md](../docs/ai/ai-design.md); [docs/07](../docs/07-secure-build-and-deploy.md) (pipeline and
deployment); [docs/08](../docs/08-operations-runbook.md) (operations).
