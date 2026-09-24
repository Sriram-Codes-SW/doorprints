package app.doorprints.server.photo;

import app.doorprints.server.common.ConflictException;
import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.config.AppProperties;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.sync.SyncVersions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PhotoService {

    private final PhotoRepository photos;
    private final HouseRepository houses;
    private final SyncVersions versions;
    private final int maxPerHouse;

    public PhotoService(PhotoRepository photos, HouseRepository houses, SyncVersions versions, AppProperties props) {
        this.photos = photos;
        this.houses = houses;
        this.versions = versions;
        this.maxPerHouse = props.limits().maxPhotosPerHouse();
    }

    /**
     * Stores a photo. The client picks the id, so a retried upload (dropped connection) is not stored twice: an
     * existing id, live or deleted, returns without change (a deleted photo is never resurrected).
     */
    @Transactional
    public UUID upload(UUID houseId, UUID requestedId, byte[] bytes) {
        versions.lock();
        var photoId = requestedId == null ? UUID.randomUUID() : requestedId;
        var existing = photos.findMetadataById(photoId);
        if (existing != null) {
            if (!existing.houseId().equals(houseId)) throw new ConflictException("Photo id already used by another house");
            return photoId;
        }
        var house = houses.findById(houseId).filter(h -> !h.isDeleted())
                .orElseThrow(() -> new NotFoundException("House " + houseId + " not found"));
        if (photos.countLiveByHouseId(house.getId()) >= maxPerHouse) {
            throw new ConflictException("Photo limit reached: at most " + maxPerHouse + " photos per house");
        }
        var clean = ImageSanitizer.sanitize(bytes);
        photos.save(new Photo(photoId, houseId, clean.contentType(), clean.data(), Instant.now(), versions.next()));
        return photoId;
    }

    @Transactional(readOnly = true)
    public Photo getLive(UUID id) {
        return photos.findById(id).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("Photo " + id + " not found"));
    }

    /** Leaves a tombstone so other devices remove their copy (F-15). Deleting twice is fine. */
    @Transactional
    public void delete(UUID id) {
        versions.lock();
        var photo = photos.findById(id).orElse(null);
        if (photo == null || photo.isDeleted()) return;
        photo.markDeleted(Instant.now(), versions.next());
    }

    @Transactional(readOnly = true)
    public List<UUID> liveIds(UUID houseId) {
        return photos.findIdsByHouseId(houseId);
    }

    @Transactional(readOnly = true)
    public List<PhotoDto> changesSince(long since) {
        return photos.findChangesSince(since);
    }
}
