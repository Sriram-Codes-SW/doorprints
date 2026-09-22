# PostGIS 3.6 on PostgreSQL 18 + pgvector, for local dev and CI.
# Build: docker build -t house-hunt-db backend/db
#
# postgis/postgis:18-3.6 is built FROM postgres:18-trixie (see github.com/postgis/docker-postgis,
# 18-3.6/Dockerfile), which already has the PGDG apt repository configured, so the matching
# pgvector package (0.8.x at the time of writing) installs straight from apt.
# Supabase and most managed Postgres offerings ship both extensions already.
FROM postgis/postgis:18-3.6

RUN apt-get update \
    && apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-pgvector" \
    && rm -rf /var/lib/apt/lists/*
