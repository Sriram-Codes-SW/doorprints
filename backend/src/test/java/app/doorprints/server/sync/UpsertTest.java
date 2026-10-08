package app.doorprints.server.sync;

import app.doorprints.server.sync.Upsert.Decision;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tie rule of the push endpoints (S4b-BL-163, docs/03 section 10.1): last write wins on {@code updatedAt}; an
 * equal stamp with the same content writes nothing, an equal stamp with other content is a write (the incoming row
 * wins, as it does on the clients' pull). Every cell of the table, with no database.
 */
class UpsertTest {

    private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant EARLIER = T.minusMillis(1);
    private static final Instant LATER = T.plusMillis(1);

    @Test
    void aRowThatIsNotStoredIsCreatedWhateverTheContentCheckSays() {
        assertThat(Upsert.decide(null, T, () -> true)).isEqualTo(Decision.CREATE);
        assertThat(Upsert.decide(null, T, () -> false)).isEqualTo(Decision.CREATE);
    }

    @Test
    void aStoredRowThatIsNewerIsKeptAndTheContentIsNotEvenCompared() {
        assertThat(Upsert.decide(LATER, T, () -> { throw new AssertionError("compared"); }))
                .isEqualTo(Decision.KEEP_STORED);
    }

    @Test
    void anIncomingRowThatIsNewerOverwritesWhetherOrNotTheContentDiffers() {
        assertThat(Upsert.decide(EARLIER, T, () -> true)).isEqualTo(Decision.OVERWRITE);
        assertThat(Upsert.decide(EARLIER, T, () -> false)).isEqualTo(Decision.OVERWRITE);
    }

    @Test
    void anEqualStampWithTheSameContentWritesNothing() {
        assertThat(Upsert.decide(T, T, () -> true)).isEqualTo(Decision.UNCHANGED);
    }

    @Test
    void anEqualStampWithOtherContentOverwritesSoATieStillConverges() {
        assertThat(Upsert.decide(T, T, () -> false)).isEqualTo(Decision.OVERWRITE);
    }

    @Test
    void onlyAnEqualStampLooksAtTheContent() {
        int[] calls = {0};
        Upsert.decide(T, T, () -> { calls[0]++; return true; });
        Upsert.decide(EARLIER, T, () -> { calls[0]++; return true; });
        Upsert.decide(LATER, T, () -> { calls[0]++; return true; });
        Upsert.decide(null, T, () -> { calls[0]++; return true; });
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void aStampThatDiffersByTheSmallestUnitIsNotATie() {
        var micro = T.plusNanos(1000);
        assertThat(Upsert.decide(micro, T, () -> true)).isEqualTo(Decision.KEEP_STORED);
        assertThat(Upsert.decide(T, micro, () -> true)).isEqualTo(Decision.OVERWRITE);
    }
}
