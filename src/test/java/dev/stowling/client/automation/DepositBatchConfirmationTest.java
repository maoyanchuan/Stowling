package dev.stowling.client.automation;

import static dev.stowling.client.automation.DepositBatchConfirmation.Mode.LIVE;
import static dev.stowling.client.automation.DepositBatchConfirmation.Mode.SERVER_SNAPSHOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.client.automation.DepositBatchConfirmation.Counts;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DepositBatchConfirmationTest {
    @Test
    void continuousRefillsConfirmOnlyTheOriginalBatchWithoutWaitingForAnEmptyBackpack() {
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 0, 0));

        batch.observe(LIVE, key -> new Counts(64, 64, 64));
        assertFalse(batch.settled());
        batch.observe(LIVE, key -> new Counts(96, 64, 96));

        assertTrue(batch.fullyAccepted());
        assertEquals(Map.of("stone", 64), batch.accepted());
        assertFalse(batch.stalled(Duration.ofSeconds(30)));
    }

    @Test
    void anUnconfirmedInPlaceBatchRequestsASafePositionAfterTwoSeconds() {
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 0, 0));
        batch.observe(LIVE, key -> new Counts(64, 0, 0));

        assertFalse(batch.stalled(Duration.ofMillis(1999)));
        assertTrue(batch.stalled(Duration.ofSeconds(2)));
        batch.resetUnsettled();
        batch.observe(SERVER_SNAPSHOT, key -> new Counts(64, 64, 64));
        batch.observe(SERVER_SNAPSHOT, key -> new Counts(64, 64, 64));
        assertTrue(batch.fullyAccepted());
        assertFalse(batch.stalled(Duration.ofSeconds(3)));
    }

    @Test
    void differentItemsAndExactVariantsShareTwoConfirmationPolls() {
        var batch = new DepositBatchConfirmation();
        var before = Map.of("stone", new Counts(128, 32, 0), "disc#a", new Counts(8, 0, 10),
                "disc#b", new Counts(3, 0, 0));
        batch.record("stone", 64, before::get);
        batch.record("disc#a", 8, before::get);
        batch.record("stone", 64, before::get);
        batch.record("disc#b", 2, before::get);
        var after = Map.of("stone", new Counts(0, 160, 0), "disc#a", new Counts(8, 8, 18),
                "disc#b", new Counts(1, 2, 0));

        assertFalse(batch.observe(LIVE, after::get));
        assertFalse(batch.settled());
        assertEquals(Map.of(), batch.accepted());
        assertFalse(batch.observe(LIVE, after::get));
        assertTrue(batch.settled());
        assertTrue(batch.fullyAccepted());
        assertEquals(Map.of("stone", 128, "disc#a", 8, "disc#b", 2), batch.accepted());
    }

    @Test
    void rereadPreservesAcceptedItemsAndAccountsForPartialAndRejectedMoves() {
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 0, 0));
        batch.record("disc", 8, key -> new Counts(8, 10, 4));
        batch.record("dirt", 64, key -> new Counts(64, 0, 0));
        var first = Map.of("stone", new Counts(0, 64, 0), "disc", new Counts(8, 10, 4),
                "dirt", new Counts(64, 0, 0));
        var second = Map.of("stone", new Counts(0, 64, 0), "disc", new Counts(8, 13, 4),
                "dirt", new Counts(64, 0, 0));

        assertFalse(batch.observe(LIVE, first::get));
        assertTrue(batch.observe(LIVE, second::get));
        assertEquals(Map.of("stone", 64), batch.accepted());
        batch.resetUnsettled();
        batch.observe(SERVER_SNAPSHOT, second::get);
        assertFalse(batch.settled());
        batch.observe(SERVER_SNAPSHOT, second::get);
        assertTrue(batch.settled());
        assertFalse(batch.fullyAccepted());
        assertEquals(Map.of("stone", 64, "disc", 3), batch.accepted());
    }

    @Test
    void invalidSnapshotDeltaDoesNotDiscardOtherConfirmedItems() {
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 0, 0));
        batch.record("disc", 8, key -> new Counts(8, 10, 4));
        var after = Map.of("stone", new Counts(0, 64, 0), "disc", new Counts(8, 19, 4));

        batch.observe(SERVER_SNAPSHOT, after::get);
        assertFalse(batch.settled());
        assertEquals(Map.of("stone", 64), batch.accepted());
    }
}
