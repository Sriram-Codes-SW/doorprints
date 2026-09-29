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

-- Device keys, pairing and the owner page (docs/03 §12.1, ADR-25). Every secret is stored as its SHA-256 hash only:
-- a copy of this database gives no working key, code, invite or session.

-- One row per connected app install. ai_allowed: the owner's per-device AI switch, off for a new device.
CREATE TABLE device_key (
    id           uuid PRIMARY KEY,
    name         varchar(60)  NOT NULL,
    key_hash     bytea        NOT NULL UNIQUE,
    key_last4    varchar(4)   NOT NULL,
    via          varchar(10)  NOT NULL CHECK (via IN ('code', 'invite')),
    ai_allowed   boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

-- A device waiting for the owner to type its code (RFC 8628 in small). The device polls with poll_hash's token.
CREATE TABLE pairing_request (
    id           uuid PRIMARY KEY,
    user_code    varchar(8)   NOT NULL,
    poll_hash    bytea        NOT NULL UNIQUE,
    device_name  varchar(60)  NOT NULL,
    status       varchar(10)  NOT NULL CHECK (status IN ('pending', 'approved', 'denied', 'claimed')),
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    device_id    uuid REFERENCES device_key (id) ON DELETE SET NULL
);
CREATE INDEX pairing_request_code_idx ON pairing_request (user_code) WHERE status = 'pending';

-- One-time invites the owner page shows as a QR code or a link.
CREATE TABLE pairing_invite (
    id           uuid PRIMARY KEY,
    token_hash   bytea        NOT NULL UNIQUE,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    device_id    uuid REFERENCES device_key (id) ON DELETE SET NULL
);

-- Owner page access: one-time setup links (kind 'setup') and signed-in browsers (kind 'session').
CREATE TABLE owner_token (
    id           uuid PRIMARY KEY,
    kind         varchar(10)  NOT NULL CHECK (kind IN ('setup', 'session')),
    token_hash   bytea        NOT NULL UNIQUE,
    label        varchar(120),
    created_at   timestamptz  NOT NULL,
    last_used_at timestamptz,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    revoked_at   timestamptz
);
