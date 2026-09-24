package app.doorprints.server.house;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/houses")
public class HouseController {

    private final HouseService service;

    public HouseController(HouseService service) {
        this.service = service;
    }

    /** All live houses, or — with {@code since} — every change (including deletions) after that sync version. */
    @GetMapping
    public List<HouseDto> list(@RequestParam(required = false) Long since) {
        return service.list(since);
    }

    @GetMapping("/{id}")
    public HouseDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public HouseDto upsert(@PathVariable UUID id, @Valid @RequestBody HouseDto body) {
        return service.upsert(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Built-in MVC method validation (Spring 6.1+) turns a bad lat/lon/radius into 400 (F-19). */
    @GetMapping("/nearby")
    public List<HouseDto> nearby(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                 @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
                                 @RequestParam(defaultValue = "50") @Positive double radius) {
        return service.nearby(lat, lon, Math.min(radius, 5000));
    }

    @GetMapping("/street")
    public List<HouseDto> onStreet(@RequestParam @NotBlank @Size(max = 200) String name) {
        return service.onStreet(name);
    }
}
