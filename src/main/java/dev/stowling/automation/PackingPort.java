package dev.stowling.automation;

import dev.stowling.schedule.StopOutcome;
import java.util.UUID;

public interface PackingPort {
    PackingDisposition submit(PackingRequest request);

    default PackingDisposition poll(PackingRequest request) {
        return submit(request);
    }

    default StopOutcome requestStop(UUID taskId) {
        return StopOutcome.UNSAFE;
    }

    default StopOutcome pollStop(UUID taskId) {
        return StopOutcome.UNSAFE;
    }
}
