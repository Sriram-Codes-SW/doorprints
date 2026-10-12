-- The whole schema in one migration: the one-time format reset of S4b-BL-206 (docs/03 ADR-36 and §18, kinds PR 2)
-- squashed the fourteen migrations V1..V14 into this file. No server had been deployed with data (the owner's test
-- servers are wiped), so nothing is migrated: a database made by the old V1..V14 is dropped and created again
-- (Flyway refuses it: the checksums and the missing versions differ). From here on a change is a new file again.

CREATE EXTENSION IF NOT EXISTS postgis;

-- Monotonic counter used for offline sync: every write gets a new value and
-- clients ask for "everything with sync_version > the last one I saw".
CREATE SEQUENCE sync_seq;

-- One candidate per row (a house is the first kind; docs/03 §18). The table keeps its name `house`: only the public
-- surfaces were renamed by the reset (the wire's `candidates`, `/api/candidates`, `candidateId`).
--   kind           the kind id (`house`), ^[a-z][a-z0-9_]{1,31}$ (docs/03 §18.4 door 3).
--   money_*        the price as an amount (whole rupees, null when unknown) and its cadence (`month` for a rent,
--                  `once` for a sale; one of once, day, week, month, quarter, year); both null when neither is known.
--   extras         the kind's typed extras as one JSON object keyed by extra id (for a house: bedrooms, areaSqft,
--                  floor and the eleven cost fields, a money extra as {"amount", "cadence"}), stored as the client
--                  sent it after validation, ids this server does not know included (door 5); null when empty,
--                  never '{}'.
--   location_source GPS, MAP or APPROX (FR-068); null when unknown.
--   rooms, answers, move_in  the nested lists and the moving-in card as JSON, stored as sent (null for none).
--   broker_id      a record id of type `broker`; no foreign key on purpose (records sync on their own cursor).
-- A tombstone blanks every content column (threat model F-16, PRV-005; `House.purgeContent`).
CREATE TABLE house (
    id              uuid PRIMARY KEY,
    kind            varchar(32)      NOT NULL DEFAULT 'house',
    label           varchar(200)     NOT NULL,
    address         varchar(500),
    street          varchar(200),
    locality        varchar(200),
    lat             double precision NOT NULL,
    lon             double precision NOT NULL,
    geog            geography(Point, 4326)
                    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography) STORED,
    status          varchar(20)      NOT NULL DEFAULT 'NEW',
    money_amount    bigint,
    money_cadence   varchar(10),
    rating          integer,
    contact_name    varchar(200),
    contact_phone   varchar(50),
    listing_url     varchar(1000),
    notes           text,
    location_source varchar(10),
    extras          jsonb,
    rooms           jsonb,
    answers         jsonb,
    move_in         jsonb,
    broker_id       varchar(64),
    created_at      timestamptz      NOT NULL,
    updated_at      timestamptz      NOT NULL,
    deleted         boolean          NOT NULL DEFAULT false,
    sync_version    bigint           NOT NULL
);
CREATE INDEX house_geog_idx ON house USING gist (geog);
CREATE INDEX house_sync_idx ON house (sync_version);
CREATE INDEX house_street_idx ON house (lower(street));

CREATE TABLE house_checklist (
    house_id uuid         NOT NULL REFERENCES house (id) ON DELETE CASCADE,
    item     varchar(100) NOT NULL,
    score    integer      NOT NULL,
    PRIMARY KEY (house_id, item)
);

