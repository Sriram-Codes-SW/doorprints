package app.doorprints.server.house;

import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HouseService {

    private final HouseRepository repo;
    private final VisitRepository visits;
    private final PhotoRepository photos;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final ApplicationEventPublisher events;

    public HouseService(HouseRepository repo, VisitRepository visits, PhotoRepository photos, SyncVersions versions,
                        ClientClock clock, ApplicationEventPublisher events) {
        this.repo = repo;
        this.visits = visits;
        this.photos = photos;
        this.versions = versions;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<HouseDto> list(Long since) {
        var houses = since == null
                ? repo.findByDeletedFalseOrderByUpdatedAtDesc()
                : repo.findBySyncVersionGreaterThanOrderBySyncVersion(since);
        return houses.stream().map(HouseDto::from).toList();
    }

    @Transactional(readOnly = true)
    public HouseDto get(UUID id) {
        return HouseDto.from(find(id));
    }

    /**
     * Create or update. Conflicts are resolved "last write wins" on the client's updatedAt (clamped by
     * {@link ClientClock}): an older edit arriving late (e.g. from an offline phone) doesn't overwrite a newer one.
     * A body with {@code deleted: true} is a delete (the Android app deletes this way) and purges the content.
     */
    @Transactional
    public HouseDto upsert(UUID id, HouseDto dto) {
        versions.lock(); // before reading, so the last-write-wins check and the write are atomic
        var incomingUpdatedAt = clock.accept(dto.updatedAt(), "updatedAt");
        var house = repo.findById(id).orElse(null);
        boolean created = house == null;
        if (created) {
            house = new House(id);
            house.setCreatedAt(clock.accept(dto.createdAt(), "createdAt"));
        } else if (house.getUpdatedAt().isAfter(incomingUpdatedAt)) {
            return HouseDto.from(house);
        }
        boolean wasDeleted = !created && house.isDeleted();
        dto.applyTo(house);
        house.setUpdatedAt(incomingUpdatedAt);
        long version = versions.next();
        house.setSyncVersion(version);
        if (house.isDeleted() && !wasDeleted) purge(house, version);
        var saved = repo.save(house);
        events.publishEvent(new HouseChangedEvent(id));
        return HouseDto.from(saved);
    }

    @Transactional
    public void delete(UUID id) {
        versions.lock();
        var house = find(id);
        if (house.isDeleted()) return;
        house.setDeleted(true);
        house.setUpdatedAt(clock.now());
        long version = versions.next();
        house.setSyncVersion(version);
        purge(house, version);
        events.publishEvent(new HouseChangedEvent(id));
    }

    /** Tombstone content purge (F-16): blank the row, tombstone its photos, unlink its visits. */
    private void purge(House house, long version) {
        var now = clock.now();
        house.purgeContent();
        photos.tombstoneAllOfHouse(house.getId(), now, version);
        visits.unlinkHouse(house.getId(), now, version);
    }

    @Transactional(readOnly = true)
    public List<HouseDto> nearby(double lat, double lon, double radius) {
        var rows = repo.findNearby(lat, lon, radius);
        var ids = rows.stream().map(r -> UUID.fromString((String) r[0])).toList();
        Map<UUID, House> byId = repo.findAllById(ids).stream()
                .collect(Collectors.toMap(House::getId, Function.identity()));
        return rows.stream()
                .map(r -> HouseDto.from(byId.get(UUID.fromString((String) r[0])), ((Number) r[1]).doubleValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HouseDto> onStreet(String street) {
        return repo.findLiveOnStreet(street.trim()).stream().map(HouseDto::from).toList();
    }

    House find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("House " + id + " not found"));
    }
}
