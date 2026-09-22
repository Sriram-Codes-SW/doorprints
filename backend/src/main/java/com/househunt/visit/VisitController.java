package com.househunt.visit;

import com.househunt.common.NotFoundException;
import com.househunt.house.HouseChangedEvent;
import com.househunt.sync.ClientClock;
import com.househunt.sync.SyncVersions;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/visits")
public class VisitController {

    private final VisitRepository repo;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final ApplicationEventPublisher events;

    public VisitController(VisitRepository repo, SyncVersions versions, ClientClock clock,
                           ApplicationEventPublisher events) {
        this.repo = repo;
        this.versions = versions;
        this.clock = clock;
        this.events = events;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<VisitDto> list(@RequestParam(required = false) Long since,
                               @RequestParam(required = false) UUID houseId) {
        List<Visit> visits;
        if (since != null) visits = repo.findBySyncVersionGreaterThanOrderBySyncVersion(since);
        else if (houseId != null) visits = repo.findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(houseId);
        else visits = repo.findByDeletedFalseOrderByArrivedAtDesc();
        return visits.stream().map(VisitDto::from).toList();
    }

    @PutMapping("/{id}")
    @Transactional
    public VisitDto upsert(@PathVariable UUID id, @Valid @RequestBody VisitDto dto) {
        versions.lock(); // before reading: last-write-wins check and write are atomic (F-09)
        var incomingUpdatedAt = clock.accept(dto.updatedAt(), "updatedAt");
        clock.validate(dto.arrivedAt(), "arrivedAt");
        clock.validate(dto.leftAt(), "leftAt");
        if (dto.leftAt() != null && dto.leftAt().isBefore(dto.arrivedAt())) {
            throw new IllegalArgumentException("leftAt must not be before arrivedAt");
        }
        var visit = repo.findById(id).orElseGet(() -> new Visit(id));
        if (visit.getUpdatedAt() != null && visit.getUpdatedAt().isAfter(incomingUpdatedAt)) {
            return VisitDto.from(visit);
        }
        var previousHouseId = visit.getHouseId();
        visit.setHouseId(dto.houseId());
        visit.setLat(dto.lat());
        visit.setLon(dto.lon());
        visit.setStreet(dto.street());
        visit.setArrivedAt(dto.arrivedAt());
        visit.setLeftAt(dto.leftAt());
        visit.setSource(dto.source() == null ? VisitSource.MANUAL : dto.source());
        visit.setDeleted(dto.deleted());
        if (dto.deleted()) visit.purgePlace();
        visit.setUpdatedAt(incomingUpdatedAt);
        visit.setSyncVersion(versions.next());
        var saved = repo.save(visit);
        // The house's AI index includes a visit summary, so tell it which houses changed.
        if (dto.houseId() != null) events.publishEvent(new HouseChangedEvent(dto.houseId()));
        if (previousHouseId != null && !previousHouseId.equals(dto.houseId())) {
            events.publishEvent(new HouseChangedEvent(previousHouseId));
        }
        return VisitDto.from(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        versions.lock();
        var visit = repo.findById(id).orElseThrow(() -> new NotFoundException("Visit " + id + " not found"));
        if (!visit.isDeleted()) {
            visit.setDeleted(true);
            visit.purgePlace();
            visit.setUpdatedAt(clock.now());
            visit.setSyncVersion(versions.next());
            if (visit.getHouseId() != null) events.publishEvent(new HouseChangedEvent(visit.getHouseId()));
        }
        return ResponseEntity.noContent().build();
    }
}
