package com.househunt.house;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HouseRepository extends JpaRepository<House, UUID> {

    List<House> findByDeletedFalseOrderByUpdatedAtDesc();

    List<House> findBySyncVersionGreaterThanOrderBySyncVersion(long syncVersion);

    /** Case-insensitive street match written as {@code lower(street) = lower(?)} so it uses house_street_idx. */
    @Query(value = "select * from house where not deleted and lower(street) = lower(:street)", nativeQuery = true)
    List<House> findLiveOnStreet(@Param("street") String street);

    @Modifying
    @Query("delete from House h where h.deleted = true and h.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);

    /** Houses within {@code radius} metres, nearest first. Row = [id, distanceMetres]. */
    @Query(value = """
            select cast(h.id as varchar) as id,
                   ST_Distance(h.geog, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) as dist
            from house h
            where not h.deleted
              and ST_DWithin(h.geog, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)
            order by dist
            limit 50
            """, nativeQuery = true)
    List<Object[]> findNearby(@Param("lat") double lat, @Param("lon") double lon, @Param("radius") double radius);

    @Query(value = "select count(distinct lower(street)) from house where not deleted and street is not null",
            nativeQuery = true)
    long countDistinctStreets();

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(HouseStatus status);
}
