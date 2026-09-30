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

-- The Sprint 4b data model, slice 1a (docs/11 sections 5.21 and 5.30 item 1, ADR-28, FR-068): the house's own values.
-- All three are optional and absent for a house saved before this. The carpet area is what the person wrote (sq ft);
-- location_source is GPS, MAP or APPROX (an approximate spot: a hollow marker, never a Hunt-mode alert); cost is the
-- eleven money fields of docs/11 section 5.21 as one JSON object (absent fields left out, never '{}'), stored as the
-- client sent it like a record payload, because nothing on the server computes with it.
ALTER TABLE house ADD COLUMN area_sqft       integer;
ALTER TABLE house ADD COLUMN location_source varchar(10);
ALTER TABLE house ADD COLUMN cost            jsonb;
