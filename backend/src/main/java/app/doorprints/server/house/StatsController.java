package app.doorprints.server.house;

import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    /**
     * The counts, and {@code maxSyncVersion}: the highest sync version this server has handed out
     * ({@link SyncVersions#highest()}). A client whose stored pull cursor is above it is talking to a server that was
     * reset or restored from an older dump, and re-sends everything (S4b-BL-20). Added 2026-09-24; clients treat a
     * missing field (an older server) as unknown.
     */
    public record Stats(long houses, long shortlisted, long rejected, long visits, long streets, long maxSyncVersion) {
    }

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final SyncVersions versions;

    public StatsController(HouseRepository houses, VisitRepository visits, SyncVersions versions) {
        this.houses = houses;
        this.visits = visits;
        this.versions = versions;
    }

    @GetMapping("/api/stats")
    public Stats stats() {
        return new Stats(
                houses.countByDeletedFalse(),
                houses.countByDeletedFalseAndStatus(HouseStatus.SHORTLISTED),
                houses.countByDeletedFalseAndStatus(HouseStatus.REJECTED),
                visits.countByDeletedFalse(),
                houses.countDistinctStreets(),
                versions.highest());
    }
}
