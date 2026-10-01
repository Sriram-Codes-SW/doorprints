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

-- Slice 5 (docs/11 section 5.7, ADR-28): what a person says about a photo. room_id names a room of the house (no
-- foreign key: rooms live in the house's JSON and may be gone), tags is a JSON array of at most 10 strings, caption is
-- at most 200 characters, and meta_updated_at (epoch ms, 0 = never edited) decides last-write-wins between devices.
-- A tombstone blanks all four (the caption is the person's own words).
ALTER TABLE photo ADD COLUMN room_id         varchar(64);
ALTER TABLE photo ADD COLUMN tags            jsonb;
ALTER TABLE photo ADD COLUMN caption         varchar(200);
ALTER TABLE photo ADD COLUMN meta_updated_at bigint NOT NULL DEFAULT 0;
