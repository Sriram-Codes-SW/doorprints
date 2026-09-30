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

package app.doorprints.server.house;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "house")
public class House {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String label;
    private String address;
    private String street;
    private String locality;

    @Column(nullable = false)
    private double lat;
    @Column(nullable = false)
    private double lon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HouseStatus status = HouseStatus.NEW;

    private Long price;
    private String priceType;
    private Integer bedrooms;
    private Integer rating;
    private String contactName;
    private String contactPhone;
    private String listingUrl;
    private String notes;
    /* Slice 1a of the Sprint 4b data model (V7): the house's own values, all optional. */
    private Integer areaSqft;
    private String locationSource;
    /** The {@link HouseCost} as compact JSON text, null for no cost; stored as sent, like a record payload. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String cost;
    /** Slice 1b (V8): the id of the house's broker, a record of type {@code broker}. No foreign key: it may dangle. */
    private String brokerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "house_checklist", joinColumns = @JoinColumn(name = "house_id"))
    @MapKeyColumn(name = "item")
    @Column(name = "score")
    private Map<String, Integer> checklist = new HashMap<>();

    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false)
    private boolean deleted;
    @Column(nullable = false)
    private long syncVersion;

    protected House() {
    }

    public House(UUID id) {
        this.id = id;
    }

    /**
     * Keeps only what a tombstone needs (id, deleted, timestamps, sync version) and drops the content: notes, contact
     * details, prices, the slice 1a values (area, location source, cost, broker) and the checklist (threat model F-16,
     * PRV-005). The location stays because the columns are NOT NULL, but it is reset to 0,0.
     */
    public void purgeContent() {
        label = "";
        address = null;
        street = null;
        locality = null;
        lat = 0;
        lon = 0;
        price = null;
        priceType = null;
        bedrooms = null;
        rating = null;
        contactName = null;
        contactPhone = null;
        listingUrl = null;
        notes = null;
        areaSqft = null;
        locationSource = null;
        cost = null;
        brokerId = null;
        checklist.clear();
    }

    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
    public String getLocality() { return locality; }
    public void setLocality(String locality) { this.locality = locality; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }
    public HouseStatus getStatus() { return status; }
    public void setStatus(HouseStatus status) { this.status = status; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public String getPriceType() { return priceType; }
    public void setPriceType(String priceType) { this.priceType = priceType; }
    public Integer getBedrooms() { return bedrooms; }
    public void setBedrooms(Integer bedrooms) { this.bedrooms = bedrooms; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getListingUrl() { return listingUrl; }
    public void setListingUrl(String listingUrl) { this.listingUrl = listingUrl; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Integer getAreaSqft() { return areaSqft; }
    public void setAreaSqft(Integer areaSqft) { this.areaSqft = areaSqft; }
    public String getLocationSource() { return locationSource; }
    public void setLocationSource(String locationSource) { this.locationSource = locationSource; }
    public String getCost() { return cost; }
    public void setCost(String cost) { this.cost = cost; }
    public String getBrokerId() { return brokerId; }
    public void setBrokerId(String brokerId) { this.brokerId = brokerId; }
    public Map<String, Integer> getChecklist() { return checklist; }
    public void setChecklist(Map<String, Integer> checklist) {
        this.checklist.clear();
        if (checklist != null) this.checklist.putAll(checklist);
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public long getSyncVersion() { return syncVersion; }
    public void setSyncVersion(long syncVersion) { this.syncVersion = syncVersion; }
}
