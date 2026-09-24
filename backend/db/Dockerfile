# PostGIS 3.6 on PostgreSQL 18 + pgvector, for local dev and CI.
# Build: docker build -t doorprints-db backend/db
#
# postgis/postgis:18-3.6 is built FROM postgres:18-trixie (see github.com/postgis/docker-postgis,
# 18-3.6/Dockerfile), which already has the PGDG apt repository configured, so the matching
# pgvector package (0.8.x at the time of writing) installs straight from apt.
# Supabase and most managed Postgres offerings ship both extensions already.
FROM postgis/postgis:18-3.6

# Package installation needs root; the image switches to the unprivileged user right after.
RUN apt-get update \
    && apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-pgvector" \
    && rm -rf /var/lib/apt/lists/*

# Run as the postgres user (uid 999, created by the postgres:18 base image) instead of starting as root
# (Trivy DS-0002). This is the mode the official entrypoint documents as "allow the container to be started with
# --user" (docker-library/postgres 18/trixie/docker-entrypoint.sh): when not root it skips the chown/gosu step and
# runs initdb directly. It works because the base image pre-creates /var/lib/postgresql (the VOLUME, parent of
# PGDATA=/var/lib/postgresql/18/docker) owned by postgres with mode 1777 and /var/run/postgresql owned by postgres,
# and a fresh named or anonymous volume copies that ownership. Caveat: a host bind mount (or a volume first
# initialised by some other uid) must be owned by uid 999, because the entrypoint can no longer chown it.
USER postgres
