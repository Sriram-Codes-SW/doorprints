CREATE EXTENSION IF NOT EXISTS postgis;

-- Monotonic counter used for offline sync: every write gets a new value and
-- clients ask for "everything with sync_version > the last one I saw".
CREATE SEQUENCE sync_seq;

CREATE TABLE house (
    id             uuid PRIMARY KEY,
    label          varchar(200)     NOT NULL,
    address        varchar(500),
    street         varchar(200),
    locality       varchar(200),
    lat            double precision NOT NULL,
    lon            double precision NOT NULL,
    geog           geography(Point, 4326)
                   GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography) STORED,
    status         varchar(20)      NOT NULL DEFAULT 'NEW',
    price          bigint,
    price_type     varchar(10),
    bedrooms       integer,
    rating         integer,
    contact_name   varchar(200),
    contact_phone  varchar(50),
    listing_url    varchar(1000),
    notes          text,
    created_at     timestamptz      NOT NULL,
    updated_at     timestamptz      NOT NULL,
    deleted        boolean          NOT NULL DEFAULT false,
    sync_version   bigint           NOT NULL
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

CREATE TABLE photo (
    id           uuid PRIMARY KEY,
    house_id     uuid         NOT NULL REFERENCES house (id) ON DELETE CASCADE,
    content_type varchar(100) NOT NULL,
    data         bytea        NOT NULL,
    created_at   timestamptz  NOT NULL
);
CREATE INDEX photo_house_idx ON photo (house_id);
