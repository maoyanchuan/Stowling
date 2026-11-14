package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationOperationStatus;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerAttemptStatus;
import dev.stowling.automation.ItemAmount;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MinecraftContainerInteractionItemTransferTest {
    private static final String STONE = "minecraft:stone";
    private static final String BULK = "bulk";
    private static final String STORAGE = "storage";

    private final ContainerRef bulk = container(BULK, 1);
    private final ContainerRef storage = container(STORAGE, 2);
    private final Map<String, ContainerRef> containers = Map.of(
            BULK,
            bulk,
            STORAGE,
            storage
    );

    @Test
    void mapsPutFromPlayerToTheRealDestination() {
        RecordingDriver driver = new RecordingDriver();
        MinecraftContainerInteraction interaction = interaction(driver);
        ItemTransferPort.Request request = request(
                ItemTransferPort.Mode.PUT,
                "PLAYER",
                STORAGE,
                4
        );

        AutomationOperationResult result = interaction.start("put-key", request);

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertEquals(1, driver.startCount);
        assertTrue(driver.transfer.source().isEmpty());
        assertEquals(Optional.of(storage), driver.transfer.destination());
        assertEquals(List.of(new ItemAmount(STONE, 4)), driver.transfer.items());
    }

    @Test
    void mapsTransferBetweenRealContainers() {
        RecordingDriver driver = new RecordingDriver();
        MinecraftContainerInteraction interaction = interaction(driver);
        ItemTransferPort.Request request = request(
                ItemTransferPort.Mode.TRANSFER,
                BULK,
                STORAGE,
                4
        );

        interaction.start("transfer-key", request);

        assertEquals(1, driver.startCount);
        assertEquals(Optional.of(bulk), driver.transfer.source());
        assertEquals(Optional.of(storage), driver.transfer.destination());
    }

    @Test
    void mapsTakeFromTheRealSourceWithoutInventingAPlayerContainer() {
        RecordingDriver driver = new RecordingDriver();
        MinecraftContainerInteraction interaction = interaction(driver);
        ItemTransferPort.Request request = request(
                ItemTransferPort.Mode.TAKE,
                BULK,
                "PLAYER",
                4
        );

        interaction.start("take-key", request);

        assertEquals(1, driver.startCount);
        assertEquals(Optional.of(bulk), driver.transfer.source());
        assertTrue(driver.transfer.destination().isEmpty());
        assertFalse(driver.transfer.destination().stream()
                .anyMatch(candidate -> "PLAYER".equals(candidate.id())));
    }

    @Test
    void doesNotStartASecondAttemptForAnActiveOperationKey() {
        RecordingDriver driver = new RecordingDriver();
        MinecraftContainerInteraction interaction = interaction(driver);
        ItemTransferPort.Request request = request(
                ItemTransferPort.Mode.PUT,
                "PLAYER",
                STORAGE,
                4
        );

        AutomationOperationResult first = interaction.start("same-key", request);
        AutomationOperationResult second = interaction.start("same-key", request);

        assertEquals(AutomationOperationStatus.RUNNING, first.status());
        assertEquals(AutomationOperationStatus.RUNNING, second.status());
        assertEquals(1, driver.startCount);
        assertTrue(second.message().orElseThrow().contains("already active"));
    }

    @Test
    void missingAttemptReconcileRequiresManualRecovery() {
        RecordingDriver driver = new RecordingDriver();
        MinecraftContainerInteraction interaction = interaction(driver);

        AutomationOperationResult result = interaction.reconcile("missing-key");

        assertEquals(AutomationOperationStatus.PAUSED, result.status());
        assertTrue(result.message().orElseThrow().contains("manual recovery"));
        assertEquals(0, driver.startCount);
        assertEquals(0, driver.pollCount);
    }

    @Test
    void removesTerminalAttemptWithoutReplayingAStalePoll() {
        RecordingDriver driver = new RecordingDriver();
        driver.pollResult = new ContainerAttemptResult(
                ContainerAttemptStatus.ACKNOWLEDGED,
                9,
                Optional.empty(),
                Optional.empty(),
                "server confirmation observed"
        );
        MinecraftContainerInteraction interaction = interaction(driver);
        ItemTransferPort.Request request = request(
                ItemTransferPort.Mode.PUT,
                "PLAYER",
                STORAGE,
                4
        );
        interaction.start("stale-key", request);

        AutomationOperationResult completed = interaction.poll("stale-key");
        AutomationOperationResult stale = interaction.poll("stale-key");

        assertEquals(AutomationOperationStatus.SUCCEEDED, completed.status());
        assertEquals(AutomationOperationStatus.FAILED, stale.status());
        assertEquals(1, driver.pollCount);
        assertTrue(completed.lastConfirmedAction().orElseThrow().contains("PUT"));
        assertTrue(stale.message().orElseThrow().contains("manual recovery"));
    }

    @Test
    void cancelRequestsSafeConfirmationInsteadOfClosingImmediately() {
        RecordingDriver driver = new RecordingDriver();
        driver.stopOutcome = StopOutcome.PENDING;
        driver.pollStopOutcome = StopOutcome.PENDING;
        MinecraftContainerInteraction interaction = interaction(driver);
        UUID taskId = UUID.randomUUID();
        String operationKey = taskId + ":transfer";
        ItemTransferPort.Request request = request(
                ItemTransferPort.Mode.PUT,
                "PLAYER",
                STORAGE,
                4
        );
        interaction.start(operationKey, request);
        ContainerAttempt attempt = new ContainerAttempt(
                operationKey,
                new ContainerTransfer(
                        "stop-transfer",
                        Optional.empty(),
                        Optional.of(storage),
                        List.of(new ItemAmount(STONE, 4))
                ),
                0,
                java.time.Instant.EPOCH
        );

        interaction.cancel(attempt);

        assertEquals(1, driver.stopCalls);
        assertEquals(0, driver.cancelCalls);
        assertEquals(StopOutcome.PENDING, interaction.pollStop(attempt));

        driver.pollStopOutcome = StopOutcome.CLEAN;
        assertEquals(StopOutcome.CLEAN, interaction.pollStop(attempt));
        assertEquals(StopOutcome.CLEAN, interaction.pollStop(attempt));
    }

    private MinecraftContainerInteraction interaction(RecordingDriver driver) {
        return new MinecraftContainerInteraction(
                new ImmediateScheduler(),
                id -> Optional.ofNullable(containers.get(id)),
                driver
        );
    }

    private static ItemTransferPort.Request request(
            ItemTransferPort.Mode mode,
            String source,
            String destination,
            int count
    ) {
        return new ItemTransferPort.Request(
                mode,
                source,
                destination,
                STONE,
                count
        );
    }

    private static ContainerRef container(String id, int x) {
        return new ContainerRef(
                id,
                "minecraft:overworld",
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    private static final class RecordingDriver
            implements MinecraftContainerInteraction.OperationDriver {
        private int startCount;
        private int pollCount;
        private int stopCalls;
        private int pollStopCalls;
        private int cancelCalls;
        private StopOutcome stopOutcome = StopOutcome.UNSAFE;
        private StopOutcome pollStopOutcome = StopOutcome.UNSAFE;
        private MinecraftContainerInteraction.TransferSpec transfer;
        private ContainerAttemptResult pollResult = new ContainerAttemptResult(
                ContainerAttemptStatus.PENDING,
                0,
                Optional.empty(),
                Optional.empty(),
                "waiting"
        );

        @Override
        public ContainerAttemptResult start(
                MinecraftContainerInteraction.AttemptToken token,
                MinecraftContainerInteraction.TransferSpec transfer
        ) {
            startCount++;
            this.transfer = transfer;
            return new ContainerAttemptResult(
                    ContainerAttemptStatus.PENDING,
                    token.baselineRevision(),
                    Optional.empty(),
                    Optional.empty(),
                    "started"
            );
        }

        @Override
        public ContainerAttemptResult poll(
                MinecraftContainerInteraction.AttemptToken token
        ) {
            pollCount++;
            return pollResult;
        }

        @Override
        public void cancel(MinecraftContainerInteraction.AttemptToken token) {
            cancelCalls++;
        }

        @Override
        public StopOutcome requestStop(
                MinecraftContainerInteraction.AttemptToken token
        ) {
            stopCalls++;
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop(
                MinecraftContainerInteraction.AttemptToken token
        ) {
            pollStopCalls++;
            return pollStopOutcome;
        }
    }

    private static final class ImmediateScheduler implements ClientActionScheduler {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            return CompletableFuture.completedFuture(action.get());
        }
    }
}
