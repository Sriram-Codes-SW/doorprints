package app.doorprints.server.sync;

import app.doorprints.server.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Guards "last write wins" against client clocks (threat model F-08, SEC-020).
 *
 * <ul>
 *   <li>{@code null} means "now".</li>
 *   <li>Absurd values (before 2000-01-01, or more than {@code app.sync.max-future-days} ahead) are rejected with 400:
 *       they are bugs or tampering, and a record stamped years ahead could otherwise never be edited again.</li>
 *   <li>Values ahead of server time by more than {@code app.sync.max-clock-skew-seconds} are clamped to server now,
 *       so a phone whose clock runs fast cannot "win" every later conflict.</li>
 * </ul>
 */
@Component
public class ClientClock {

    static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");

    private final Clock clock;
    private final Duration maxSkew;
    private final Duration maxFuture;

    @Autowired
    public ClientClock(AppProperties props) {
        this(Clock.systemUTC(), props.sync().maxClockSkewSeconds(), props.sync().maxFutureDays());
    }

    ClientClock(Clock clock, int maxSkewSeconds, int maxFutureDays) {
        this.clock = clock;
        this.maxSkew = Duration.ofSeconds(maxSkewSeconds);
        this.maxFuture = Duration.ofDays(maxFutureDays);
    }

    public Instant now() {
        return clock.instant();
    }

    /** The timestamp to store for a client-supplied {@code updatedAt}/{@code createdAt}. */
    public Instant accept(Instant clientTime, String field) {
        var now = now();
        if (clientTime == null) return now;
        check(clientTime, field, now);
        return clientTime.isAfter(now.plus(maxSkew)) ? now : clientTime;
    }

    /**
     * Validates an event time that is kept as given (visit arrival/departure): rejects absurd values but does not
     * clamp, because it describes when something happened, not the edit order.
     */
    public Instant validate(Instant eventTime, String field) {
        if (eventTime != null) check(eventTime, field, now());
        return eventTime;
    }

    private void check(Instant t, String field, Instant now) {
        if (t.isBefore(EARLIEST) || t.isAfter(now.plus(maxFuture))) {
            throw new IllegalArgumentException(field + " is out of range (check the device clock): " + t);
        }
    }
}
