package com.househunt.privacy;

import com.househunt.config.AppProperties;
import com.househunt.house.HouseDto;
import com.househunt.house.HouseRepository;
import com.househunt.photo.PhotoDto;
import com.househunt.photo.PhotoRepository;
import com.househunt.sync.SyncVersions;
import com.househunt.visit.VisitDto;
import com.househunt.visit.VisitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Data-subject rights for the single user (FR-031 export, FR-032 erase, PRV-004/PRV-005 retention). */
@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);

    /** Everything the server holds, as JSON. Photo bytes are fetched separately via {@code photoUrl}. */
    public record Export(String format, Instant exportedAt, List<HouseDto> houses, List<VisitDto> visits,
                         List<ExportedPhoto> photos) {
    }

    public record ExportedPhoto(PhotoDto photo, String photoUrl) {
    }

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final PhotoRepository photos;
    private final SyncVersions versions;
    private final Duration retention;

    @PersistenceContext
    private EntityManager em;

    public DataService(HouseRepository houses, VisitRepository visits, PhotoRepository photos, SyncVersions versions,
                       AppProperties props) {
        this.houses = houses;
        this.visits = visits;
        this.photos = photos;
        this.versions = versions;
        this.retention = Duration.ofDays(props.privacy().tombstoneRetentionDays());
    }

    @Transactional(readOnly = true)
    public Export export() {
        var liveHouses = houses.findByDeletedFalseOrderByUpdatedAtDesc().stream().map(HouseDto::from).toList();
        var liveVisits = visits.findByDeletedFalseOrderByArrivedAtDesc().stream().map(VisitDto::from).toList();
        var livePhotos = photos.findAllLiveMetadata().stream()
                .map(p -> new ExportedPhoto(p, "/api/photos/" + p.id())).toList();
        return new Export("house-hunt-export/1", Instant.now(), liveHouses, liveVisits, livePhotos);
    }

    /**
     * Hard-deletes every house, visit, photo and AI index row. Devices keep their local copies (they only receive
     * changes, and hard deletes leave no tombstones), so the user clears app data on each device separately.
     */
    @Transactional
    public void deleteAll() {
        versions.lock(); // no sync write can interleave
        em.createNativeQuery("delete from photo").executeUpdate();
        em.createNativeQuery("delete from visit").executeUpdate();
        em.createNativeQuery("delete from house_checklist").executeUpdate();
        em.createNativeQuery("delete from house").executeUpdate();
        // The optional pgvector table (V2) only exists when the extension is installed.
        var hasVectorStore = em.createNativeQuery("select to_regclass('public.vector_store') is not null")
                .getSingleResult();
        if (Boolean.TRUE.equals(hasVectorStore)) em.createNativeQuery("delete from vector_store").executeUpdate();
        log.warn("privacy.delete-all: all houses, visits, photos and AI index rows were deleted");
    }

    /** Daily purge of tombstones older than the retention period (default 90 days). */
    @Scheduled(cron = "${app.privacy.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeTombstones() {
        versions.lock();
        var before = Instant.now().minus(retention);
        int p = photos.purgeTombstonesBefore(before);
        int v = visits.purgeTombstonesBefore(before);
        int h = houses.purgeTombstonesBefore(before);
        if (p + v + h > 0) log.info("privacy.purge tombstones: houses={} visits={} photos={}", h, v, p);
    }
}
