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
