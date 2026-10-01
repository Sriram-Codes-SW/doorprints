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

-- S4b-BL-87: the floor the flat is on (0 the ground floor, -5..200, checked by HouseDto and the backup import), which
-- the duplicate-flat warning compares with the bedrooms and the point. Null when unknown; blanked in the tombstone purge.
ALTER TABLE house ADD COLUMN floor integer;
