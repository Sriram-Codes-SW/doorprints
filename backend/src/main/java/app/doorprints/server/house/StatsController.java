package app.doorprints.server.house;

import app.doorprints.server.visit.VisitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    public record Stats(long houses, long shortlisted, long rejected, long visits, long streets) {
    }

    private final HouseRepository houses;
    private final VisitRepository visits;

    public StatsController(HouseRepository houses, VisitRepository visits) {
        this.houses = houses;
        this.visits = visits;
    }

    @GetMapping("/api/stats")
    public Stats stats() {
        return new Stats(
                houses.countByDeletedFalse(),
                houses.countByDeletedFalseAndStatus(HouseStatus.SHORTLISTED),
                houses.countByDeletedFalseAndStatus(HouseStatus.REJECTED),
                visits.countByDeletedFalse(),
                houses.countDistinctStreets());
    }
}
