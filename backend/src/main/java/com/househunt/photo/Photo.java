package com.househunt.photo;

import jakarta.persistence.*;

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

    /** Turns this photo into a tombstone: bytes are dropped, the id stays so other devices can delete their copy. */
    public void markDeleted(Instant now, long syncVersion) {
        this.deleted = true;
        this.data = null;
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
}
