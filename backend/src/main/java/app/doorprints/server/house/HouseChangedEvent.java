package app.doorprints.server.house;

import java.util.UUID;

/**
 * Published inside the write transaction whenever a house (or a visit that belongs to it) changes.
 * Listeners that need committed data use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 * With AI disabled nobody listens, so this costs nothing.
 */
public record HouseChangedEvent(UUID houseId) {
}
