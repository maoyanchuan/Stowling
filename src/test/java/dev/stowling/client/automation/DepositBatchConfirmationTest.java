package dev.stowling.client.automation;

import static dev.stowling.client.automation.DepositBatchConfirmation.Mode.LIVE;
import static dev.stowling.client.automation.DepositBatchConfirmation.Mode.SERVER_SNAPSHOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerInteractionBatch;
import dev.stowling.client.automation.DepositBatchConfirmation.Counts;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DepositBatchConfirmationTest {
    @Test
    void aFullUnlockedBackpackSharesOneBatchAndWaitsForItsLastServerSnapshot() {
        Object menu = new Object();
        var batch = new DepositBatchConfirmation();
        var budget = new ContainerInteractionBatch("oak_log", true);
        for (int slot = 0; slot < 34; slot++) {
            assertTrue(budget.accepts("oak_log", 1));
            budget.record(64, 1);
            batch.record("oak_log", 64, key -> new Counts(2176, 128, 0));
        }
        assertEquals(2176, budget.count());
        batch.awaitServer(menu, 10, 34);

        for (int response = 1; response < 34; response++) {
            int containerCount = 128 + response * 64;
            assertFalse(batch.observeServer(menu, 10 + response,
                    key -> new Counts(2176, containerCount, 0)));
            assertFalse(batch.settled());
            assertTrue(batch.accepted().isEmpty());
        }

        assertTrue(batch.observeServer(menu, 44, key -> new Counts(2176, 2304, 0)));
        assertTrue(batch.fullyAccepted());
        assertEquals(Map.of("oak_log", 2176), batch.accepted());
    }

    @Test
    void mixedItemWaveConfirmsPartialAndRejectedStacksOnlyAfterAllResponses() {
        Object menu = new Object();
        var batch = new DepositBatchConfirmation();
        var before = Map.of("stone", new Counts(128, 20, 0),
                "disc#a", new Counts(1, 0, 0), "disc#b", new Counts(1, 0, 0));
        batch.record("stone", 64, before::get);
        batch.record("disc#a", 1, before::get);
        batch.record("stone", 64, before::get);
        batch.record("disc#b", 1, before::get);
        batch.awaitServer(menu, 5, 4);
        var after = Map.of("stone", new Counts(63, 85, 0),
                "disc#a", new Counts(0, 1, 0), "disc#b", new Counts(1, 0, 0));

        assertFalse(batch.observeServer(menu, 8, after::get));
        assertFalse(batch.settled());
        assertTrue(batch.accepted().isEmpty());
        assertTrue(batch.observeServer(menu, 9, after::get));
        assertTrue(batch.settled());
        assertFalse(batch.fullyAccepted());
        assertEquals(Map.of("stone", 65, "disc#a", 1), batch.accepted());
    }

    @Test
    void laterWavesCannotUseAnEarlierResponseOrAReplacementMenu() {
        Object menu = new Object();
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 128, key -> new Counts(128, 2176, 0));
        batch.awaitServer(menu, 44, 2);

        assertFalse(batch.observeServer(menu, 44, key -> new Counts(0, 2304, 0)));
        assertFalse(batch.observeServer(menu, 45, key -> new Counts(0, 2304, 0)));
        assertFalse(batch.observeServer(new Object(), 100, key -> new Counts(0, 2304, 0)));
        assertTrue(batch.accepted().isEmpty());
        assertTrue(batch.observeServer(menu, 46, key -> new Counts(0, 2304, 0)));
        assertEquals(Map.of("stone", 128), batch.accepted());
    }

    @Test
    void aServerWaveMustIncludeAtLeastOneResponse() {
        var batch = new DepositBatchConfirmation();
        assertThrows(IllegalArgumentException.class,
                () -> batch.awaitServer(new Object(), 1, 0));
    }

    @Test
    void clientPredictionNeverConfirmsAnOrdinaryBatch() {
        Object menu = new Object();
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 0, 0));
        batch.awaitServer(menu, 1);
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(batch.observeServer(menu, 1, key -> new Counts(0, 64, 0)));
        }
        assertFalse(batch.settled());
        assertTrue(batch.accepted().isEmpty());
    }

    @Test
    void sameMenuServerSnapshotConfirmsDespiteRefillAndDelayedReceipts() {
        Object menu = new Object();
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 20, 0));
        batch.awaitServer(menu, 3);
        assertTrue(batch.observeServer(menu, 4, key -> new Counts(128, 84, 0)));
        assertTrue(batch.fullyAccepted());
        assertEquals(Map.of("stone", 64), batch.accepted());
    }

    @Test
    void reusedWindowOrOldGenerationCannotConfirmAnotherBatch() {
        Object first = new Object();
        Object second = new Object();
        var batch = new DepositBatchConfirmation();
        batch.record("stone", 64, key -> new Counts(64, 0, 0));
        batch.awaitServer(second, 2);
        assertFalse(batch.observeServer(first, 99, key -> new Counts(0, 64, 0)));
        assertFalse(batch.observeServer(second, 2, key -> new Counts(0, 64, 0)));
        assertTrue(batch.accepted().isEmpty());
    }

    @Test
    void authoritativePartialAcceptanceAndRollbackPreserveRemainingCargo() {
        Object menu = new Object();
        var partial = new DepositBatchConfirmation();
        partial.record("stone", 64, key -> new Counts(64, 20, 0));
        partial.awaitServer(menu, 1);
        partial.observeServer(menu, 2, key -> new Counts(61, 23, 0));
        assertTrue(partial.settled());
        assertFalse(partial.fullyAccepted());
        assertEquals(Map.of("stone", 3), partial.accepted());
        var rejected = new DepositBatchConfirmation();
        rejected.record("stone", 64, key -> new Counts(64, 23, 0));
        rejected.awaitServer(menu, 2);
        rejected.observeServer(menu, 3, key -> new Counts(64, 23, 0));
        assertTrue(rejected.settled());
        assertTrue(rejected.accepted().isEmpty());
    }

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
    void legacyBusinessBatchCanRequestAnAuthoritativeRereadAfterTwoSeconds() {
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
