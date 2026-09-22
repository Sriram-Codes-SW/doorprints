package com.househunt.visit;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "visit")
public class Visit {

    @Id
    private UUID id;
    private UUID houseId;
    @Column(nullable = false)
    private double lat;
    @Column(nullable = false)
    private double lon;
    private String street;
    @Column(nullable = false)
    private Instant arrivedAt;
    private Instant leftAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisitSource source;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false)
    private boolean deleted;
    @Column(nullable = false)
    private long syncVersion;

    protected Visit() {
    }

    public Visit(UUID id) {
        this.id = id;
    }

    /** Tombstone: keep id, deleted flag, timestamps and sync version; drop where the user was (PRV-005). */
    public void purgePlace() {
        lat = 0;
        lon = 0;
        street = null;
        leftAt = null;
    }

    public UUID getId() { return id; }
    public UUID getHouseId() { return houseId; }
    public void setHouseId(UUID houseId) { this.houseId = houseId; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }
    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
    public Instant getArrivedAt() { return arrivedAt; }
    public void setArrivedAt(Instant arrivedAt) { this.arrivedAt = arrivedAt; }
    public Instant getLeftAt() { return leftAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
    public VisitSource getSource() { return source; }
    public void setSource(VisitSource source) { this.source = source; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public long getSyncVersion() { return syncVersion; }
    public void setSyncVersion(long syncVersion) { this.syncVersion = syncVersion; }
}
