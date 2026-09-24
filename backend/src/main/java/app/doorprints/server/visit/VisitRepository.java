package app.doorprints.server.visit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByDeletedFalseOrderByArrivedAtDesc();

    List<Visit> findBySyncVersionGreaterThanOrderBySyncVersion(long syncVersion);

    List<Visit> findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(UUID houseId);

    long countByDeletedFalse();

    /** A deleted house's visits stay in the history but lose the link (and get a new sync version). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Visit v set v.houseId = null, v.updatedAt = :now, v.syncVersion = :version
            where v.houseId = :houseId""")
    int unlinkHouse(@Param("houseId") UUID houseId, @Param("now") Instant now, @Param("version") long version);

    @Modifying
    @Query("delete from Visit v where v.deleted = true and v.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);
}
