/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.record;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One opaque record of the Sprint 4b data model (docs/11 section 5.30, ADR-28): the server stores the payload as
 * the client sent it and never reads inside it. A tombstone keeps the key, timestamps and sync version with an
 * empty payload (PRV-005).
 */
@Entity
@Table(name = "record")
public class Record {

    @EmbeddedId
    private RecordKey key;
    /** A JSON object, compact; kept as text on the Java side because nothing here needs to look into it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false)
    private boolean deleted;
    @Column(nullable = false)
    private long syncVersion;

    protected Record() {
    }

    public Record(RecordKey key) {
        this.key = key;
    }

    public RecordKey getKey() { return key; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public long getSyncVersion() { return syncVersion; }
    public void setSyncVersion(long syncVersion) { this.syncVersion = syncVersion; }
}
