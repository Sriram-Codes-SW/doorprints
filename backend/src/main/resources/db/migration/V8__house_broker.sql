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

-- The Sprint 4b data model, slice 1b (docs/11 section 5.25, ADR-28): a house may name its broker. broker_id is the
-- id of a record of type 'broker' (see V6). There is no foreign key on purpose: records sync on their own cursor, so a
-- house can arrive before its broker, and a deleted broker leaves the id dangling, which readers take as no broker.
ALTER TABLE house ADD COLUMN broker_id varchar(64);
