-- Copyright 2026 Sriram (Sriram-Codes-SW)
--
-- This file is part of Doorprints.
--
-- Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
-- Public License as published by the Free Software Foundation, version 3 of the License.
--
-- Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
-- warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
-- details.
--
-- You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
-- the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The Sprint 4b data model, slice 0 (docs/11 section 5.30, ADR-28). Every new kind of data of Sprint 4b (criteria,
-- questions, viewings, hunting areas, places, area notes, brokers, photo metadata, the move-in record, preferences)
-- is one row here, keyed by its kind and the client's id, with the payload stored opaquely: the server never reads
-- a payload, only stores and syncs it (GET /api/records?since=, PUT /api/records/{type}/{id}), so a new kind of
-- data costs no table, entity or endpoint. A delete leaves a tombstone (deleted = true, payload '{}') with a new
-- sync_version, purged by the daily job after app.privacy.tombstone-retention-days like the other tombstones.
-- Caps (per type at most 5 000 live rows, a payload at most 64 KiB) are enforced by the controller.
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
