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

package app.doorprints.server.photo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "photo")
public class Photo {

    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID houseId;
    @Column(nullable = false)
    private String contentType;
    /** Null for tombstones. Without bytecode enhancement LAZY is only a hint, so list queries use projections. */
    @Basic(fetch = FetchType.LAZY)
    private byte[] data;
    private Integer sizeBytes;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false)
    private boolean deleted;
    @Column(nullable = false)
    private long syncVersion;
    /* Slice 5 (V12): what the person says about the photo; all blank on a tombstone. */
    private String roomId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String tags;
    private String caption;
    @Column(nullable = false)
    private long metaUpdatedAt;

    protected Photo() {
    }

    public Photo(UUID id, UUID houseId, String contentType, byte[] data, Instant now, long syncVersion) {
        this.id = id;
        this.houseId = houseId;
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
        this.createdAt = now;
        this.updatedAt = now;
        this.syncVersion = syncVersion;
    }

    /** Turns this photo into a tombstone: bytes and the person's room, tags and caption are dropped, the id stays so other devices can delete their copy. */
    public void markDeleted(Instant now, long syncVersion) {
        this.deleted = true;
        this.data = null;
        this.roomId = null;
        this.tags = null;
        this.caption = null;
        this.metaUpdatedAt = 0;
        this.updatedAt = now;
        this.syncVersion = syncVersion;
    }

    public UUID getId() { return id; }
    public UUID getHouseId() { return houseId; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
    public Integer getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isDeleted() { return deleted; }
    public long getSyncVersion() { return syncVersion; }
    public String getRoomId() { return roomId; }
    public String getTags() { return tags; }
    public String getCaption() { return caption; }
    public long getMetaUpdatedAt() { return metaUpdatedAt; }
}
