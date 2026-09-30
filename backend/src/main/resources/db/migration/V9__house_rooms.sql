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

-- The Sprint 4b data model, slice 1c (docs/11 section 5.6, ADR-28): a house's rooms with sizes and condition, as one
-- JSON array (at most 30 objects; absent when there are none, never '[]'), stored as the client sent it like cost,
-- because nothing on the server computes with it. Blanked in the tombstone purge with the other house values.
ALTER TABLE house ADD COLUMN rooms jsonb;
