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

/**
 * Database row of one saved candidate (a house is the first kind, docs/03 §18): its kind, where it is, what it costs
 * ({@link Money} as two columns), the person's rating, notes and contact details, and the sync fields. The extras, the
 * rooms, the answers and the move-in are kept as JSON text and read through their own types. A deleted candidate stays
 * as a tombstone for sync with its content blanked ({@link #purgeContent}). The class keeps its name: only the public
 * surfaces were renamed by the format reset (S4b-BL-206).
 */
@Entity
@Table(name = "house")
public class House {

    /** The kind of every candidate this server makes on its own (docs/schemas/kinds/house.json). */
    public static final String HOUSE_KIND = "house";
    /** A kind id (docs/03 §18.4 door 3). */
    public static final String KIND_PATTERN = "^[a-z][a-z0-9_]{1,31}$";

    @Id
    private UUID id;

    @Column(nullable = false)
    private String kind = HOUSE_KIND;

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

    /** The {@link Money}'s amount, whole rupees; null when unknown. */
    private Long moneyAmount;
    /** The {@link Money}'s cadence; null when the candidate has no money at all. */
    private String moneyCadence;
    private Integer rating;
    private String contactName;
    private String contactPhone;
    private String listingUrl;
    private String notes;
    private String locationSource;
    /** The {@link CandidateExtras} as compact JSON text, null for none; stored as sent after validation. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String extras;
    /** Slice 1c: the {@link HouseRoom} list as compact JSON text, null for no rooms; stored as sent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String rooms;
    /** Slice 3a: the {@link HouseAnswer} list as compact JSON text, null for no answers; stored as sent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String answers;
    /** Slice 5: the {@link HouseMoveIn} object as compact JSON text, null for none; stored as sent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String moveIn;
    /** Slice 1b: the id of the house's broker, a record of type {@code broker}. No foreign key: it may dangle. */
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

    /** The floor's range (S4b-BL-87), the clients' {@code HouseValues.floor}: a basement level down to -5. */
    public static final int MIN_FLOOR = -5;
    public static final int MAX_FLOOR = 200;

    protected House() {
    }

    public House(UUID id) {
        this.id = id;
    }

    /**
     * Keeps only what a tombstone needs (id, kind, deleted, timestamps, sync version) and drops the content: notes,
     * contact details, the money, the extras (area, floor, BHK, cost and any extra this server does not know), the
     * location source, the broker, the rooms, the answers, the move-in and the checklist (threat model F-16, PRV-005).
     * The location stays because the columns are NOT NULL, but it is reset to 0,0.
     */
    public void purgeContent() {
        label = "";
        address = null;
        street = null;
        locality = null;
        lat = 0;
        lon = 0;
        moneyAmount = null;
        moneyCadence = null;
        rating = null;
        contactName = null;
        contactPhone = null;
        listingUrl = null;
        notes = null;
        locationSource = null;
        extras = null;
        rooms = null;
        answers = null;
        moveIn = null;
        brokerId = null;
        checklist.clear();
    }

    /** The money as one value: {@code null} when the row has no cadence (and so no money). */
    public Money getMoney() {
        return moneyCadence == null ? null : new Money(moneyAmount, moneyCadence);
    }

    public void setMoney(Money money) {
        this.moneyAmount = money == null ? null : money.amount();
        this.moneyCadence = money == null ? null : money.cadence();
    }

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind == null ? HOUSE_KIND : kind; }
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
    public String getLocationSource() { return locationSource; }
    public void setLocationSource(String locationSource) { this.locationSource = locationSource; }
    /** The {@link CandidateExtras} as JSON text ({@link CandidateExtras#parse}); null for none. */
    public String getExtras() { return extras; }
    public void setExtras(String extras) { this.extras = extras; }
    public String getRooms() { return rooms; }
    public void setRooms(String rooms) { this.rooms = rooms; }
    public String getAnswers() { return answers; }
    public void setAnswers(String answers) { this.answers = answers; }
    public String getMoveIn() { return moveIn; }
    public void setMoveIn(String moveIn) { this.moveIn = moveIn; }
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