-- A visit's candidate is `house_id` (`candidateId` on the wire); null when the visit is at a place that is not a
-- candidate, or after its candidate was deleted (the delete unlinks it). A deleted visit keeps only what a tombstone
-- needs (id, deleted, updated_at, sync_version); the place is wiped.
CREATE TABLE visit (
    id           uuid PRIMARY KEY,
    house_id     uuid REFERENCES house (id) ON DELETE SET NULL,
    lat          double precision NOT NULL,
    lon          double precision NOT NULL,
    geog         geography(Point, 4326)
                 GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography) STORED,
    street       varchar(200),
    arrived_at   timestamptz      NOT NULL,
    left_at      timestamptz,
    source       varchar(10)      NOT NULL,
    updated_at   timestamptz      NOT NULL,
    deleted      boolean          NOT NULL DEFAULT false,
    sync_version bigint           NOT NULL
);
CREATE INDEX visit_geog_idx ON visit USING gist (geog);
CREATE INDEX visit_sync_idx ON visit (sync_version);
CREATE INDEX visit_house_idx ON visit (house_id);

-- Photos are part of offline sync: a delete leaves a tombstone (deleted = true, bytes removed) with a new
-- sync_version, so other devices learn about it via GET /api/photos?since=<cursor>. Tombstones are purged by the
-- scheduled TombstonePurgeJob after app.privacy.tombstone-retention-days (default 90). What a person says about a
-- photo (docs/11 section 5.7): room_id names a room of the candidate (no foreign key: rooms live in the candidate's
-- JSON and may be gone), tags is a JSON array of at most 10 strings, caption at most 200 characters, and
-- meta_updated_at (epoch ms, 0 = never edited) decides last-write-wins between devices. A tombstone blanks all four.
CREATE TABLE photo (
    id              uuid PRIMARY KEY,
    house_id        uuid         NOT NULL REFERENCES house (id) ON DELETE CASCADE,
    content_type    varchar(100) NOT NULL,
    data            bytea,
    created_at      timestamptz  NOT NULL,
    deleted         boolean      NOT NULL DEFAULT false,
    size_bytes      integer,
    updated_at      timestamptz  NOT NULL,
    sync_version    bigint       NOT NULL,
    room_id         varchar(64),
    tags            jsonb,
    caption         varchar(200),
    meta_updated_at bigint       NOT NULL DEFAULT 0
);
CREATE INDEX photo_house_idx ON photo (house_id);
CREATE INDEX photo_sync_idx ON photo (sync_version);

