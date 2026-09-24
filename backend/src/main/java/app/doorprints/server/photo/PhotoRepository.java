package app.doorprints.server.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    /** Live photo ids of a house, oldest first. */
    @Query("select p.id from Photo p where p.houseId = :houseId and p.deleted = false order by p.createdAt")
    List<UUID> findIdsByHouseId(@Param("houseId") UUID houseId);

    @Query("select count(p) from Photo p where p.houseId = :houseId and p.deleted = false")
    long countLiveByHouseId(@Param("houseId") UUID houseId);

    /** Metadata changes (uploads and tombstones) after a sync version, without loading the bytes. */
    @Query("""
            select new app.doorprints.server.photo.PhotoDto(p.id, p.houseId, p.contentType, p.sizeBytes, p.createdAt,
                   p.updatedAt, p.deleted, p.syncVersion)
            from Photo p where p.syncVersion > :since order by p.syncVersion""")
    List<PhotoDto> findChangesSince(@Param("since") long since);

    /** Metadata of one photo (live or tombstone) without its bytes, or null. */
    @Query("""
            select new app.doorprints.server.photo.PhotoDto(p.id, p.houseId, p.contentType, p.sizeBytes, p.createdAt,
                   p.updatedAt, p.deleted, p.syncVersion)
            from Photo p where p.id = :id""")
    PhotoDto findMetadataById(@Param("id") UUID id);

    @Query("""
            select new app.doorprints.server.photo.PhotoDto(p.id, p.houseId, p.contentType, p.sizeBytes, p.createdAt,
                   p.updatedAt, p.deleted, p.syncVersion)
            from Photo p where p.deleted = false order by p.createdAt""")
    List<PhotoDto> findAllLiveMetadata();

    /** Tombstones every live photo of a house (house deleted). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Photo p set p.deleted = true, p.data = null, p.updatedAt = :now, p.syncVersion = :version
            where p.houseId = :houseId and p.deleted = false""")
    int tombstoneAllOfHouse(@Param("houseId") UUID houseId, @Param("now") Instant now, @Param("version") long version);

    @Modifying
    @Query("delete from Photo p where p.deleted = true and p.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);
}
