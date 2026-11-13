package dev.stowling.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.client.ClientShutdownSequence.ShutdownStep;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientShutdownSequenceTest {
    @Test
    void invokesEveryCleanupOnceEvenWhenAnEarlierStepFails() {
        List<String> calls = new ArrayList<>();
        ShutdownStep baritone = new ShutdownStep("Baritone", () -> {
            calls.add("baritone");
            throw new IllegalStateException("expected test failure");
        });
        ShutdownStep http = new ShutdownStep("HTTP server", () -> calls.add("http"));
        ShutdownStep bridge = new ShutdownStep("headless bridge", () -> calls.add("bridge"));
        ClientShutdownSequence sequence = new ClientShutdownSequence(List.of(
                baritone,
                http,
                bridge
        ));

        sequence.close();
        sequence.close();

        assertEquals(List.of("baritone", "http", "bridge"), calls);
    }
}