-- Optional AI "Ask my house hunt" (RAG) storage. See docs/ai/ai-design.md.
--
-- The table layout mirrors what Spring AI 2.0.1's PgVectorStore reads and writes
-- (PgVectorStore#createObjectsInDatabase): id uuid PK, content text, metadata json,
-- embedding vector(N) plus an HNSW cosine index named spring_ai_vector_index.
-- One row per candidate; id = candidate id so re-indexing is an upsert (ON CONFLICT (id)).
--
-- The dimension is FIXED at 768 here and must equal AI_EMBEDDING_DIMENSIONS
-- (gemini-embedding-2 / gemini-embedding-001 with output dimensionality 768, or
-- Ollama nomic-embed-text which is natively 768).
--
-- Environments without the pgvector extension (e.g. a plain PostGIS image) must keep
-- working with AI disabled, so everything is guarded: if "vector" is not available this
-- part is a no-op. If you install pgvector later, either set
-- AI_VECTOR_INIT_SCHEMA=true once or run the statements below by hand.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS vector';
        EXECUTE $ddl$
            CREATE TABLE IF NOT EXISTS vector_store (
                id        uuid PRIMARY KEY,
                content   text,
                metadata  json,
                embedding vector(768)
            )
        $ddl$;
        EXECUTE 'CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops)';
    ELSE
        RAISE NOTICE 'pgvector extension not available: skipping vector_store (AI RAG features need it)';
    END IF;
END
$$;

-- Device keys, pairing and the owner page (docs/03 §12.1, ADR-25). Every secret is stored as its SHA-256 hash only:
-- a copy of this database gives no working key, code, invite or session.

-- One row per connected app install. ai_allowed: the owner's per-device AI switch, off for a new device.
CREATE TABLE device_key (
    id           uuid PRIMARY KEY,
    name         varchar(60)  NOT NULL,
    key_hash     bytea        NOT NULL UNIQUE,
    key_last4    varchar(4)   NOT NULL,
    via          varchar(10)  NOT NULL CHECK (via IN ('code', 'invite')),
    ai_allowed   boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

-- A device waiting for the owner to type its code (RFC 8628 in small). The device polls with poll_hash's token.
-- client_hash (S4b-BL-161, docs/03 section 12.1): a salted SHA-256 of the client address, never the address, so at
-- most a few requests per source stay open. The salt is made at each start of the server, so the hash links only
-- requests of one run (they live 10 minutes).
CREATE TABLE pairing_request (
    id           uuid PRIMARY KEY,
    user_code    varchar(8)   NOT NULL,
    poll_hash    bytea        NOT NULL UNIQUE,
    device_name  varchar(60)  NOT NULL,
    status       varchar(10)  NOT NULL CHECK (status IN ('pending', 'approved', 'denied', 'claimed')),
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    device_id    uuid REFERENCES device_key (id) ON DELETE SET NULL,
    client_hash  bytea
);
CREATE INDEX pairing_request_code_idx ON pairing_request (user_code) WHERE status = 'pending';
CREATE INDEX pairing_request_client_idx ON pairing_request (client_hash) WHERE status = 'pending';

-- One-time invites the owner page shows as a QR code or a link.
CREATE TABLE pairing_invite (
    id           uuid PRIMARY KEY,
    token_hash   bytea        NOT NULL UNIQUE,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    device_id    uuid REFERENCES device_key (id) ON DELETE SET NULL
);

-- Owner page access: one-time setup links (kind 'setup') and signed-in browsers (kind 'session').
CREATE TABLE owner_token (
    id           uuid PRIMARY KEY,
    kind         varchar(10)  NOT NULL CHECK (kind IN ('setup', 'session')),
    token_hash   bytea        NOT NULL UNIQUE,
    label        varchar(120),
    created_at   timestamptz  NOT NULL,
    last_used_at timestamptz,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    revoked_at   timestamptz
);

-- Secrets and settings the owner sets on the owner page (docs/03 §12.1, ADR-25): the Gemini key, stored encrypted
-- with AES-256-GCM under a key derived from the server's owner key (APP_API_KEY), so a copy of this database alone
-- does not reveal it; and plain switches such as "AI paused".
CREATE TABLE server_secret (
    name         varchar(40)  PRIMARY KEY,
    ciphertext   bytea        NOT NULL,
    last4        varchar(4)   NOT NULL,
    updated_at   timestamptz  NOT NULL
);

CREATE TABLE server_setting (
    name         varchar(40)  PRIMARY KEY,
    value        varchar(200) NOT NULL,
    updated_at   timestamptz  NOT NULL
);

-- The Sprint 4b data model (docs/11 section 5.30, ADR-28). Every kind of data other than candidates, visits and
-- photos (criteria, questions, viewings, hunting areas, places, area notes, brokers, preferences) is one row here,
-- keyed by its type and the client's id, with the payload stored opaquely: the server never reads a payload, only
-- stores and syncs it (GET /api/records?since=, PUT /api/records/{type}/{id}), so a new kind of data costs no table,
-- entity or endpoint. (The one exception is a viewing's `candidateId`, which the AI index and the backup read.) A
-- delete leaves a tombstone (deleted = true, payload '{}') with a new sync_version, purged by the daily job after
-- app.privacy.tombstone-retention-days like the other tombstones. Caps (per type at most 5 000 live rows, a payload
-- at most 64 KiB) are enforced by the controller.
CREATE TABLE record (
    type         varchar(40)  NOT NULL,
    id           varchar(64)  NOT NULL,
    payload      jsonb        NOT NULL DEFAULT '{}',
    updated_at   timestamptz  NOT NULL,
    deleted      boolean      NOT NULL DEFAULT false,
    sync_version bigint       NOT NULL,
    PRIMARY KEY (type, id)
);

CREATE INDEX record_sync_idx ON record (sync_version);
